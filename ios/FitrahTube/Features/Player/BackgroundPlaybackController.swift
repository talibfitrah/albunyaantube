import AVFoundation
import MediaPlayer
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
    /// Task 4's Now Playing inputs. `args` stays nil until `PlayerHostView` hands it over, and a
    /// controller with no args publishes nothing rather than an empty lock screen.
    private var args: PlayerArgs?
    private var state: StreamState = .idle
    private var artwork: MPMediaItemArtwork?
    private var artworkURL: URL?
    private var artworkTask: Task<Void, Never>?
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
        installRemoteCommands()
    }

    func detach() {
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player = nil
        // Same one-owner rule as the audio session: a dismantled player must not keep owning the
        // lock-screen transport. `removeRemoteCommands` also clears `nowPlayingInfo`.
        removeRemoteCommands()
        artworkTask?.cancel()
        artworkTask = nil
        artwork = nil
        artworkURL = nil
        args = nil
        state = .idle
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

    // MARK: - Now Playing (Task 4)

    /// Called from `PlayerHostView` on every make/update pass -- which is also every item
    /// replacement (the audio-only swap in and out, a recovery `replaceCurrentItem`, a quality
    /// pick): the lock screen is republished from whatever item is live NOW, never only from the
    /// first prepare.
    func update(args: PlayerArgs, state: StreamState) {
        self.args = args
        self.state = state
        loadArtworkIfNeeded(args.thumbnailURL)
        publishNowPlaying()
    }

    /// The elapsed/rate refresh. Driven by the Coordinator's EXISTING 1 s periodic time observer,
    /// which AVFoundation also fires on every rate change and time jump -- so a play, a pause and a
    /// seek all land here. Deliberately not a second periodic observer on the same player.
    func refreshNowPlaying() {
        publishNowPlaying()
    }

    private func publishNowPlaying() {
        let elapsed = player?.currentTime().seconds ?? 0
        // CF-B6: `Resolved` carries no duration, so the item's own is the authority the moment it
        // is finite (HLS reports `indefinite` until the playlist is parsed, and always for a live
        // stream); `NowPlayingSnapshot.make` falls back to `args.durationSeconds` until then.
        let measured = player?.currentItem?.duration.seconds
        guard let args, let player,
              let snapshot = NowPlayingSnapshot.make(
                  args: args, state: state,
                  elapsed: elapsed.isFinite ? elapsed : 0,
                  duration: measured.flatMap { $0.isFinite && $0 > 0 ? $0 : nil },
                  rate: player.rate) else {
            MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
            return
        }
        var info = snapshot.info
        if let artwork { info[MPMediaItemPropertyArtwork] = artwork }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = info
    }

    /// Reuses `RemoteImage`'s loader (its https guard, its 10 MB cap and its decoded-image cache)
    /// rather than a second `URLSession` fetch: the card the user tapped to get here has almost
    /// always already decoded this exact thumbnail, so this is normally a cache hit with no network
    /// at all. Failures are swallowed -- no artwork is a cosmetic degradation, never an error.
    private func loadArtworkIfNeeded(_ url: URL?) {
        guard url != artworkURL else { return }
        artworkURL = url
        artwork = nil
        artworkTask?.cancel()
        artworkTask = nil
        guard let url else { return }
        artworkTask = Task { [weak self] in
            guard let image = await RemoteImage.cachedImage(for: url), !Task.isCancelled else { return }
            self?.artwork = MPMediaItemArtwork(boundsSize: image.size) { _ in image }
            self?.publishNowPlaying()
        }
    }

    // MARK: - Remote command centre (Task 4)

    /// Every command republishes: the lock-screen scrubber is only honest if elapsed/rate go out
    /// the moment the transport moved, not on the next periodic tick.
    private func act(_ body: (AVPlayer) -> Void) -> MPRemoteCommandHandlerStatus {
        guard let player else { return .noActionableNowPlayingItem }
        body(player)
        publishNowPlaying()
        return .success
    }

    private static var transportCommands: [MPRemoteCommand] {
        let center = MPRemoteCommandCenter.shared()
        return [center.playCommand, center.pauseCommand, center.togglePlayPauseCommand,
                center.skipForwardCommand, center.skipBackwardCommand,
                center.changePlaybackPositionCommand]
    }

    private func installRemoteCommands() {
        let center = MPRemoteCommandCenter.shared()
        // Re-attaching (a new player on the same controller) must not stack a second handler on a
        // command that already has one.
        Self.transportCommands.forEach { $0.removeTarget(nil) }
        _ = center.playCommand.addTarget { [weak self] _ in self?.act { $0.play() } ?? .commandFailed }
        _ = center.pauseCommand.addTarget { [weak self] _ in self?.act { $0.pause() } ?? .commandFailed }
        _ = center.togglePlayPauseCommand.addTarget { [weak self] _ in
            self?.act { $0.timeControlStatus == .paused ? $0.play() : $0.pause() } ?? .commandFailed
        }
        center.skipForwardCommand.preferredIntervals = [10]
        center.skipBackwardCommand.preferredIntervals = [10]
        _ = center.skipForwardCommand.addTarget { [weak self] event in
            self?.skip(by: (event as? MPSkipIntervalCommandEvent)?.interval ?? 10) ?? .commandFailed
        }
        _ = center.skipBackwardCommand.addTarget { [weak self] event in
            self?.skip(by: -((event as? MPSkipIntervalCommandEvent)?.interval ?? 10)) ?? .commandFailed
        }
        _ = center.changePlaybackPositionCommand.addTarget { [weak self] event in
            guard let event = event as? MPChangePlaybackPositionCommandEvent else { return .commandFailed }
            return self?.act { $0.seek(to: CMTime(seconds: event.positionTime, preferredTimescale: 600)) }
                ?? .commandFailed
        }
        Self.transportCommands.forEach { $0.isEnabled = true }
        // B5 owns Up Next / the playlist queue. Until then these must be visibly absent, not dead
        // (ruling 28's "dead buttons are worse than absent ones", applied to transport).
        center.nextTrackCommand.isEnabled = false
        center.previousTrackCommand.isEnabled = false
    }

    private func skip(by interval: TimeInterval) -> MPRemoteCommandHandlerStatus {
        act { $0.seek(to: $0.currentTime() + CMTime(seconds: interval, preferredTimescale: 600)) }
    }

    private func removeRemoteCommands() {
        Self.transportCommands.forEach {
            $0.removeTarget(nil)
            $0.isEnabled = false
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
