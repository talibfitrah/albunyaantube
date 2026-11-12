import AVFoundation
import MediaPlayer
import UIKit

/// Owns everything that happens to playback while the player view is not the thing on screen:
/// the audio session, interruptions, route changes, and (Task 4) the Now Playing surface and its
/// remote commands.
/// Created and torn down by `PlayerHostView.Coordinator`, which already owns the KVO/stall
/// observers -- one lifecycle owner, not two.
@MainActor final class BackgroundPlaybackController {
    var backgroundPlay: Bool { didSet { applyBackgroundPolicy() } }
    var pictureInPictureActive = false { didSet { applyBackgroundPolicy() } }
    /// `.pause` reports the pauses that end the user's playback, headphones out and the remote pause command (Cubic A6):
    /// a player a failure already paused cannot show the host one. An interruption's pause is NOT
    /// reported (fix r4 N-2): its end decides, through `resumeIntentPending` below, and an end
    /// without `.shouldResume` reports `.pause` (fix r5 N4-1).
    var onPolicyAction: ((PlaybackPolicyAction) -> Void)?
    /// True while the host owes recovery's replacement a play (`Coordinator.resumeOnNextItem`), or an
    /// auto-advance is in flight (round 3, m1): the failure, or the ended item, already left the
    /// player paused, so the player alone reads "not playing" at an interruption that begins then,
    /// and the end of the interruption would never resume it.
    var resumeIntentPending: (() -> Bool)?
    /// True while a newer video open holds the host's player (`PlayerViewModel.heldByOpen`): the
    /// interruption's end does not resume it under the opened one.
    var autoplayHeld: (() -> Bool)?
    /// True from an interruption's begin to its end (fix r5 N4-1): recovery's replacement must not
    /// play meanwhile (`Coordinator.observe`); the end's `.resume` plays it when iOS says
    /// `.shouldResume`. iOS does not promise the end (Apple: treat the user's Play as one), so the
    /// host's sampler also clears it once the player is seen wanting to play.
    var interrupted = false {
        didSet { if oldValue, !interrupted { Self.sessionInterrupted = false } }   // a Play ends it for all
    }
    /// 1.0.4 round 2: the same interruption for EVERY player -- one `AVAudioSession` per process. A
    /// host rebuilt mid-interruption (an auto-skip past an error state) has a controller that never
    /// saw the begin; it reads this before its player plays (`PlayerHostView.makeUIViewController`)
    /// and before `attach` activates the session. Kept by one process-wide observer, so an end that
    /// lands with no player mounted still clears it. Per-player `interrupted` stays as it was.
    private(set) static var sessionInterrupted = false

    /// Round 3 (review m2, ruling): a user's open, Up Next tap or Retry is the user's Play (Apple:
    /// treat Play as the interruption's end). Another media app started from Control Center sends a
    /// begin and never an end, so without this every later open waited for a tap. Called from
    /// `Router.push` for `.player`/`.shorts` (a tap or a deep link; round 5 N2 moved it off the
    /// screen's mount, which an iPad resize also re-runs), `PlayerScreen.playUpNext`, `PlayerHostView.trackSkip` and
    /// `PlayerScreen.retryTapped`. Never for an automatic start: an advance, an auto-skip's rebuild.
    static func userAskedToPlay() { sessionInterrupted = false }
    private static var sessionObserver: NSObjectProtocol?
    /// CF-B1-3: the pre-emptive TTL re-resolve (`PlayerViewModel.reResolveIfExpiring`), run on every return to the
    /// foreground. Skipped while PiP is live: the floating window is playing the very item a re-resolve replaces, so it
    /// would re-buffer or blank; the stream is unexpired by definition (it is still playing).
    var onWillEnterForeground: (() async -> Void)?
    /// The playlist's next and previous lecture (`PlayerHostView.trackSkip`), set on every host update pass. nil: no
    /// such lecture, so the lock-screen button is disabled (ruling 28: a dead button is worse than an absent one).
    var onNextTrack: (() -> Void)? { didSet { applyTrackCommands() } }
    var onPreviousTrack: (() -> Void)? { didSet { applyTrackCommands() } }

    private weak var player: AVPlayer?
    /// The lock screen and its commands are process-wide singletons, and the rail shell keeps covered tabs' players
    /// attached: they are the playing lecture's. Taken at attach, and back by a covered player that starts playing
    /// (`refreshNowPlaying`) -- else next/previous would run the newer screen's handler under the lecture playing.
    private static weak var lockScreenOwner: BackgroundPlaybackController?
    private var observers: [NSObjectProtocol] = []
    /// Task 4's Now Playing inputs. `args` stays nil until `PlayerHostView` hands it over, and a
    /// controller with no args publishes nothing rather than an empty lock screen.
    private var args: PlayerArgs?
    private var state: StreamState = .idle
    private var artwork: MPMediaItemArtwork?
    private var artworkURL: URL?
    private var artworkTask: Task<Void, Never>?
    private var wasPlayingBeforeInterruption = false

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
            // Not mid-interruption: activating would take the audio from the app that has it.
            if !sessionInterrupted { try session.setActive(true) }
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
        Self.observeSessionInterruptions()
        if Self.sessionInterrupted {
            interrupted = true
            // Stage 9 I-C9b: a host rebuilt mid-interruption (an auto-skip during a call) never saw the begin, so the
            // begin's playing read is seeded here; its player was built paused, so the intent is what says "playing".
            wasPlayingBeforeInterruption = player.timeControlStatus != .paused || resumeIntentPending?() == true
        }
        Self.configureAudioSession()
        applyBackgroundPolicy()
        let center = NotificationCenter.default
        observers = [
            center.addObserver(forName: UIApplication.willEnterForegroundNotification, object: nil, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    guard let self, let hook = self.onWillEnterForeground, !self.pictureInPictureActive else { return }
                    Task { await hook() }
                }
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

    /// Registered once, on the first attach (an inactive session gets no interruptions), for the life
    /// of the process.
    private static func observeSessionInterruptions() {
        guard sessionObserver == nil else { return }
        sessionObserver = NotificationCenter.default.addObserver(
            forName: AVAudioSession.interruptionNotification, object: nil, queue: .main) { note in
            let type = (note.userInfo?[AVAudioSessionInterruptionTypeKey] as? UInt)
                .flatMap(AVAudioSession.InterruptionType.init(rawValue:))
            MainActor.assumeIsolated {
                if type == .began { BackgroundPlaybackController.sessionInterrupted = true }
                if type == .ended { BackgroundPlaybackController.sessionInterrupted = false }
            }
        }
    }

    func detach() {
        // A covered tab's teardown leaves the playing one's lock screen and audio session alone: deactivating a session
        // with running audio stops it (`AVAudioSession.setActive(false)`).
        let owned = Self.lockScreenOwner.map { $0 === self } ?? true
        observers.forEach(NotificationCenter.default.removeObserver)
        observers = []
        player = nil
        // Same one-owner rule as the audio session: a dismantled player must not keep owning the
        // lock-screen transport. `removeRemoteCommands` also clears `nowPlayingInfo`.
        if owned { removeRemoteCommands() }
        artworkTask?.cancel()
        artworkTask = nil
        artwork = nil
        artworkURL = nil
        args = nil
        state = .idle
        // `.notifyOthersOnDeactivation` hands the session back politely so a paused music app
        // resumes instead of staying silent.
        if owned { try? AVAudioSession.sharedInstance().setActive(false, options: .notifyOthersOnDeactivation) }
    }

    private func applyBackgroundPolicy() {
        player?.audiovisualBackgroundPlaybackPolicy =
            AudioSessionPolicy.backgroundPolicy(backgroundPlay: backgroundPlay,
                                                pictureInPictureActive: pictureInPictureActive) == .continues
            ? .continuesIfPossible : .pauses
    }

    private func handleInterruption(type: AVAudioSession.InterruptionType?, optionsRaw: UInt) {
        switch type {
        case .began:
            interrupted = true
            wasPlayingBeforeInterruption = player?.timeControlStatus != .paused || resumeIntentPending?() == true
            handle(.interruptionBegan)
        case .ended:
            interrupted = false
            let shouldResume = AVAudioSession.InterruptionOptions(rawValue: optionsRaw).contains(.shouldResume)
            if shouldResume {
                Self.configureAudioSession()  // the session was deactivated by the interruption
            } else {
                // Fix r5 N4-1(b): iOS said do not resume, so a failure's pending resume intent dies
                // too. Reported only: the interruption already paused the player, and a play the
                // user made since must stand.
                onPolicyAction?(.pause)
            }
            handle(.interruptionEnded(shouldResume: shouldResume))
        default:
            break   // nil (undecodable payload) and any future case: do nothing
        }
    }

    private func handle(_ event: PlaybackLifecycleEvent) {
        let action = AudioSessionPolicy.decide(event, wasPlayingBeforeInterruption: wasPlayingBeforeInterruption)
        switch action {
        case .none: break
        case .pause:
            player?.pause()
            // Fix r5 N4-1(c): headphones out during a call; the call's end must not resume through
            // the speaker.
            if case .routeChanged = event { wasPlayingBeforeInterruption = false }
        case .resume: if autoplayHeld?() != true { player?.play() }
        }
        // Fix r4 N-2: the interruption's pause must leave the host's resume intent alone; the
        // interruption's end (`.resume`, counting that intent) plays recovery's replacement.
        guard event != .interruptionBegan else { return }
        onPolicyAction?(action)
    }

    // MARK: - Now Playing (Task 4)

    /// Called from `PlayerHostView` on every make/update pass -- which is also every item
    /// replacement (the audio-only toggle, a recovery `replaceCurrentItem`, a quality
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
        if let player, player.rate != 0, Self.lockScreenOwner !== self { installRemoteCommands() }
        publishNowPlaying()
    }

    private func publishNowPlaying() {
        guard Self.lockScreenOwner === self else { return }
        let elapsed = player.map(PlayerHostView.position(of:)) ?? 0
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
            self?.artwork = Self.artwork(for: image)
            self?.publishNowPlaying()
        }
    }

    /// B5 Task 4 finding (live auto-advance crashed with SIGILL in `dispatch_assert_queue`):
    /// MediaPlayer calls the request handler on ITS queue when it pushes the Now Playing dictionary
    /// (`-[MPMediaItemArtwork jpegDataWithSize:]` off `_onQueue_pushNowPlayingInfoAndRetry:`), and
    /// under this target's default MainActor isolation a plain closure literal is main-actor-bound,
    /// so the first real artwork (the first playlist advance -- the debug route carries no
    /// thumbnail) trapped. `@Sendable` is the whole fix; `UIImage` is Sendable.
    nonisolated static func artwork(for image: UIImage) -> MPMediaItemArtwork {
        MPMediaItemArtwork(boundsSize: image.size) { _ in image }
    }

    // MARK: - Remote command centre (Task 4)

    /// Every command republishes: the lock-screen scrubber is only honest if elapsed/rate go out
    /// the moment the transport moved, not on the next periodic tick.
    ///
    /// MIN-8 (final review): every handler below -- and this helper -- is `@MainActor` because the
    /// whole controller is, so each one ASSUMES `MPRemoteCommand` delivers its target on the main
    /// thread. That is what MediaPlayer does in practice and what every sample does, but Apple
    /// documents no queue for it, so the assumption is unproven rather than guaranteed; an
    /// off-main delivery would trap in the actor check, not corrupt state silently. Device
    /// checklist item 16 (lock screen + Bluetooth remote, driven hard) is what confirms it.
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
                center.changePlaybackPositionCommand, center.nextTrackCommand, center.previousTrackCommand]
    }

    private func installRemoteCommands() {
        Self.lockScreenOwner = self
        let center = MPRemoteCommandCenter.shared()
        // Re-attaching (a new player on the same controller) must not stack a second handler on a
        // command that already has one.
        Self.transportCommands.forEach { $0.removeTarget(nil) }
        _ = center.playCommand.addTarget { [weak self] _ in self?.act { $0.play() } ?? .commandFailed }
        _ = center.pauseCommand.addTarget { [weak self] _ in
            self?.act { $0.pause(); self?.onPolicyAction?(.pause) } ?? .commandFailed
        }
        _ = center.togglePlayPauseCommand.addTarget { [weak self] _ in
            // The headset button's pause is a pause too (Cubic): it drops a failure's resume intent and holds a skip.
            self?.act { player in
                if player.timeControlStatus == .paused { player.play() } else { player.pause(); self?.onPolicyAction?(.pause) }
            } ?? .commandFailed
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
        _ = center.nextTrackCommand.addTarget { [weak self] _ in self?.track(self?.onNextTrack) ?? .commandFailed }
        _ = center.previousTrackCommand.addTarget { [weak self] _ in self?.track(self?.onPreviousTrack) ?? .commandFailed }
        Self.transportCommands.forEach { $0.isEnabled = true }
        applyTrackCommands()
    }

    private func track(_ skip: (() -> Void)?) -> MPRemoteCommandHandlerStatus {
        guard player != nil, let skip else { return .noSuchContent }
        interrupted = false   // next/previous is the user's Play: it ends this player's hold (Cubic r3)
        skip()
        return .success
    }

    /// Only while this controller owns the transport: a detached or covered one leaves them alone. The lock screen has
    /// one button pair beside play/pause and shows ±10 s over next/previous while both are on, so a playlist trades
    /// ±10 s for the track buttons; the scrubber (`changePlaybackPositionCommand`) still seeks.
    private func applyTrackCommands() {
        guard player != nil, Self.lockScreenOwner === self else { return }
        let center = MPRemoteCommandCenter.shared()
        center.nextTrackCommand.isEnabled = onNextTrack != nil
        center.previousTrackCommand.isEnabled = onPreviousTrack != nil
        let playlist = onNextTrack != nil || onPreviousTrack != nil
        center.skipForwardCommand.isEnabled = !playlist
        center.skipBackwardCommand.isEnabled = !playlist
    }

    private func skip(by interval: TimeInterval) -> MPRemoteCommandHandlerStatus {
        act { $0.seek(to: CMTime(seconds: PlayerHostView.position(of: $0) + interval, preferredTimescale: 600)) }
    }

    private func removeRemoteCommands() {
        Self.lockScreenOwner = nil
        Self.transportCommands.forEach {
            $0.removeTarget(nil)
            $0.isEnabled = false
        }
        MPNowPlayingInfoCenter.default().nowPlayingInfo = nil
    }
}
