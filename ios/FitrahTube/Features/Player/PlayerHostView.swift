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
    /// `PlayerViewModel.audioOnly` (ruling 34): swaps the built item's URL to the resolved stream's
    /// itag 140 rendition. A flip yields a DIFFERENT url, so `player(for:replacing:audioOnly:)`'s
    /// existing identity check already gives Android's behaviour -- an audio-mode change counts as a
    /// quality switch, position preserved (`PlayerFragment.kt:2841-2842`).
    let audioOnly: Bool
    /// Task 5's hand-off target: every (re)build/update publishes the live item to
    /// `model.currentItem` so `AudioLanguageMenu` (a SwiftUI overlay with no view-hierarchy access
    /// to the AVKit-managed item) can read/select its audible options.
    let model: PlayerViewModel
    /// B4: which surface this host is. Defaulted so `PlayerScreen`'s call site is untouched.
    var presentation: PlayerPresentation = .standard
    /// B5 Task 3: whether `PlayerScreen` is laying the host out fullscreen. The coordinator reads its
    /// copy of this in the double-tap handler (the centre zone only acts in fullscreen); it never
    /// re-derives size classes itself. Defaulted so `ShortsScreen`'s call site is untouched.
    var isFullscreen = false

    /// B4: the ONE read of the Background play setting this host makes. `.shorts` forces it off
    /// (Android parity, brief 9.5), and every consumer -- the coordinator's initial policy, auto-PiP,
    /// the live `background.backgroundPlay` -- goes through here. Miss one and Shorts get a floating
    /// PiP window from a home-swipe.
    private var effectiveBackgroundPlay: Bool { presentation.allowsBackgroundPlayback && model.backgroundPlay }

    func makeCoordinator() -> Coordinator { Coordinator(backgroundPlay: effectiveBackgroundPlay) }

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = presentation.showsPlaybackControls
        controller.videoGravity = Self.videoGravity(zoomed: model.videoZoomed, presentation: presentation)
        controller.delegate = context.coordinator
        controller.player = Self.player(for: state, replacing: nil, audioOnly: audioOnly,
                                        continuesCurrentVideo: continuesCurrentVideo(context),
                                        resumeFallback: model.currentTime)
        context.coordinator.lastVideoId = model.hostVideoId
        Self.configurePictureInPicture(controller, backgroundPlay: effectiveBackgroundPlay)
        applyBackgroundController(to: controller, context: context)
        applyNowPlaying(context: context)
        applyQuality(to: controller, context: context)
        applyAudioLanguageHandoff(to: controller)
        applyCaptionsHandoff(to: controller)
        applyRecoveryObservers(to: controller, context: context)
        // Reconciliation note 1 / spec §10 "implemented as an overlay on the content view".
        // The delegate's `shouldRecognizeSimultaneouslyWith` returning true is what keeps plan §6.5's
        // rule true: AVKit's single tap still toggles its controls, because our recognizer neither
        // requires its failure nor blocks it. A double tap therefore ALSO flashes the controls once;
        // that is accepted (Android does the same) and is NOT worth reaching into
        // `controller.view.gestureRecognizers` to suppress.
        // `controller.view`, not `contentOverlayView` (the plan's sanctioned fallback): on this SDK
        // (Xcode 26.3 / iOS 26.3 sim) the overlay sits BELOW AVKit's controls layer, which swallows
        // every touch, so a recognizer on it never fires -- verified B5 Task 3 Step 4 by XCUITest
        // (controls toggled, no seek flash, no zoom). Same delegate, same rule; nothing else changes.
        let doubleTap = UITapGestureRecognizer(target: context.coordinator,
                                               action: #selector(Coordinator.handleDoubleTap(_:)))
        doubleTap.numberOfTapsRequired = 2
        doubleTap.delegate = context.coordinator
        controller.view.addGestureRecognizer(doubleTap)
        context.coordinator.doubleTap = doubleTap
        context.coordinator.isFullscreen = isFullscreen
        return controller
    }

    /// B5 Task 3: the centre-double-tap zoom OVERRIDES the presentation's gravity; it does not
    /// replace the property (reconciliation note 4), so `.shorts` keeps its fill when not zoomed.
    static func videoGravity(zoomed: Bool, presentation: PlayerPresentation) -> AVLayerVideoGravity {
        zoomed ? .resizeAspectFill : presentation.videoGravity
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = Self.player(for: state, replacing: controller.player, audioOnly: audioOnly,
                                        continuesCurrentVideo: continuesCurrentVideo(context),
                                        resumeFallback: model.currentTime)
        context.coordinator.lastVideoId = model.hostVideoId
        controller.showsPlaybackControls = presentation.showsPlaybackControls
        // M6 (B4 final review): write only on change -- an unconditional write would churn AVKit's
        // layer every pass. B5: the user's zoom override is folded into the computed value.
        let gravity = Self.videoGravity(zoomed: model.videoZoomed, presentation: presentation)
        if controller.videoGravity != gravity { controller.videoGravity = gravity }
        // AVKit's own fullscreen (the iPad path) has its own double-tap gravity toggle.
        context.coordinator.doubleTap?.isEnabled = !model.avKitFullscreen
        context.coordinator.isFullscreen = isFullscreen
        // Live on every pass, exactly like `background.backgroundPlay` below: a Background-play flip
        // made in Settings while the player is open must change auto-PiP now, not on the next launch.
        Self.configurePictureInPicture(controller, backgroundPlay: effectiveBackgroundPlay)
        applyBackgroundController(to: controller, context: context)
        applyNowPlaying(context: context)
        applyQuality(to: controller, context: context)
        applyAudioLanguageHandoff(to: controller)
        applyCaptionsHandoff(to: controller)
        applyRecoveryObservers(to: controller, context: context)
    }

    /// B5: whether this pass is still the same video the coordinator last built for. False on an
    /// auto-advance or an Up Next tap, so the next video starts at 0 instead of the previous clock.
    private func continuesCurrentVideo(_ context: Context) -> Bool {
        Self.shouldPreservePosition(previous: context.coordinator.lastVideoId, next: model.hostVideoId)
    }

    /// Pure half of the above (`PlayerHostTests`). `nil` is the first build.
    static func shouldPreservePosition(previous: String?, next: String) -> Bool {
        previous == nil || previous == next
    }

    /// Task 7 (known family gap, fixed here): the hand-off slots below are the VM's only reference
    /// to AVKit-owned objects, and the VM outlives this host (`PlayerScreen` holds it in `@State`
    /// across every state change, including the non-playable ones that dismantle the host). Leaving
    /// a dismantled controller's item/player in `model.currentItem`/`currentPlayer` would leave
    /// `AudioLanguageMenu`/`CaptionOverlay` selecting into a torn-down item; clearing them here is
    /// safe precisely because a dismantled host owns nothing any more.
    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: Coordinator) {
        coordinator.stopObserving()
        if let doubleTap = coordinator.doubleTap { doubleTap.view?.removeGestureRecognizer(doubleTap) }
        coordinator.doubleTap = nil
        coordinator.seekFeedbackTask?.cancel()
        coordinator.model?.seekFeedback = nil    // the cancelled task above would have cleared it
        // M6 (B1 final review): the coordinator can outlive this call (SwiftUI holds it until the
        // representable's own storage goes), and a live `NWPathMonitor` keeps a queue callback
        // firing for a host that owns nothing any more. `deinit`'s cancel stays as the backstop --
        // `NWPathMonitor.cancel()` is idempotent. These three run in EVERY case, PiP or not: they
        // only drop references to a host that is gone either way.
        coordinator.stopMonitoring()
        coordinator.model?.currentItem = nil
        coordinator.model?.currentPlayer = nil
        // Task 5: the audio-session detach and the player release are the two things a live PiP
        // window still needs, so they go through the policy instead of running unconditionally.
        coordinator.finishTeardown(of: controller, hostDismantled: true)
    }

    /// Task 7 glue: (re)attach the recovery observers to whatever item is live now. The coordinator
    /// no-ops when it's already watching this exact player+item pair, so this is safe to call on
    /// every `updateUIViewController` pass; a real item swap tears the old observers down first.
    private func applyRecoveryObservers(to controller: AVPlayerViewController, context: Context) {
        guard let player = controller.player, let item = player.currentItem else {
            context.coordinator.stopObserving()
            return
        }
        context.coordinator.observe(item: item, player: player, model: model, isLive: Self.isLive(state),
                                    playToEnd: presentation.actionOnPlayToEnd)
    }

    /// Task 4: republish the lock-screen surface on every pass -- which is every item replacement
    /// too (the audio-only swap in and out, a recovery `replaceCurrentItem`, a quality pick all
    /// arrive through `updateUIViewController`), not just the first prepare. A state with nothing
    /// playable retracts the surface instead of leaving the previous video's metadata standing.
    private func applyNowPlaying(context: Context) {
        context.coordinator.background.update(args: model.args, state: state)
    }

    /// CF-B1-1: the audio session, `UIBackgroundModes: audio`, interruptions and route changes are
    /// `BackgroundPlaybackController`'s -- this is only the hand-off of whichever `AVPlayer` is
    /// live now. `attach` no-ops on an unchanged player, so this is safe on every update pass.
    private func applyBackgroundController(to controller: AVPlayerViewController, context: Context) {
        guard let player = controller.player else { return }
        let background = context.coordinator.background
        // Read live on every pass (ruling 34): a Background-play flip made in Settings while the
        // player is open must take effect on the next background transition, not on the next launch.
        background.backgroundPlay = effectiveBackgroundPlay
        background.userAudioOnly = model.audioOnly
        background.audioOnlyAvailable = PlayerViewModel.audioOnlyAvailable(for: state)
        background.onPolicyAction = Self.policyHandler(model: model, player: player,
                                                       coordinator: context.coordinator)
        // CF-B1-3: awaited by the controller BEFORE the foreground policy runs (and skipped while
        // PiP is live), so the restore above builds its item from the freshly resolved stream.
        background.onWillEnterForeground = { [weak model] in
            await model?.reResolveIfExpiring()
        }
        background.attach(player: player)
    }

    /// `BackgroundPlaybackController.onPolicyAction`, built here rather than inline above so the
    /// tests drive the REAL production closure. Fix round 2: the inline version never passed the
    /// coordinator, so `applyPolicyAction`'s `coordinator:` defaulted to nil in the app and MIN-4's
    /// observer re-arm ran only in the tests that passed one by hand -- every real swap left the
    /// recovery observers on the outgoing item.
    ///
    /// Fix round 1, C2: the handler must never swap against a stale `state`. It is REASSIGNED on
    /// every update pass, and it reads `model.state` at ACTION time rather than capturing this
    /// pass's copy -- Task 6's pre-emptive re-resolve settles between the foreground notification
    /// and the `.restoreVideo` it produces, and SwiftUI has not re-run the host by then, so a
    /// captured copy would restore the very URL the refresh just replaced.
    ///
    /// Every capture is weak: the controller is owned by the coordinator, so a strong coordinator
    /// here would close the cycle coordinator -> controller -> handler -> coordinator.
    static func policyHandler(model: PlayerViewModel?, player: AVPlayer?,
                              coordinator: Coordinator?) -> (PlaybackPolicyAction) -> Void {
        { [weak model, weak player, weak coordinator] action in
            guard let model else { return }
            Self.applyPolicyAction(action, state: model.state, player: player, model: model,
                                   coordinator: coordinator)
        }
    }

    /// The `.swapToAudioOnly` / `.restoreVideo` half of the background policy, split out of the
    /// closure above so `BackgroundPlaybackControllerTests` can drive the real wiring end to end
    /// (T2-1) instead of only the pure decision table.
    ///
    /// ponytail: an automatic background swap costs one re-buffer going in and one coming out (both
    /// are local URL swaps, no network). Accepted: spec §10 asks for the itag 140 swap on background
    /// so the phone stops pulling video segments off-screen. Skipped: the "or when backgrounded on
    /// cellular" variant from plan §6.5 -- the setting already carries user intent.
    static func applyPolicyAction(_ action: PlaybackPolicyAction, state: StreamState,
                                  player: AVPlayer?, model: PlayerViewModel?,
                                  coordinator: Coordinator? = nil) {
        switch action {
        case .swapToAudioOnly, .restoreVideoNow:
            // Fix round 1, C2: `model.audioOnly` alone only SCHEDULES a SwiftUI update, and there
            // is no guarantee `updateUIViewController` runs before the app suspends -- the phone
            // could keep pulling video segments for the whole background stint, which is the one
            // thing this swap exists to stop. So the BACKGROUND swap happens here, synchronously,
            // on the live player. `player(for:replacing:audioOnly:)` reuses that same `AVPlayer`
            // (position and playWhenReady carried across by its own replace path); it never builds
            // a second one.
            //
            // Fix round 2: `.restoreVideoNow` is the mirror image and shares this path for the same
            // reason -- it is the auto-PiP undo, emitted mid-home-swipe, and a window opened over
            // the itag 140 item is black until the user comes back. (The ORDINARY foreground
            // restore is `.restoreVideo` below, which stays deferred.)
            let audioOnly = action == .swapToAudioOnly
            if let player {
                _ = Self.player(for: state, replacing: player, audioOnly: audioOnly)
                // MIN-4 (final review): that replace built a NEW `AVPlayerItem`, and every recovery
                // observer (status KVO, failed-to-play-to-end, the periodic stall sampler) is
                // per-item -- left on the outgoing one, an audio-only stream that dies in the
                // background would never reach the recovery ladder. Same hook
                // `updateUIViewController` uses; it no-ops if the pair is already the observed one.
                // B5 Task 4: re-arm with the action the outgoing item HAD (`.advance` on the main
                // player) -- `.none` here silently lost background auto-advance, the very thing
                // reconciliation note 2 promises. `.shorts` never reaches this path anyway
                // (`AudioSessionPolicy.decide(.enteredBackground, …)` needs backgroundPlay true).
                if let item = player.currentItem, let coordinator {
                    coordinator.observe(item: item, player: player, model: model, isLive: Self.isLive(state),
                                        playToEnd: coordinator.playToEnd)
                }
            }
            model?.audioOnly = audioOnly
        case .restoreVideo:
            // IMP-1 (final review): NO synchronous replace on the way back. The foreground
            // transition runs this on a 2 s deadline, so a TTL refresh can still be in flight --
            // swapping here against the state of the moment and then letting the update pass swap
            // again against the refreshed one is two `replaceCurrentItem` calls, and the second
            // restarts playback from 0. One replace, owned by the foreground update pass, against
            // whichever `Resolved` is current when it runs. Nothing is being pulled in the
            // meantime that the user did not ask for: the player is still on the audio rendition.
            model?.audioOnly = false
        default:
            return
        }
    }

    /// Ruling 43: platform-standard PiP via AVKit. `canStartPictureInPictureAutomaticallyFromInline`
    /// is NOT programmatic PiP -- the user backgrounding the app is the trigger and AVKit performs
    /// the transition; what spec §10 and plan §6.5 forbid (and App Review rejects) is calling
    /// `startPictureInPicture()` from code, which this app never does (grep: no call site exists).
    /// Auto-start is gated on the Background play setting so a user who turned background playback
    /// OFF cannot get a floating video window by backgrounding (ruling 34); the stock PiP button in
    /// the transport stays available either way.
    ///
    /// B3 task 4 (the answer): the embed rung has no `AVPlayer` and no `AVPlayerViewController` at
    /// all (`streamURL` returns nil for `.embed`, so this host is never mounted on that branch), and
    /// `EmbedRungView` sets `allowsPictureInPictureMediaPlayback = false` on its `WKWebView`. There
    /// is therefore no PiP affordance on rung 3 whatsoever -- which is what spec §6.6's rung-3 row
    /// asks for and what YouTube API Services policy III.I.9 requires of embed content.
    static func configurePictureInPicture(_ controller: AVPlayerViewController, backgroundPlay: Bool) {
        controller.allowsPictureInPicturePlayback = true
        controller.canStartPictureInPictureAutomaticallyFromInline = backgroundPlay
    }

    private static func isLive(_ state: StreamState) -> Bool {
        guard let resolved = state.resolved, case .hls(_, let isLive, _, _) = resolved.stream else {
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
    @MainActor final class Coordinator: NSObject, AVPlayerViewControllerDelegate, UIGestureRecognizerDelegate {
        private(set) var path: NWPath
        /// B5 Task 3: the double-tap overlay recognizer (`makeUIViewController`) and the screen's
        /// fullscreen flag, written on every update pass.
        var doubleTap: UITapGestureRecognizer?
        var isFullscreen = false
        var seekFeedbackTask: Task<Void, Never>?
        private let monitor = NWPathMonitor()

        /// The audio session / background-playback owner (CF-B1-1). Lives here rather than in the
        /// representable struct because it must survive every `updateUIViewController` pass.
        let background: BackgroundPlaybackController

        /// Strong, and deliberately so: `dismantleUIViewController` is `static` and gets only the
        /// controller + this coordinator, so this is the sole route back to the VM's hand-off slots
        /// at teardown. No cycle -- the VM holds no reference to the host or coordinator.
        private(set) var model: PlayerViewModel?

        private(set) weak var observedItem: AVPlayerItem?
        private weak var observedPlayer: AVPlayer?
        private var statusCancellable: AnyCancellable?
        private var failedToEndObserver: NSObjectProtocol?
        /// B4: the repeat-one loop (`.restart`); B5: the auto-advance (`.advance`). Per item.
        private var endObserver: NSObjectProtocol?
        /// The action the current item was armed with, so the background audio-only swap re-arms
        /// the replacement item with the SAME one (B5 Task 4: `.none` there lost background
        /// auto-advance).
        private(set) var playToEnd: PlayToEndAction = .none
        /// B5: the video the last build/update pass was for (`shouldPreservePosition`).
        var lastVideoId: String?
        #if DEBUG
        static var debugSeekNearEndConsumed = false
        #endif
        private var timeObserverToken: Any?
        private var isLive = false
        /// All the watchdog's state and every decision it makes (fix round 1, C1).
        private var watchdog = StallWatchdog()

        /// Set when `dismantleUIViewController` ran while a PiP window was still playing, so
        /// `…DidStopPictureInPicture` knows it owes the deferred detach + player release.
        private var dismantledWhilePiP = false

        init(backgroundPlay: Bool) {
            background = BackgroundPlaybackController(backgroundPlay: backgroundPlay)
            path = monitor.currentPath
            super.init()
            monitor.pathUpdateHandler = { [weak self] newPath in
                MainActor.assumeIsolated {
                    self?.path = newPath
                }
            }
            monitor.start(queue: .main)
        }

        // MARK: - Double-tap overlay (B5 Task 3, spec §10)

        @objc func handleDoubleTap(_ recognizer: UITapGestureRecognizer) {
            guard let view = recognizer.view, let player = observedPlayer, let item = observedItem,
                  let model else { return }
            let zone = PlayerGestures.zone(x: recognizer.location(in: view).x, width: view.bounds.width)
            switch zone {
            case .centre:
                // Android's "no dead zone" (`PlayerGestureDetector.kt:58-62`): outside fullscreen a
                // centre double tap is not consumed by this overlay.
                guard isFullscreen else { return }
                model.videoZoomed.toggle()
                model.banner = BannerMessage(text: String(localized: model.videoZoomed
                    ? "player_resize_mode_zoom" : "player_resize_mode_fit"))
            case .back, .forward:
                guard let target = PlayerGestures.seek(from: player.currentTime().seconds, zone: zone,
                                                       duration: item.duration.seconds, step: 10) else { return }
                player.seek(to: CMTime(seconds: target, preferredTimescale: 600))
                model.currentTime = target
                model.seekFeedback = zone
                seekFeedbackTask?.cancel()
                seekFeedbackTask = Task { [weak model] in
                    try? await Task.sleep(for: .milliseconds(600))
                    guard !Task.isCancelled else { return }
                    model?.seekFeedback = nil
                }
            }
        }

        /// Nonisolated by protocol; touches nothing actor-confined -- the whole point is to say
        /// "yes" so AVKit's own tap recognizers keep working alongside ours.
        nonisolated func gestureRecognizer(_ gestureRecognizer: UIGestureRecognizer,
                                           shouldRecognizeSimultaneouslyWith other: UIGestureRecognizer) -> Bool {
            true
        }

        // MARK: - AVKit's own fullscreen (ruling 42's iPad path)

        func playerViewController(_ playerViewController: AVPlayerViewController,
                                  willBeginFullScreenPresentationWithAnimationCoordinator coordinator: any UIViewControllerTransitionCoordinator) {
            model?.avKitFullscreen = true      // also disables our recognizer (`updateUIViewController`)
        }

        func playerViewController(_ playerViewController: AVPlayerViewController,
                                  willEndFullScreenPresentationWithAnimationCoordinator coordinator: any UIViewControllerTransitionCoordinator) {
            model?.avKitFullscreen = false
        }

        // MARK: - AVPlayerViewControllerDelegate (Task 5)

        /// WILL, not DID: `AudioSessionPolicy.decide(.enteredBackground, …)` reads
        /// `pictureInPictureActive`, and the ordering of this callback against
        /// `didEnterBackgroundNotification` is not guaranteed -- flipping it on the DID callback
        /// would let an auto-PiP background transition be read as a plain background and swap the
        /// item to audio-only, blanking the PiP window it just opened.
        func playerViewControllerWillStartPictureInPicture(_ controller: AVPlayerViewController) {
            background.pictureInPictureActive = true
            // IMP-2 (final review): flipping the flag only stops a FUTURE swap. When
            // `didEnterBackgroundNotification` won the race, the item is ALREADY the itag 140 audio
            // rendition and the window AVKit is opening would have no video track to show.
            background.undoAutoSwapIfAny()
        }

        func playerViewControllerDidStopPictureInPicture(_ controller: AVPlayerViewController) {
            background.pictureInPictureActive = false
            finishTeardown(of: controller, hostDismantled: dismantledWhilePiP)
        }

        /// The player screen is still mounted behind the PiP window, so there is nothing to
        /// restore -- answer `true` immediately or AVKit waits on a completion that never comes.
        func playerViewController(_ controller: AVPlayerViewController,
                                  restoreUserInterfaceForPictureInPictureStopWithCompletionHandler
                                  completionHandler: @escaping (Bool) -> Void) {
            completionHandler(true)
        }

        /// The one place the two teardown steps a live PiP window still needs are decided
        /// (`PiPDismantlePolicy`), for both entry points: SwiftUI's dismantle and AVKit's
        /// "PiP stopped". Idempotent -- a second call with nothing owed does nothing.
        ///
        /// ponytail: PiP survives BACKGROUNDING, not a back-navigation out of the player -- SwiftUI
        /// pops `PlayerScreen`, which releases the `@State PlayerViewModel`, and once AVKit lets the
        /// controller go the deferred teardown below never runs. Making PiP outlive the route needs
        /// an app-scoped player holder; deferred.
        func finishTeardown(of controller: AVPlayerViewController, hostDismantled: Bool) {
            let actions = PiPDismantlePolicy.teardown(
                pictureInPictureActive: background.pictureInPictureActive, hostDismantled: hostDismantled)
            dismantledWhilePiP = actions.deferUntilPiPStops
            // Same one-owner teardown as `stopObserving`: drops the lifecycle/interruption/route
            // observers, the remote commands and the Now Playing surface, and hands the audio
            // session back with `.notifyOthersOnDeactivation`. Skipped while PiP is live -- the
            // floating window needs the session and the lock screen exactly as much as the app did.
            if actions.detachBackground { background.detach() }
            if actions.releasePlayer {
                controller.player?.pause()
                controller.player = nil
            }
        }

        func observe(item: AVPlayerItem, player: AVPlayer, model: PlayerViewModel?, isLive: Bool,
                     playToEnd: PlayToEndAction) {
            // Optional so the background swap's re-arm (MIN-4) can go through this one path without
            // carrying a VM it has no reason to know about; a nil never CLEARS the live one.
            if let model { self.model = model }
            guard item !== observedItem || player !== observedPlayer else { return }
            stopObserving()
            observedItem = item
            observedPlayer = player
            self.isLive = isLive
            self.playToEnd = playToEnd
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
                        case .readyToPlay:
                            self.watchdog.armed = true
                            #if DEBUG
                            // B5 Task 4 live rig (`-fitrah-player-seek-near-end`, same shape as B3's
                            // `-fitrah-embed-seek-to-end`): the FIRST ready item of the process jumps
                            // to 5 s before its end, so a real end-of-item / auto-advance is reachable
                            // without sitting through a whole lecture. Once per process, so the
                            // advanced-to video plays from 0 -- that is the thing under test.
                            if !Self.debugSeekNearEndConsumed,
                               LaunchArguments.debug.contains("-fitrah-player-seek-near-end"),
                               item.duration.seconds.isFinite, item.duration.seconds > 5 {
                                Self.debugSeekNearEndConsumed = true
                                player.seek(to: CMTime(seconds: item.duration.seconds - 5, preferredTimescale: 600))
                            }
                            #endif
                        // Unarmed == this rung never produced a first frame (spec §10 -> next rung);
                        // armed == it played and then died, which is the 403-class incident.
                        case .failed:
                            #if DEBUG
                            print("PlayerHostView: item failed: \(String(describing: item.error))")
                            #endif
                            self.fire(self.watchdog.armed ? .playbackError : .failedBeforeFirstFrame)
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

            // Per-ITEM, like every other observer here, so a recovery replaceCurrentItem re-arms it
            // against the new item rather than looping/advancing a dead one. AVPlayerLooper/
            // AVQueuePlayer would mean a second player type on this screen; one notification is
            // the whole feature.
            switch playToEnd {
            case .restart:
                // B4: Android REPEAT_MODE_ONE (PlayerBinder.kt:154).
                endObserver = NotificationCenter.default.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak player] _ in
                    MainActor.assumeIsolated {
                        player?.seek(to: .zero)
                        player?.play()
                    }
                }
            case .advance:
                // B5: `PlayerFragment.kt:1247-1249` -> `PlayerViewModel.playToEnd` decides (Safe Mode,
                // empty queue, queue end, auto-skip); this is only the notification.
                endObserver = NotificationCenter.default.addObserver(
                    forName: .AVPlayerItemDidPlayToEndTime, object: item, queue: .main) { [weak self] _ in
                    MainActor.assumeIsolated {
                        guard let model = self?.model else { return }
                        Task { await model.playToEnd() }
                    }
                }
            case .none:
                break
            }

            timeObserverToken = player.addPeriodicTimeObserver(
                forInterval: CMTime(seconds: 1, preferredTimescale: 600), queue: .main) { [weak self] time in
                MainActor.assumeIsolated {
                    self?.sample(time: time.seconds)
                    // B5 (CF-B1-8): the hoisted position, ~1 s coarse -- the host prefers a live
                    // `currentTime()` and reads this only when rebuilding from no player. Gated on
                    // the VM still being on the video this coordinator last built: between an
                    // advance's `swapArgs` (which zeroes it for the next video) and the update pass
                    // that swaps the item, the OLD item is still ticking here, and a fresh
                    // coordinator after a dismantle would read that clock back as the resume point.
                    if let self, self.lastVideoId == self.model?.args.videoId {
                        self.model?.currentTime = time.seconds
                        // B5 Task 3: `PlayerFullscreen.isActive`'s video-orientation input, same
                        // observer, same guard -- a portrait Short must never leak into the next video.
                        if let size = self.observedItem?.presentationSize, size.width > 0, size.height > 0 {
                            let portrait = size.height > size.width
                            if self.model?.videoIsPortrait != portrait { self.model?.videoIsPortrait = portrait }
                        }
                    }
                    // Task 4: the ONE periodic observer feeds both the stall watchdog and the lock
                    // screen's elapsed/rate. AVFoundation fires it on rate changes and time jumps
                    // as well as on the interval, so a play/pause/seek made in the stock AVKit
                    // chrome republishes here without a second observer.
                    self?.background.refreshNowPlaying()
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
            if let endObserver { NotificationCenter.default.removeObserver(endObserver) }
            endObserver = nil
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

        /// The path monitor, plus the backstop for the one teardown path that has no other exit
        /// (Task 5 review, I2): a back-navigation out of the player while PiP is live dismantles
        /// the host, defers the teardown to `…DidStopPictureInPicture`, and then SwiftUI releases
        /// this coordinator with the `PlayerScreen` -- so that callback never arrives and the audio
        /// session, the lock-screen dictionary and the remote-command handlers stay owned by a dead
        /// object for the rest of the process's life.
        ///
        /// `deinit` is nonisolated in Swift 6, hence `assumeIsolated`: SwiftUI creates and releases
        /// representable coordinators on the main actor (they are only ever reachable from
        /// `makeUIViewController`/`updateUIViewController`/`dismantleUIViewController`, all
        /// main-actor calls), so the last release lands on main. Same assumption the path monitor's
        /// `pathUpdateHandler` above already makes.
        deinit {
            monitor.cancel()
            // MIN-2 (final review): unconditional. `detach()` is idempotent (it clears an already
            // empty observer list, removes already removed command targets and hands back an
            // already inactive session), and the `dismantledWhilePiP` guard only narrowed this to
            // the ONE leak path the review happened to find -- a coordinator released on any other
            // path with an attached controller leaked exactly the same session + lock screen.
            MainActor.assumeIsolated { background.detach() }
        }
    }

    // MARK: - Builder (static so `PlayerHostTests` can call it directly, no view hierarchy needed)

    /// Builds the `AVPlayer` for one `StreamState`, reusing `replacing` when it's already playing the
    /// resolved URL (an unrelated state change -- e.g. a later task's recovery counters -- must not
    /// restart playback). Ruling 32 (session-only resume): `replacing`'s `currentTime()` carries into
    /// the replacement item; nothing here persists past this `AVPlayer`'s own lifetime.
    /// B5: `continuesCurrentVideo == false` (an auto-advance or an Up Next tap) starts the
    /// replacement at 0; `resumeFallback` is the VM's hoisted `currentTime` (CF-B1-8) for a fresh
    /// player built after a non-playable state dismantled the host. Both defaulted, so every
    /// existing call site is unchanged.
    static func player(for state: StreamState, replacing existing: AVPlayer?, audioOnly: Bool = false,
                       continuesCurrentVideo: Bool = true, resumeFallback: TimeInterval = 0) -> AVPlayer? {
        guard let resolved = state.resolved,
              let url = streamURL(resolved.stream, audioOnly: audioOnly) else {
            existing?.pause()
            return nil
        }
        // I7 (B1 final review): reuse the live item only while it can still play. An
        // `AVPlayerItem` that reached `.failed` never recovers, so handing it back on a same-URL
        // re-resolve (exactly what manual Retry and the recovery ladder do) froze the player on a
        // retry that looked like it had done something. A failed item falls through to the replace
        // path below, which builds a fresh item on the SAME `AVPlayer` (position carried over).
        // B5 Task 4: a DIFFERENT video (`continuesCurrentVideo == false`) never reuses the item
        // either -- a same-URL advance handed the ended item back and the queue stalled one short
        // of its terminus.
        if continuesCurrentVideo, let existing, (existing.currentItem?.asset as? AVURLAsset)?.url == url,
           existing.currentItem?.status != .failed {
            return existing
        }
        let item = AVPlayerItem(asset: asset(url: url, userAgent: resolved.userAgent))
        // A live player's own clock is authoritative when we have one; `resumeFallback` (the VM's
        // hoisted currentTime, CF-B1-8) covers the case where a non-playable state dismantled the
        // host and a fresh AVPlayer is being built. `continuesCurrentVideo == false` (an
        // auto-advance or an Up Next tap) means neither applies -- the next video starts at 0.
        let live = existing?.currentTime().seconds
        let resume = continuesCurrentVideo ? ((live?.isFinite == true ? live : nil) ?? resumeFallback) : 0
        let resumeTime = CMTime(seconds: resume, preferredTimescale: 600)
        guard let existing else {
            let player = AVPlayer(playerItem: item)
            // Task 8 (spec §10 AirPlay): explicit, not inherited from the default. AVKit's stock
            // transport already carries the route picker; this is what makes the picked route
            // actually play the video remotely. Written ONLY here, on a freshly built player, and
            // never on an update pass -- `PlayerViewModel`'s mirroring fallback clears it on the
            // live player, and an unconditional write per pass would undo that immediately.
            player.allowsExternalPlayback = true
            if resume > 0 { player.seek(to: resumeTime) }
            player.play()
            return player
        }
        // I5 (player.md §3.2: a re-resolve saves position AND playWhenReady): resuming a stream the
        // user had deliberately paused is a real behaviour bug -- recovery replaces the item under a
        // paused player just as readily as under a playing one.
        // B5 Task 4: a DIFFERENT video always starts -- an ended item leaves the player `.paused`,
        // so an auto-advance under this rule alone swapped the item in and never played it.
        let wasPlaying = existing.timeControlStatus != .paused || !continuesCurrentVideo
        existing.replaceCurrentItem(with: item)
        if resume > 0 { existing.seek(to: resumeTime) }
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
        AVURLAsset(url: url, options: assetOptions(userAgent: userAgent, url: url))
    }

    /// Task 7 (the Task 4 trap): a LOCAL file gets `AVURLAssetPreferPreciseDurationAndTimingKey` —
    /// without it a saved fMP4 m4a reports ~2× its real duration (measured, Task 4's live leg).
    /// File URLs only: on a remote stream precise timing forces a full parse over the network for
    /// a duration the estimate already gets right. ONE seam, so offline playback needs no player
    /// fork; `OfflinePlaybackTests` pins the split.
    static func assetOptions(userAgent: String, url: URL) -> [String: Any] {
        var options = assetOptions(userAgent: userAgent)
        if url.isFileURL { options[AVURLAssetPreferPreciseDurationAndTimingKey] = true }
        return options
    }

    static func assetOptions(userAgent: String) -> [String: Any] {
        [AVURLAssetHTTPUserAgentKey: userAgent]
    }

    /// Not private: `PlayerHostTests` pins the audio-only selection directly.
    static func streamURL(_ stream: ResolvedStream, audioOnly: Bool = false) -> URL? {
        switch stream {
        case .hls(let url, _, let audioOnlyURL, _):
            // The `?? url` fallback is belt-and-braces: `PlayerViewModel.audioOnlyAvailable` hides
            // the toggle (and gates the automatic swap) when there is no itag 140 URL, so a true
            // `audioOnly` with a nil rendition should be unreachable -- and if it ever is reached,
            // video is the right thing to keep playing.
            return audioOnly ? (audioOnlyURL ?? url) : url
        case .progressive(let url, _):
            return url   // rung 2 has no separate audio rendition; the toggle is hidden there anyway
        case .embed:
            return nil // B3
        }
    }
}

/// The two playback surfaces this app has. Not a feature flag and not a style: each case is a set
/// of four AVKit properties that must move together, and naming the surface is what stops them
/// drifting apart. Spec 10's Shorts paragraph is the whole right-hand column.
enum PlayerPresentation: Sendable, Equatable {
    case standard, shorts

    var showsPlaybackControls: Bool { self == .standard }
    var videoGravity: AVLayerVideoGravity { self == .shorts ? .resizeAspectFill : .resizeAspect }
    var loops: Bool { self == .shorts }
    /// Ruling 34 gives the Background play SETTING a real effect -- for the main player. Shorts
    /// override it to off (Android parity, brief 9.5: onStop pauses, onStart resumes iff it was
    /// playing). A 60-second clip on repeat-one is not a background-audio use case, and leaving it
    /// on would loop audio out of a screen the user has walked away from, indefinitely.
    var allowsBackgroundPlayback: Bool { self == .standard }
    /// B5: the main player advances through its queue at end-of-item (`PlayerViewModel.playToEnd`
    /// owns Safe Mode / empty queue / queue-end / auto-skip); Shorts always loop, never advance.
    var actionOnPlayToEnd: PlayToEndAction { loops ? .restart : .advance }
}

enum PlayToEndAction: Sendable, Equatable { case restart, advance, none }

/// What `PlayerHostView`'s teardown still owes, given whether a PiP window is holding the player.
struct PiPTeardownActions: Equatable, Sendable {
    /// Hand the audio session, the lifecycle observers, the remote commands and Now Playing back.
    var detachBackground: Bool
    /// Pause the `AVPlayer` and drop it off the controller.
    var releasePlayer: Bool
    /// Remember the host is gone so `…DidStopPictureInPicture` can finish the job later.
    var deferUntilPiPStops: Bool
}

/// Plan §6.5's "never detach the player while PiP is active", as a truth table over the two inputs
/// the AVKit path cannot be unit-tested against. Both call sites -- SwiftUI's
/// `dismantleUIViewController` and AVKit's `…DidStopPictureInPicture` -- ask this same question, so
/// the deferred teardown is one decision, not two hand-mirrored branches.
enum PiPDismantlePolicy {
    static func teardown(pictureInPictureActive: Bool, hostDismantled: Bool) -> PiPTeardownActions {
        // Nothing is owed while the host is still mounted: it owns the player and the session.
        guard hostDismantled else {
            return PiPTeardownActions(detachBackground: false, releasePlayer: false, deferUntilPiPStops: false)
        }
        // Detaching would cut the audio session and Now Playing out from under a live PiP window;
        // pausing and nil-ing the player would blank it.
        let now = !pictureInPictureActive
        return PiPTeardownActions(detachBackground: now, releasePlayer: now, deferUntilPiPStops: !now)
    }
}
