import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct PlayerHostTests {
    static func resolved(_ stream: ResolvedStream, userAgent: String = "TestUA") -> Resolved {
        Resolved(stream: stream, client: .visionos, userAgent: userAgent, resolvedAt: Date(), expiresAt: nil)
    }

    // MARK: - .ready builds a player carrying the resolved URL + User-Agent (plan constraint: the
    // resolved User-Agent MUST be set on the AVURLAsset)

    @Test func readyStateBuildsPlayerWithResolvedURL() throws {
        let url = URL(string: "https://example.com/stream.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []), userAgent: "FitrahTube/1.0")

        let player = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let asset = try #require(player?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == url)
    }

    @Test func readyStateAssetOptionsCarryTheResolvedUserAgent() {
        // `AVURLAsset` doesn't expose its `options` dictionary back out once built, so this asserts
        // the options `player(for:replacing:)` actually passes to `AVURLAsset(url:options:)` --
        // the only place the User-Agent value is inspectable.
        let options = PlayerHostView.assetOptions(userAgent: "FitrahTube/1.0")
        #expect(options[AVURLAssetHTTPUserAgentKey] as? String == "FitrahTube/1.0")
    }

    @Test func rung2ProgressiveStateAlsoBuildsAPlayer() throws {
        let url = URL(string: "https://example.com/a.mp4")!
        let resolved = Self.resolved(.progressive(url: url, label: "360p"))

        let player = PlayerHostView.player(for: .rung2Progressive(resolved), replacing: nil)

        let asset = try #require(player?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == url)
    }

    // MARK: - Non-playable states never build a player
    // Five plain functions, not `@Test(arguments:)` (`PlayerViewModelTests`'s pattern for
    // `ExtractionError`) -- `StreamState` isn't `Sendable` (Task 2's type, not this task's to touch)
    // and Swift Testing's `arguments:` needs its element type to cross that boundary.

    @Test func idleBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .idle, replacing: nil) == nil)
    }

    @Test func loadingBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .loading, replacing: nil) == nil)
    }

    @Test func contentUnavailableBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .contentUnavailable, replacing: nil) == nil)
    }

    @Test func errorBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .error(messageKey: "player_error_message"), replacing: nil) == nil)
    }

    @Test func cooldownBuildsNoPlayer() {
        #expect(PlayerHostView.player(for: .cooldown(until: Date()), replacing: nil) == nil)
    }

    /// B3 task 4: rung 3 is not an `AVPlayer` surface at all -- it is a `WKWebView`
    /// (`EmbedRungView`). `streamURL` returning nil for it is what makes `player(for:replacing:)`
    /// pause and release rather than swap (CF-B2-4), and it is also why no PiP affordance exists on
    /// that rung: there is no `AVPlayerViewController` to offer one.
    @Test func embedBuildsNoPlayer() {
        let resolved = Self.resolved(.embed(videoId: "xc7keR2piUM"))
        #expect(PlayerHostView.player(for: .embed(resolved), replacing: nil) == nil)
        #expect(PlayerHostView.streamURL(.embed(videoId: "xc7keR2piUM")) == nil)
    }

    // MARK: - Ruling 32: session-only resume -- replacing an existing player for the SAME URL keeps it

    @Test func sameURLReusesTheExistingPlayerInstance() {
        let url = URL(string: "https://example.com/stream.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let first = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let second = PlayerHostView.player(for: .ready(resolved), replacing: first)

        #expect(second === first)
    }

    /// I7 (B1 final review): a same-URL re-resolve after the item FAILED must not hand the dead
    /// item back -- an `AVPlayerItem` whose status is `.failed` never recovers, so reusing it
    /// freezes the player on a retry that looks like it did something.
    @Test func sameURLDoesNotReuseAPlayerWhoseItemFailed() async throws {
        // A local file that doesn't exist fails deterministically (and fast) once the player
        // tries to load it -- the only way to get a real `.failed` item in a unit target.
        let url = URL(fileURLWithPath: "/nonexistent/fitrah-failed-item.mp4")
        let resolved = Self.resolved(.progressive(url: url, label: "360p"))
        let first = try #require(PlayerHostView.player(for: .rung2Progressive(resolved), replacing: nil))
        let failedItem = try #require(first.currentItem)
        var waited = 0
        while failedItem.status != .failed, waited < 100 {
            try await Task.sleep(for: .milliseconds(50))
            waited += 1
        }
        #expect(failedItem.status == .failed)

        let second = PlayerHostView.player(for: .rung2Progressive(resolved), replacing: first)

        #expect(second === first) // same AVPlayer -- only the dead ITEM must be replaced
        #expect(second?.currentItem !== failedItem)
    }

    // MARK: - A state update to a DIFFERENT URL replaces the item, carrying `currentTime` over

    /// B5 Task 4 finding: an advance (or Up Next tap) to a video whose stream URL equals the
    /// current one handed the ENDED item back untouched -- no restart, no second end-of-item, so
    /// the queue silently stalled one short of `.queueEnded`. Same URL only ever happens with the
    /// fixture resolver today, but the rule is the right one regardless: `continuesCurrentVideo`
    /// already says "this is a different video", and a different video is never the same item.
    @Test func aDifferentVideoNeverReusesTheItemEvenOnTheSameURL() throws {
        let url = URL(string: "https://example.com/stream.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let player = try #require(PlayerHostView.player(for: .ready(resolved), replacing: nil))
        let firstItem = try #require(player.currentItem)
        // An ended item leaves the player `.paused` (actionAtItemEnd). I5's "never resume a stream
        // the user paused" is about the SAME video; a different one must always start.
        player.pause()

        let advanced = PlayerHostView.player(for: .ready(resolved), replacing: player, continuesCurrentVideo: false)

        #expect(advanced === player)
        #expect(advanced?.currentItem !== firstItem)
        #expect(advanced?.timeControlStatus != .paused, "the advanced-to video must play")
    }

    @Test func differentURLReplacesTheItemOnTheSamePlayer() throws {
        let firstURL = URL(string: "https://example.com/a.m3u8")!
        let secondURL = URL(string: "https://example.com/b.m3u8")!
        let first = Self.resolved(.hls(url: firstURL, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let second = Self.resolved(.hls(url: secondURL, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let player = PlayerHostView.player(for: .ready(first), replacing: nil)

        let updated = PlayerHostView.player(for: .ready(second), replacing: player)

        #expect(updated === player) // same AVPlayer instance, new item swapped in
        let asset = try #require(updated?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == secondURL)
    }

    // MARK: - Task 3: audio-only URL selection and the same-player swap

    @Test func audioOnlySelectsTheItag140URL() {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let audio = URL(string: "https://r1.googlevideo.com/a140")!
        let stream = ResolvedStream.hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: [])
        #expect(PlayerHostView.streamURL(stream, audioOnly: false) == video)
        #expect(PlayerHostView.streamURL(stream, audioOnly: true) == audio)
    }

    @Test func audioOnlyFallsBackToVideoWhenNoAudioTrackExists() {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let stream = ResolvedStream.hls(url: video, isLive: false, audioOnlyURL: nil, captionTracks: [])
        #expect(PlayerHostView.streamURL(stream, audioOnly: true) == video)
        let progressive = ResolvedStream.progressive(url: video, label: "360p")
        #expect(PlayerHostView.streamURL(progressive, audioOnly: true) == video)
    }

    @Test func togglingAudioOnlyReplacesTheItemAndKeepsThePlayer() throws {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let audio = URL(string: "https://r1.googlevideo.com/a140")!
        let resolved = Self.resolved(.hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: []))
        let state = StreamState.ready(resolved)
        let first = PlayerHostView.player(for: state, replacing: nil, audioOnly: false)

        let second = PlayerHostView.player(for: state, replacing: first, audioOnly: true)

        #expect(second === first)  // same AVPlayer -- position carries via replaceCurrentItem + seek
        let asset = try #require(second?.currentItem?.asset as? AVURLAsset)
        #expect(asset.url == audio)
    }

    @Test func audioOnlyAssetCarriesTheResolvedUserAgent() {
        // The itag 140 URL is IP+UA-bound exactly like the manifest; a bare asset 403s.
        #expect(PlayerHostView.assetOptions(userAgent: "UA")[AVURLAssetHTTPUserAgentKey] as? String == "UA")
    }

    // MARK: - Final review (IMP-1, MIN-4): what the background policy may and may not do synchronously

    /// IMP-1: only the BACKGROUND half of the policy swaps the item here. `.restoreVideo` runs
    /// against whatever `state` is live at action time -- and the pre-emptive TTL refresh can land
    /// AFTER the 2 s foreground deadline, so a synchronous restore would replace the item once
    /// against the stale `Resolved` and the foreground update pass would replace it a second time
    /// against the fresh one, restarting playback from 0. One replace, owned by the update pass.
    @Test func restoringVideoDoesNotReplaceTheItemSynchronously() throws {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let audio = URL(string: "https://r1.googlevideo.com/a140")!
        let state = StreamState.ready(Self.resolved(.hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: [])))
        let player = try #require(PlayerHostView.player(for: state, replacing: nil, audioOnly: true))
        let audioItem = try #require(player.currentItem)

        PlayerHostView.applyPolicyAction(.restoreVideo, state: state, player: player, model: nil)

        #expect(player.currentItem === audioItem)
        #expect((player.currentItem?.asset as? AVURLAsset)?.url == audio)
    }

    /// MIN-4: the synchronous background swap builds a NEW `AVPlayerItem`, and the recovery
    /// observers (status KVO, failed-to-play-to-end, the periodic sampler) are per-item. Left on
    /// the outgoing item, an audio-only stream that dies in the background would never fire the
    /// recovery ladder.
    @Test func theBackgroundSwapReArmsTheRecoveryObserversOnTheNewItem() throws {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let audio = URL(string: "https://r1.googlevideo.com/a140")!
        let state = StreamState.ready(Self.resolved(.hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: [])))
        let player = try #require(PlayerHostView.player(for: state, replacing: nil, audioOnly: false))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let videoItem = try #require(player.currentItem)
        coordinator.observe(item: videoItem, player: player, model: nil, isLive: false, playToEnd: .advance)
        defer { coordinator.stopObserving() }

        PlayerHostView.applyPolicyAction(.swapToAudioOnly, state: state, player: player,
                                         model: nil, coordinator: coordinator)

        #expect(player.currentItem !== videoItem)
        #expect(coordinator.observedItem === player.currentItem)
        // B5 Task 4 (live step 2 item 8 FAILED): the re-arm used `.none`, so an end-of-item that
        // landed while backgrounded never advanced -- the audio-only item must keep the action the
        // video item had, or background auto-advance (reconciliation note 2) is fiction.
        #expect(coordinator.playToEnd == .advance)
    }

    /// I2 (B4 T2 fix round 2): the repeat-one loop. Real clip (`player-fixture.mp4`, the fake-player
    /// fixture) so the seek lands on a loaded item; the end notification is posted by hand because
    /// waiting for a 2 s clip to play out is what the wall clock is for, not this test.
    @Test(.timeLimit(.minutes(1))) func restartLoopSeeksToZeroOnPlayToEndAndOnlyWhileObserving() async throws {
        let url = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let state = StreamState.rung2Progressive(Self.resolved(.progressive(url: url, label: "360p")))
        let player = try #require(PlayerHostView.player(for: state, replacing: nil))
        let item = try #require(player.currentItem)
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: false)
        coordinator.observe(item: item, player: player, model: nil, isLive: false, playToEnd: .restart)

        let one = CMTime(seconds: 1, preferredTimescale: 600)
        await player.seek(to: one, toleranceBefore: .zero, toleranceAfter: .zero)
        #expect(player.currentTime().seconds > 0.5)
        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        await Task.yield()   // the observer runs on `.main`; one turn is all it needs
        for _ in 0..<200 where player.currentTime().seconds > 0.1 { try await Task.sleep(for: .milliseconds(5)) }
        #expect(player.currentTime().seconds < 0.1)

        coordinator.stopObserving()
        player.pause()
        await player.seek(to: one, toleranceBefore: .zero, toleranceAfter: .zero)
        NotificationCenter.default.post(name: .AVPlayerItemDidPlayToEndTime, object: item)
        try await Task.sleep(for: .milliseconds(50))
        #expect(player.currentTime().seconds > 0.5)
    }

    // MARK: - A non-playable state tears playback down instead of leaving a stale player

    // MARK: - Task 5: Picture in Picture

    @Test func pictureInPictureIsEnabledAndAutoStartFollowsTheBackgroundPlaySetting() {
        // ruling 43 + plan §6.5: PiP is on; auto-start-from-inline is on only when the user has
        // allowed background playback, so backgrounding with the setting OFF cannot smuggle video
        // into a floating window the user asked not to have (ruling 34).
        let controller = AVPlayerViewController()
        PlayerHostView.configurePictureInPicture(controller, backgroundPlay: true)
        #expect(controller.allowsPictureInPicturePlayback)
        #expect(controller.canStartPictureInPictureAutomaticallyFromInline)

        PlayerHostView.configurePictureInPicture(controller, backgroundPlay: false)
        #expect(controller.allowsPictureInPicturePlayback)
        #expect(controller.canStartPictureInPictureAutomaticallyFromInline == false)
    }

    // MARK: - Task 5: the teardown truth table (pure -- the AVKit path itself is untestable here)

    @Test func teardownReleasesEverythingWhenNoPiPWindowIsHoldingThePlayer() {
        #expect(PiPDismantlePolicy.teardown(pictureInPictureActive: false, hostDismantled: true)
                == PiPTeardownActions(detachBackground: true, releasePlayer: true, deferUntilPiPStops: false))
    }

    @Test func teardownDefersEverythingWhilePiPIsActive() {
        // Detaching would hand back the audio session and clear Now Playing under a live PiP
        // window; pausing/nil-ing the player would blank it.
        #expect(PiPDismantlePolicy.teardown(pictureInPictureActive: true, hostDismantled: true)
                == PiPTeardownActions(detachBackground: false, releasePlayer: false, deferUntilPiPStops: true))
    }

    @Test func pipStoppingWithTheHostStillMountedTearsNothingDown() {
        #expect(PiPDismantlePolicy.teardown(pictureInPictureActive: false, hostDismantled: false)
                == PiPTeardownActions(detachBackground: false, releasePlayer: false, deferUntilPiPStops: false))
        #expect(PiPDismantlePolicy.teardown(pictureInPictureActive: true, hostDismantled: false)
                == PiPTeardownActions(detachBackground: false, releasePlayer: false, deferUntilPiPStops: false))
    }

    // MARK: - Task 5: the delegate wiring

    @Test func pipStateFlipsOnWillStartNotDidStart() {
        // WILL, not DID: `.enteredBackground` reads `pictureInPictureActive`, and the ordering of
        // the AVKit delegate callback against `didEnterBackgroundNotification` is not guaranteed.
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let controller = AVPlayerViewController()
        coordinator.playerViewControllerWillStartPictureInPicture(controller)
        #expect(coordinator.background.pictureInPictureActive)
        coordinator.playerViewControllerDidStopPictureInPicture(controller)
        #expect(coordinator.background.pictureInPictureActive == false)
    }

    /// Task 5 review minor: AVKit BLOCKS the PiP-stop transition on this completion handler, so
    /// an implementation that stashed it (or answered on a later run-loop turn) would leave the
    /// floating window stuck mid-dismissal. The player screen is still mounted behind it, so the
    /// only correct answer is `true`, now.
    @Test func restoringTheInterfaceForAPiPStopAnswersTrueSynchronously() {
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        var answered: Bool?
        coordinator.playerViewController(
            AVPlayerViewController(),
            restoreUserInterfaceForPictureInPictureStopWithCompletionHandler: { answered = $0 })
        #expect(answered == true)
    }

    @Test func dismantlingDuringPiPKeepsThePlayerUntilPiPStops() {
        let url = URL(string: "https://example.com/a.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let controller = AVPlayerViewController()
        controller.player = PlayerHostView.player(for: .ready(resolved), replacing: nil)
        coordinator.playerViewControllerWillStartPictureInPicture(controller)

        PlayerHostView.dismantleUIViewController(controller, coordinator: coordinator)
        #expect(controller.player != nil)   // a live PiP window is still playing it

        coordinator.playerViewControllerDidStopPictureInPicture(controller)
        #expect(controller.player == nil)   // the deferred teardown finally runs
    }

    // MARK: - A non-playable state tears playback down instead of leaving a stale player (cont.)

    @Test func transitioningToANonPlayableStatePausesTheExistingPlayer() {
        let url = URL(string: "https://example.com/a.m3u8")!
        let resolved = Self.resolved(.hls(url: url, isLive: false, audioOnlyURL: nil, captionTracks: []))
        let player = PlayerHostView.player(for: .ready(resolved), replacing: nil)

        let result = PlayerHostView.player(for: .contentUnavailable, replacing: player)

        #expect(result == nil)
        #expect(player?.rate == 0) // paused, not left running detached from any view
    }
}

// MARK: - B4 Task 2: `PlayerPresentation` -- the pure knob table behind `PlayerHostView.presentation`

@Suite struct PlayerPresentationTests {
    @Test func shortsPresentationSwitchesOffEveryMainPlayerAffordance() {
        // Spec 10 Shorts: no stock transport, fill-and-crop (Android resize_mode="zoom"), repeat-one.
        // Ruling 43 + CF-B2-15: no background playback => backgroundPolicy .pauses AND
        // canStartPictureInPictureAutomaticallyFromInline false, both of which key off this one flag.
        #expect(PlayerPresentation.shorts.showsPlaybackControls == false)
        #expect(PlayerPresentation.shorts.videoGravity == .resizeAspectFill)
        #expect(PlayerPresentation.shorts.loops)
        #expect(PlayerPresentation.shorts.allowsBackgroundPlayback == false)
    }

    @Test func standardPresentationIsUnchangedFromB1AndB2() {
        // The regression guard for the defaulted parameter: nothing about the main player moved.
        #expect(PlayerPresentation.standard.showsPlaybackControls)
        #expect(PlayerPresentation.standard.videoGravity == .resizeAspect)
        #expect(PlayerPresentation.standard.loops == false)
        #expect(PlayerPresentation.standard.allowsBackgroundPlayback)
    }

    @Test func shortsNeverAskAVFoundationToKeepPlayingInTheBackground() {
        // CF-B2-15's cost cannot arise on this screen: with backgroundPlay false the policy is .pauses
        // for EVERY stream shape, itag 140 or not, so nothing pulls video for a screen nobody sees.
        #expect(AudioSessionPolicy.backgroundPolicy(backgroundPlay: false, pictureInPictureActive: false) == .pauses)
        #expect(AudioSessionPolicy.decide(.enteredBackground, PlaybackPolicyContext(
            backgroundPlay: false, userAudioOnly: false, audioOnlyAvailable: true,
            pictureInPictureActive: false, wasPlayingBeforeInterruption: false,
            autoSwappedToAudioOnly: false)) == .none)
    }

    /// I4 (B4 final review): the ONE read of the setting, `PlayerHostView.effectiveBackgroundPlay`,
    /// wired into the coordinator it builds. Coordinator half only: `makeUIViewController` needs a
    /// `UIViewControllerRepresentableContext`, which cannot be built outside a SwiftUI host, so the
    /// controller's `showsPlaybackControls` / `videoGravity` stay pinned via `PlayerPresentation`
    /// above and the UI matrix (`ScreenshotTests.testShortsB4Task4IPhone`: no transport, 9:16 fill).
    @Test func shortsCoordinatorForcesBackgroundPlayOffEvenWhenTheSettingIsOn() {
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "PlayerPresentationTests.\(UUID().uuidString)")!)
        settings.backgroundPlay = true
        let model = PlayerViewModel(resolver: RecordingResolver(.hls), settings: settings,
                                    args: PlayerArgs(videoId: "xc7keR2piUM"))
        #expect(model.backgroundPlay)

        let shorts = PlayerHostView(state: .idle, quality: .auto, audioOnly: false, model: model, presentation: .shorts)
        #expect(shorts.makeCoordinator().background.backgroundPlay == false)
        let standard = PlayerHostView(state: .idle, quality: .auto, audioOnly: false, model: model, presentation: .standard)
        #expect(standard.makeCoordinator().background.backgroundPlay)
    }

    @Test func theLoopRestartsFromZeroRatherThanAdvancing() {
        // Android REPEAT_MODE_ONE (PlayerBinder.kt:154). The decision is pure so the notification glue
        // has nothing to decide: an ended item under .shorts seeks to zero and plays; under .standard
        // it asks the VM to advance (`playToEnd` owns Safe Mode / empty queue / queue end).
        #expect(PlayerPresentation.shorts.actionOnPlayToEnd == .restart)     // never advances
        #expect(PlayerPresentation.standard.actionOnPlayToEnd == .advance)   // playToEnd() no-ops on an empty queue
    }

    @Test func positionIsPreservedOnlyWhileTheVideoIsTheSameOne() {
        // The host seeks a replacement item back to the outgoing item's time. That is right for a
        // re-resolve or an audio-only swap and WRONG for an auto-advance -- it would start the next
        // video at the previous one's position.
        #expect(PlayerHostView.shouldPreservePosition(previous: "a", next: "a"))
        #expect(PlayerHostView.shouldPreservePosition(previous: nil, next: "a"))  // first build
        #expect(PlayerHostView.shouldPreservePosition(previous: "a", next: "b") == false)
    }

    @Test(.timeLimit(.minutes(1))) func anAdvanceBuildsTheNextItemAtZeroAndAResolveKeepsThePosition() async throws {
        // B5: `continuesCurrentVideo == false` (an advance or an Up Next tap) must not carry the
        // outgoing item's clock OR the hoisted `resumeFallback` into the next video; a fresh player
        // for the same video (host rebuilt after a state hop, CF-B1-8) still seeks to the fallback.
        // Real clip so the seeks land on a loaded item (2 s long, hence 1 s not 42 for the resume).
        let url = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let state = StreamState.rung2Progressive(PlayerHostTests.resolved(.progressive(url: url, label: "360p")))
        let player = try #require(PlayerHostView.player(for: state, replacing: nil))
        player.pause()
        await player.seek(to: CMTime(seconds: 1, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        #expect(player.currentTime().seconds > 0.5)

        // A DIFFERENT url (a temp copy of the clip): the same url would hit the builder's reuse path.
        let copy = FileManager.default.temporaryDirectory.appendingPathComponent("\(UUID().uuidString).mp4")
        try FileManager.default.copyItem(at: url, to: copy)
        defer { try? FileManager.default.removeItem(at: copy) }
        let next = StreamState.rung2Progressive(PlayerHostTests.resolved(.progressive(url: copy, label: "360p")))
        let advanced = try #require(PlayerHostView.player(for: next, replacing: player,
                                                          continuesCurrentVideo: false, resumeFallback: 1))
        #expect(advanced === player)
        try await Self.settle(advanced) { $0 < 0.1 }
        #expect(advanced.currentTime().seconds < 0.1)

        let rebuilt = try #require(PlayerHostView.player(for: state, replacing: nil,
                                                         continuesCurrentVideo: true, resumeFallback: 1))
        try await Self.settle(rebuilt) { $0 > 0.9 }
        #expect(abs(rebuilt.currentTime().seconds - 1) < 0.1)
    }

    /// C1 (B5 T2 review): the periodic observer hoists `currentTime` into the VM. Between an
    /// advance's `swapArgs` (which zeroes it for the NEXT video) and the update pass that swaps the
    /// item, the OLD item is still ticking -- and a fresh coordinator after a dismantle would read
    /// that stale clock back as the new video's resume point. The write is gated on the
    /// coordinator's last-built video still being the VM's current one.
    @Test(.timeLimit(.minutes(1))) func theHoistedPositionIsNotWrittenWhileTheViewModelIsOnAnotherVideo() async throws {
        let url = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let state = StreamState.rung2Progressive(PlayerHostTests.resolved(.progressive(url: url, label: "360p")))
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "PlayerHostTests.\(UUID().uuidString)")!)
        let model = PlayerViewModel(resolver: RecordingResolver(.hls), settings: settings,
                                    args: PlayerArgs(videoId: "fixture-1"))
        let player = try #require(PlayerHostView.player(for: state, replacing: nil))
        let item = try #require(player.currentItem)
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: false)
        coordinator.lastVideoId = "fixture-0"   // the coordinator last built the PREVIOUS video
        coordinator.observe(item: item, player: player, model: model, isLive: false, playToEnd: .none)

        // Playing (the builder called `play()`): >1 s of wall clock past a 1 s seek guarantees at
        // least one 1 s-interval tick carrying a time >= 1 -- the stale write this test pins.
        await player.seek(to: CMTime(seconds: 1, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        try await Task.sleep(for: .milliseconds(1200))
        #expect(model.currentTime == 0)

        coordinator.lastVideoId = "fixture-1"
        await player.seek(to: CMTime(seconds: 1, preferredTimescale: 600), toleranceBefore: .zero, toleranceAfter: .zero)
        player.play()
        try await Task.sleep(for: .milliseconds(1200))
        #expect(model.currentTime > 0.5)
        coordinator.stopObserving()
    }

    /// Polls the live clock until `done` holds (seeks without a completion handler are async).
    private static func settle(_ player: AVPlayer, until done: (Double) -> Bool) async throws {
        for _ in 0..<400 where !done(player.currentTime().seconds) { try await Task.sleep(for: .milliseconds(5)) }
    }
}
