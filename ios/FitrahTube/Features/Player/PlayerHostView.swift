import AVFoundation
import AVKit
import Combine
import InnerTubeKit
import Network
import SwiftUI

/// `AVPlayerViewController` host for rung-1 (HLS) and rung-2 (progressive) playback (spec §10, plan
/// §6.5). Stock chrome gives scrubber/±10s/speed/subtitle-menu/AirPlay for free; captions overlay,
/// audio-language menu, recovery and the rest of the toolbar are later tasks in this plan.
struct PlayerHostView: UIViewControllerRepresentable {
    let state: StreamState
    /// The quality menu's current pick (`PlayerScreen`, `PlayerViewModel.selectedQuality`) --
    /// applied to every item this view builds or reuses, per task 4's "on pick and on each new
    /// prepare" contract.
    let quality: QualityOption
    /// Task 5's hand-off target: every (re)build/update publishes the live item to
    /// `model.currentItem` so `AudioLanguageMenu` (a SwiftUI overlay with no view-hierarchy access
    /// to the AVKit-managed item) can read/select its audible options.
    let model: PlayerViewModel

    func makeCoordinator() -> Coordinator { Coordinator() }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = true
        // ponytail: B1 never turns PiP on (App Review flags autoplay-into-PiP as a review risk);
        // B2 (plan §6.5 "Background audio"/"PiP") flips this to true.
        controller.allowsPictureInPicturePlayback = false
        controller.player = Self.player(for: state, replacing: nil)
        applyBackgroundController(to: controller, context: context)
        applyQuality(to: controller, context: context)
        applyAudioLanguageHandoff(to: controller)
        applyCaptionsHandoff(to: controller)
        applyRecoveryObservers(to: controller, context: context)
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = Self.player(for: state, replacing: controller.player)
        applyBackgroundController(to: controller, context: context)
        applyQuality(to: controller, context: context)
        applyAudioLanguageHandoff(to: controller)
        applyCaptionsHandoff(to: controller)
        applyRecoveryObservers(to: controller, context: context)
    }

    /// Task 7 (known family gap, fixed here): the hand-off slots below are the VM's only reference
    /// to AVKit-owned objects, and the VM outlives this host (`PlayerScreen` holds it in `@State`
    /// across every state change, including the non-playable ones that dismantle the host). Leaving
    /// a dismantled controller's item/player in `model.currentItem`/`currentPlayer` would leave
    /// `AudioLanguageMenu`/`CaptionOverlay` selecting into a torn-down item; clearing them here is
    /// safe precisely because a dismantled host owns nothing any more.
    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.stopObserving()
        // Same one-owner teardown as `stopObserving`: drops the lifecycle/interruption/route
        // observers and hands the audio session back with `.notifyOthersOnDeactivation`.
        coordinator.background.detach()
        // M6 (B1 final review): the coordinator can outlive this call (SwiftUI holds it until the
        // representable's own storage goes), and a live `NWPathMonitor` keeps a queue callback
        // firing for a host that owns nothing any more. `deinit`'s cancel stays as the backstop --
        // `NWPathMonitor.cancel()` is idempotent.
        coordinator.stopMonitoring()
        coordinator.model?.currentItem = nil
        coordinator.model?.currentPlayer = nil
        controller.player?.pause()
        controller.player = nil
    }

    /// Task 7 glue: (re)attach the recovery observers to whatever item is live now. The coordinator
    /// no-ops when it's already watching this exact player+item pair, so this is safe to call on
    /// every `updateUIViewController` pass; a real item swap tears the old observers down first.
    private func applyRecoveryObservers(to controller: AVPlayerViewController, context: Context) {
        guard let player = controller.player, let item = player.currentItem else {
            context.coordinator.stopObserving()
            return
        }
        context.coordinator.observe(item: item, player: player, model: model, isLive: Self.isLive(state))
    }

    /// CF-B1-1: the audio session, `UIBackgroundModes: audio`, interruptions and route changes are
    /// `BackgroundPlaybackController`'s -- this is only the hand-off of whichever `AVPlayer` is
    /// live now. `attach` no-ops on an unchanged player, so this is safe on every update pass.
    private func applyBackgroundController(to controller: AVPlayerViewController, context: Context) {
        guard let player = controller.player else { return }
        context.coordinator.background.attach(player: player)
    }

    private static func isLive(_ state: StreamState) -> Bool {
        guard let resolved = resolvedStream(for: state), case .hls(_, let isLive, _, _) = resolved.stream else {
            return false
        }
        return isLive
    }

    /// Fix-round-1 F1: `controller.view.bounds.size` is POINTS; `QualityOption.apply`'s
    /// `layerSize` must be PIXELS (`preferredMaximumResolution` is a pixel dimension). The
    /// window's own screen scale is authoritative when the controller is on-screen; before that
    /// (first `makeUIViewController` pass, view not yet in a window) `traitCollection.displayScale`
    /// is the best available estimate, with `2` as a last-resort floor -- never `0`, which would
    /// collapse the cap to a zero-pixel size.
    private func applyQuality(to controller: AVPlayerViewController, context: Context) {
        guard let item = controller.player?.currentItem else { return }
        let scale = controller.view.window?.screen.scale ?? controller.traitCollection.displayScale
        let layerSize = QualityOption.pixelSize(points: controller.view.bounds.size, scale: scale > 0 ? scale : 2)
        quality.apply(to: item, layerSize: layerSize, network: context.coordinator.path)
    }

    /// `!==` guard: writing the same reference every `updateUIViewController` pass (most of
    /// them -- state changes for reasons that have nothing to do with the item, e.g. a quality
    /// pick) is harmless either way, but skipping the redundant write avoids churning
    /// `AudioLanguageMenu`'s `.task(id: model.currentItem)`.
    private func applyAudioLanguageHandoff(to controller: AVPlayerViewController) {
        guard let item = controller.player?.currentItem, item !== model.currentItem else { return }
        model.currentItem = item
    }

    /// Same `!==` guard as `applyAudioLanguageHandoff`, one level up -- `CaptionOverlay`'s
    /// `addPeriodicTimeObserver` needs the `AVPlayer` itself.
    private func applyCaptionsHandoff(to controller: AVPlayerViewController) {
        guard let player = controller.player, player !== model.currentPlayer else { return }
        model.currentPlayer = player
    }

    /// Holds the one `NWPathMonitor` this host needs for `QualityOption.apply`'s cellular/Low-Data
    /// clamp -- same start/store/cancel shape as `NetworkMonitor` (`App/NetworkMonitor.swift`),
    /// scoped to this view instead of shared, since nothing else in B1 needs live path data yet --
    /// plus (Task 7) the per-item recovery observers, whose lifetime is the same one-owner
    /// start/stop shape `CaptionOverlay.TimeObserver` uses for its periodic observer.
    ///
    /// Untested glue by design: every input here is a real decoding `AVPlayer`'s behaviour (KVO
    /// status, the failed-to-play-to-end notification, `loadedTimeRanges` growth), which the unit
    /// target can't produce. All the *decisions* live in `PlaybackRecovery`, which is tested
    /// exhaustively.
    @MainActor final class Coordinator {
        private(set) var path: NWPath
        private let monitor = NWPathMonitor()

        /// The audio session / background-playback owner (CF-B1-1). Lives here rather than in the
        /// representable struct because it must survive every `updateUIViewController` pass.
        let background = BackgroundPlaybackController(backgroundPlay: true)   // Task 3 feeds the real setting

        /// Strong, and deliberately so: `dismantleUIViewController` is `static` and gets only the
        /// controller + this coordinator, so this is the sole route back to the VM's hand-off slots
        /// at teardown. No cycle -- the VM holds no reference to the host or coordinator.
        private(set) var model: PlayerViewModel?

        private weak var observedItem: AVPlayerItem?
        private weak var observedPlayer: AVPlayer?
        private var statusCancellable: AnyCancellable?
        private var failedToEndObserver: NSObjectProtocol?
        private var timeObserverToken: Any?
        private var isLive = false
        /// All the watchdog's state and every decision it makes (fix round 1, C1).
        private var watchdog = StallWatchdog()

        init() {
            path = monitor.currentPath
            monitor.pathUpdateHandler = { [weak self] newPath in
                MainActor.assumeIsolated {
                    self?.path = newPath
                }
            }
            monitor.start(queue: .main)
        }

        func observe(item: AVPlayerItem, player: AVPlayer, model: PlayerViewModel, isLive: Bool) {
            self.model = model
            guard item !== observedItem || player !== observedPlayer else { return }
            stopObserving()
            observedItem = item
            observedPlayer = player
            self.isLive = isLive
            // I4: seeded from the live position, not 0 -- a replacement item is seeked back to the
            // outgoing item's time, which a 0 seed would misread as a full item's worth of progress.
            watchdog = StallWatchdog(playbackTime: player.currentTime().seconds)

            // I2: `AVPlayerItem.status` KVO carries NO queue guarantee, so the old
            // `observe { MainActor.assumeIsolated { … } }` was a hard trap waiting for an off-main
            // delivery. `receive(on:)` makes the main-thread hop explicit instead of assuming it.
            statusCancellable = item.publisher(for: \.status, options: [.new])
                .receive(on: DispatchQueue.main)
                .sink { [weak self] status in
                    MainActor.assumeIsolated {
                        guard let self, item === self.observedItem else { return }
                        switch status {
                        case .readyToPlay: self.watchdog.armed = true
                        // Unarmed == this rung never produced a first frame (spec §10 -> next rung);
                        // armed == it played and then died, which is the 403-class incident.
                        case .failed: self.fire(self.watchdog.armed ? .playbackError : .failedBeforeFirstFrame)
                        default: break
                        }
                    }
                }

            failedToEndObserver = NotificationCenter.default.addObserver(
                forName: .AVPlayerItemFailedToPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                MainActor.assumeIsolated {
                    self?.fire(.playbackError)
                }
            }

            timeObserverToken = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] time in
                MainActor.assumeIsolated {
                    self?.sample(time: time.seconds)
                }
            }
        }

        /// M6: `monitor` is private, so teardown goes through here (see
        /// `dismantleUIViewController`). Separate from `stopObserving` on purpose -- the per-item
        /// observers are torn down and re-attached on every item swap; the path monitor is not.
        func stopMonitoring() { monitor.cancel() }

        func stopObserving() {
            statusCancellable = nil
            if let failedToEndObserver { NotificationCenter.default.removeObserver(failedToEndObserver) }
            failedToEndObserver = nil
            if let timeObserverToken, let observedPlayer { observedPlayer.removeTimeObserver(timeObserverToken) }
            timeObserverToken = nil
            observedItem = nil
            observedPlayer = nil
        }

        /// Pure measurement, no decisions: read the player, hand the sample to `StallWatchdog`, act on
        /// its answer. `waitingToPlayAtSpecifiedRate` is AVFoundation's own "wants to play, can't" --
        /// false while paused, while playing, and while fully buffered, which is exactly the three
        /// healthy cases the pre-fix version false-fired on.
        private func sample(time: TimeInterval) {
            guard let item = observedItem, let player = observedPlayer else { return }
            let buffered = item.loadedTimeRanges.map { CMTimeRangeGetEnd($0.timeRangeValue).seconds }.max() ?? 0
            let tick = watchdog.tick(playbackTime: time, bufferedEnd: buffered,
                                     isStalled: player.timeControlStatus == .waitingToPlayAtSpecifiedRate,
                                     isLive: isLive, now: Date())
            if tick.progressed { model?.recordPlaybackProgress() }
            if tick.fire { fire(.stall) }
        }

        private func fire(_ event: RecoveryEvent) {
            guard let model else { return }
            Task { await model.handleRecoveryEvent(event) }
        }

        /// Only the path monitor: the observers are torn down in `dismantleUIViewController` (which
        /// SwiftUI always calls) via `stopObserving`, and `deinit` is nonisolated so it can't touch
        /// them anyway.
        deinit { monitor.cancel() }
    }

    // MARK: - Builder (static so `PlayerHostTests` can call it directly, no view hierarchy needed)

    /// Builds the `AVPlayer` for one `StreamState`, reusing `replacing` when it's already playing the
    /// resolved URL (an unrelated state change -- e.g. a later task's recovery counters -- must not
    /// restart playback). Ruling 32 (session-only resume): `replacing`'s `currentTime()` carries into
    /// the replacement item; nothing here persists past this `AVPlayer`'s own lifetime.
    static func player(for state: StreamState, replacing existing: AVPlayer?) -> AVPlayer? {
        guard let resolved = resolvedStream(for: state), let url = streamURL(resolved.stream) else {
            existing?.pause()
            return nil
        }
        // I7 (B1 final review): reuse the live item only while it can still play. An
        // `AVPlayerItem` that reached `.failed` never recovers, so handing it back on a same-URL
        // re-resolve (exactly what manual Retry and the recovery ladder do) froze the player on a
        // retry that looked like it had done something. A failed item falls through to the replace
        // path below, which builds a fresh item on the SAME `AVPlayer` (position carried over).
        if let existing, (existing.currentItem?.asset as? AVURLAsset)?.url == url,
           existing.currentItem?.status != .failed {
            return existing
        }
        let item = AVPlayerItem(asset: asset(url: url, userAgent: resolved.userAgent))
        guard let existing else {
            let player = AVPlayer(playerItem: item)
            player.play()
            return player
        }
        let resumeTime = existing.currentTime()
        // I5 (player.md §3.2: a re-resolve saves position AND playWhenReady): resuming a stream the
        // user had deliberately paused is a real behaviour bug -- recovery replaces the item under a
        // paused player just as readily as under a playing one.
        let wasPlaying = existing.timeControlStatus != .paused
        existing.replaceCurrentItem(with: item)
        if resumeTime.isValid, resumeTime.seconds.isFinite, resumeTime.seconds > 0 {
            existing.seek(to: resumeTime)
        }
        if wasPlaying { existing.play() }
        return existing
    }

    /// The mandatory `AVURLAsset` for a resolved stream (plan constraint: the resolved User-Agent
    /// MUST be set on the asset). Root-cause finding: the plan's own snippet
    /// (`AVURLAssetHTTPHeaderFieldsKey: ["User-Agent": ...]`) doesn't compile against this
    /// toolchain's SDK (Xcode 26.3 / iOS 26.2) -- that generic header-dict key has been removed
    /// (confirmed absent from `AVAsset.h`), replaced by a dedicated `AVURLAssetHTTPUserAgentKey`
    /// (iOS 16+, well within this app's 18.0 floor) taking the user-agent string directly, not
    /// nested in a header dictionary. `AVURLAsset` doesn't expose its `options` dictionary back out
    /// once built, so `assetOptions(userAgent:)` below -- not this asset -- is what `PlayerHostTests`
    /// actually asserts the value against.
    static func asset(url: URL, userAgent: String) -> AVURLAsset {
        AVURLAsset(url: url, options: assetOptions(userAgent: userAgent))
    }

    static func assetOptions(userAgent: String) -> [String: Any] {
        [AVURLAssetHTTPUserAgentKey: userAgent]
    }

    private static func resolvedStream(for state: StreamState) -> Resolved? {
        switch state {
        case .ready(let resolved), .rung2Progressive(let resolved): return resolved
        default: return nil
        }
    }

    private static func streamURL(_ stream: ResolvedStream) -> URL? {
        switch stream {
        case .hls(let url, _, _, _): return url
        case .progressive(let url, _): return url
        case .embed, .openInYouTube: return nil // B3
        }
    }
}
