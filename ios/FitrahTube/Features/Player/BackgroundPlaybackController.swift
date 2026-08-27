import AVFoundation
import UIKit

/// Owns everything that happens to playback while the player view is not the thing on screen:
/// the audio session, interruptions, route changes, and (Task 4) the Now Playing surface.
/// Created and torn down by `PlayerHostView.Coordinator`, which already owns the KVO/stall
/// observers -- one lifecycle owner, not two.
@MainActor final class BackgroundPlaybackController {
    var backgroundPlay: Bool { didSet { applyBackgroundPolicy() } }
    var pictureInPictureActive = false { didSet { applyBackgroundPolicy() } }
    /// The player's live audio-only toggle (`PlayerViewModel.audioOnly`), fed on every host update.
    var userAudioOnly = false
    /// Whether the resolved stream carries an itag 140 URL at all (`PlayerViewModel.audioOnlyAvailable`).
    /// Defaults to false so a controller nobody has told yet never decides a swap it cannot honour.
    var audioOnlyAvailable = false
    /// `PlayerHostView` performs the swap itself (through the VM's `audioOnly`). Left nil, the swap
    /// actions only move this controller's own `autoSwappedToAudioOnly` flag.
    var onPolicyAction: ((PlaybackPolicyAction) -> Void)?

    private weak var player: AVPlayer?
    private var observers: [NSObjectProtocol] = []
    private var wasPlayingBeforeInterruption = false
    private(set) var autoSwappedToAudioOnly = false

    init(backgroundPlay: Bool) {
        self.backgroundPlay = backgroundPlay
    }

    /// `.playback` + `.moviePlayback`, no `mixWithOthers` (ruling 44). Idempotent: called on every
    /// attach; AVAudioSession tolerates a repeat set/activate. Errors are logged and swallowed --
    /// a failed activation must degrade to "no background audio", never to a crash on open.
    static func configureAudioSession() {
        let session = AVAudioSession.sharedInstance()
        do {
            try session.setCategory(.playback, mode: .moviePlayback)
            try session.setActive(true)
        } catch {
            #if DEBUG
            print("[BackgroundPlaybackController] audio session activation failed: \(error)")
            #endif
        }
    }

    /// Idempotent by player identity: `PlayerHostView.updateUIViewController` runs on every state
    /// change, most of which hand back the very same `AVPlayer`. Re-attaching it would mean a
    /// `detach()` (which deactivates the audio session with `.notifyOthersOnDeactivation`) followed
    /// by a re-activation on every unrelated update -- the guard belongs here, at the one place all
    /// callers route through, not in each caller.
    func attach(player: AVPlayer) {
        guard player !== self.player else { return }
        detach()
        self.player = player
        Self.configureAudioSession()
        applyBackgroundPolicy()
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.didEnterBackgroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.handle(.enteredBackground) }
            },
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated { self?.handle(.willEnterForeground) }
            },
            // `Notification` is not `Sendable` on this toolchain, so the payload is decoded HERE,
            // in the nonisolated observer block, and only the resulting Sendable values cross into
            // the main actor -- handing `note` itself to an isolated method is a Swift 6 error
            // ("sending 'note' risks causing data races").
            center.addObserver(forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { [weak self] note in
                let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                    .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
                let optionsRaw = note.userInfo?[AVAudioSessionInterruptionOptionKey] as? UInt ?? 0
                MainActor.assumeIsolated { self?.handleInterruption(type: type, optionsRaw: optionsRaw) }
            },
            center.addObserver(forName: AVAudioSession.routeChangeNotification, object: nil, queue: .main) { [weak self] note in
                let reason = (note.userInfo?[AVAudioSessionRouteChangeReasonKey] as? UInt)
                    .flatMap(AVAudioSession.RouteChangeReason.init(rawValue:))
                MainActor.assumeIsolated {
                    self?.handle(.routeChanged(oldDeviceUnavailable: reason == .oldDeviceUnavailable))
                }
            }
        ]
    }

    func detach() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player = nil
        // `.notifyOthersOnDeactivation` hands the session back politely so a paused music app
        // resumes instead of staying silent.
        try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation)
    }

    private func applyBackgroundPolicy() {
        player?.audiovisualBackgroundPlaybackPolicy =
            AudioSessionPolicy.backgroundPolicy(backgroundPlay: backgroundPlay,
                                                pictureInPictureActive: pictureInPictureActive) == .continues
            ? .continuesIfPossible : .pauses
    }

    private var context: PlaybackPolicyContext {
        PlaybackPolicyContext(backgroundPlay: backgroundPlay, userAudioOnly: userAudioOnly,
                              audioOnlyAvailable: audioOnlyAvailable,
                              pictureInPictureActive: pictureInPictureActive,
                              wasPlayingBeforeInterruption: wasPlayingBeforeInterruption,
                              autoSwappedToAudioOnly: autoSwappedToAudioOnly)
    }

    private func handleInterruption(type: AVAudioSession.InterruptionType?, optionsRaw: UInt) {
        switch type {
        case .began:
            wasPlayingBeforeInterruption = player?.timeControlStatus != .paused
            handle(.interruptionBegan)
        case .ended:
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
            if shouldResume { Self.configureAudioSession() }  // the session was deactivated by the interruption
            handle(.interruptionEnded(shouldResume: shouldResume))
        default:
            break   // nil (undecodable payload) and any future case: do nothing
        }
    }

    private func handle(_ event: PlaybackLifecycleEvent) {
        let action = AudioSessionPolicy.decide(event, context)
        switch action {
        case .none: break
        case .pause: player?.pause()
        case .resume: player?.play()
        case .swapToAudioOnly: autoSwappedToAudioOnly = true
        case .restoreVideo: autoSwappedToAudioOnly = false
        }
        onPolicyAction?(action)   // Task 3 performs the actual URL swap
    }
}
