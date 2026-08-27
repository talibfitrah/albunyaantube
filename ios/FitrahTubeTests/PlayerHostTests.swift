import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct PlayerHostTests {
    private static func resolved(_ stream: ResolvedStream, userAgent: String = "TestUA") -> Resolved {
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
        coordinator.observe(item: videoItem, player: player, model: nil, isLive: false)
        defer { coordinator.stopObserving() }

        PlayerHostView.applyPolicyAction(.swapToAudioOnly, state: state, player: player,
                                         model: nil, coordinator: coordinator)

        #expect(player.currentItem !== videoItem)
        #expect(coordinator.observedItem === player.currentItem)
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
