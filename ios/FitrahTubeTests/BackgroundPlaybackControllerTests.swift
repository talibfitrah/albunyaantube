import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import MediaPlayer
import SwiftUI
import Testing
import UIKit
@testable import FitrahTube

/// T2-1 (Task 2 review): `AudioSessionPolicyTests` covers the pure decision table; this covers the
/// other half -- that a real lifecycle NOTIFICATION reaches the controller, and what the production
/// policy handler does with the action it produces (a `.pause` drops a failure's resume intent).
///
/// `.serialized`: every test here posts a PROCESS-WIDE notification, so two running at once would
/// each drive the other's controller.
@Suite(.serialized) struct BackgroundPlaybackControllerTests {
    /// A real, playable local item -- the same 2 s clip the screenshot rig plays (the unit target's
    /// host IS the app, see `SmokeTests`) -- on a stream that also carries a LOCAL url standing in for
    /// the itag 140 rendition, so a swap would show as an asset-url change with no network traffic.
    private static func fixture() throws -> (state: StreamState, video: URL) {
        let video = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let audio = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("fitrah-audio-only.m4a")
        let resolved = Resolved(stream: .hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: []),
                                client: .visionos, userAgent: "TestUA", resolvedAt: Date(), expiresAt: nil)
        return (.ready(resolved), video)
    }

    /// The observers are registered with `queue: .main`, so delivery is a run-loop hop away rather
    /// than synchronous with `post`. 2 s ceiling, and every one of these settles in one hop.
    ///
    /// MIN-1 (final review): the ceiling ASSERTS. Falling out of the loop silently meant a timed-out
    /// wait ran the assertions anyway, where a `!actions.contains(…)` expectation would pass for the
    /// wrong reason -- the event never arrived, rather than arriving and being correctly dropped.
    private func wait(until condition: () -> Bool) async throws {
        for _ in 0..<100 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
        #expect(condition())
    }

    private static func assetURL(_ player: AVPlayer) -> URL? {
        (player.currentItem?.asset as? AVURLAsset)?.url
    }

    /// B5 Task 4: MediaPlayer invokes the artwork request handler on its own queue. Off-main here,
    /// exactly as `_onQueue_pushNowPlayingInfoAndRetry:` does -- a main-actor-bound handler traps.
    @Test func artworkRequestHandlerIsCallableOffTheMainActor() async {
        let image = UIGraphicsImageRenderer(size: CGSize(width: 2, height: 2)).image { _ in }
        let artwork = BackgroundPlaybackController.artwork(for: image)
        let rendered = await Task.detached { artwork.image(at: CGSize(width: 2, height: 2)) }.value
        #expect(rendered != nil)
    }

    /// 2026-11-11 owner report (lock screen = silence): the itag 140 URL never loads as a progressive asset -- a DASH
    /// fMP4 whose open-ended range googlevideo throttles; AVPlayer sat at `.unknown` for 35 s, foreground or not. So
    /// backgrounding keeps the item that plays, whatever audio rendition the stream also carries.
    @Test func backgroundingKeepsThePlayingItemEvenWithAnAudioRendition() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let videoItem = player.currentItem
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let controller = coordinator.background
        var actions: [PlaybackPolicyAction] = []
        let production = PlayerHostView.policyHandler(coordinator: coordinator)
        controller.onPolicyAction = { actions.append($0); production($0) }
        var reResolves = 0
        controller.onWillEnterForeground = { reResolves += 1 }   // proof the notifications were delivered
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { reResolves == 1 }

        #expect(Self.assetURL(player) == fixture.video)
        #expect(player.currentItem === videoItem)
        #expect(actions.filter { $0 != .none }.isEmpty)
    }

    /// `attach`'s identity guard: `updateUIViewController` hands the same `AVPlayer` back on nearly
    /// every pass, so a second registration would double every lifecycle event.
    @Test func attachingTheSamePlayerTwiceRegistersOneObserverSet() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { action in actions.append(action) }
        controller.attach(player: player)
        controller.attach(player: player)
        defer { controller.detach() }

        Self.postUnplug()
        try await wait { actions.contains(.pause) }
        // A duplicate registration delivers on the same run-loop hop, so it would already be here;
        // the extra settle is only insurance against a slow simulator.
        try await Task.sleep(for: .milliseconds(200))

        #expect(actions.filter { $0 == .pause }.count == 1)
    }

    /// PiP started from the transport button flips the flag the HD stager and the foreground re-resolve read, and
    /// touches nothing else: rebuilding the item under the window AVKit is opening would blank it.
    @Test func startingPictureInPictureEmitsNoAction() {
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        var actions: [PlaybackPolicyAction] = []
        coordinator.background.onPolicyAction = { actions.append($0) }

        coordinator.playerViewControllerWillStartPictureInPicture(AVPlayerViewController())

        #expect(actions.isEmpty)
        #expect(coordinator.background.pictureInPictureActive)
    }

    // MARK: - CF-B1-3 (Task 6)

    /// Every return to the foreground runs the pre-emptive TTL re-resolve.
    @Test func theForegroundRunsTheReResolve() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        var reResolves = 0
        controller.onWillEnterForeground = { reResolves += 1 }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { reResolves == 1 }

        #expect(reResolves == 1)
    }

    /// Task 5: a live PiP window is playing the very item a re-resolve would replace, so the
    /// window would re-buffer or blank. The unexpired stream keeps playing instead; the refresh
    /// gets its next chance on the following foreground, once PiP has stopped.
    @Test func theForegroundReResolveIsSuppressedWhileAPiPWindowIsLive() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        var reResolves = 0
        controller.onWillEnterForeground = { reResolves += 1 }
        controller.attach(player: player)
        defer { controller.detach() }
        controller.pictureInPictureActive = true

        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await Task.sleep(for: .milliseconds(200))   // `theForegroundRunsTheReResolve` lands within this

        #expect(reResolves == 0)
    }

    // MARK: - A failure's resume intent across a pause (Cubic A6; fix r4 N-2, N-6(b))

    /// The playing clip on a coordinator whose controller runs the PRODUCTION policy handler (each
    /// action also handed to `log`). The item then fails under the user: `fire` arms the resume
    /// intent, and the failure leaves the player paused.
    private func failedWhilePlaying(log: @escaping (PlaybackPolicyAction) -> Void) async throws
        -> (model: PlayerViewModel, player: AVPlayer, coordinator: PlayerHostView.Coordinator) {
        let (model, player, coordinator) = try await attachedWhilePlaying(log: log)
        PlayerHostTests.postFailure(on: player)
        player.pause()
        #expect(coordinator.resumeOnNextItem, "the failure armed no resume intent")
        return (model, player, coordinator)
    }

    /// The playing clip on a coordinator whose attached controller runs the PRODUCTION policy
    /// handler (each action also handed to `log`).
    private func attachedWhilePlaying(log: @escaping (PlaybackPolicyAction) -> Void) async throws
        -> (model: PlayerViewModel, player: AVPlayer, coordinator: PlayerHostView.Coordinator) {
        let (model, player, coordinator) = try await PlayerHostTests.playingHarness()
        let production = PlayerHostView.policyHandler(coordinator: coordinator)
        coordinator.background.onPolicyAction = { log($0); production($0) }
        coordinator.background.attach(player: player)
        return (model, player, coordinator)
    }

    private static func postInterruptionBegan() {
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
    }

    private static func postInterruptionEnded(shouldResume: Bool) {
        NotificationCenter.default.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
            AVAudioSessionInterruptionOptionKey: shouldResume ? AVAudioSession.InterruptionOptions.shouldResume.rawValue : 0])
    }

    private static func postUnplug() {
        NotificationCenter.default.post(name: AVAudioSession.routeChangeNotification, object: nil, userInfo: [
            AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue])
    }

    /// N-6(b): headphones out during a failure's re-resolve, end to end from the route-change
    /// notification through `handle` and the production handler. The intent is dropped, so the
    /// replacement stays paused instead of playing through the speaker.
    @Test(.timeLimit(.minutes(1))) func headphonesOutDuringAFailuresReResolveKeepTheReplacementPaused() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (model, player, coordinator) = try await failedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        NotificationCenter.default.post(name: AVAudioSession.routeChangeNotification, object: nil, userInfo: [
            AVAudioSessionRouteChangeReasonKey: AVAudioSession.RouteChangeReason.oldDeviceUnavailable.rawValue])
        try await wait { actions.contains(.pause) }
        #expect(!coordinator.resumeOnNextItem, "the unplug kept the failure's resume intent")

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus == .paused, "the replacement played after the unplug")
    }

    /// N-2 / Codex N3: a call that starts during a failure's re-resolve. The interruption keeps the
    /// resume intent and counts it as playing, so when iOS ends it with `.shouldResume` the
    /// replacement plays.
    @Test(.timeLimit(.minutes(1))) func aCallDuringAFailuresReResolveResumesTheReplacementAfterIt() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (model, player, coordinator) = try await failedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        let center = NotificationCenter.default
        center.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.began.rawValue])
        center.post(name: AVAudioSession.interruptionNotification, object: nil, userInfo: [
            AVAudioSessionInterruptionTypeKey: AVAudioSession.InterruptionType.ended.rawValue,
            AVAudioSessionInterruptionOptionKey: AVAudioSession.InterruptionOptions.shouldResume.rawValue])
        try await wait { actions.contains(.resume) }
        #expect(coordinator.resumeOnNextItem, "the interruption dropped the failure's resume intent")

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus != .paused, "the replacement stayed paused after the call")
    }

    /// Fix r5 N4-1(a): a replacement that lands DURING the interruption is not played then (Siri, an
    /// alarm, another app's audio); the end's `.resume` plays it, because iOS said `.shouldResume`.
    @Test(.timeLimit(.minutes(1))) func aReplacementLandingMidInterruptionWaitsForShouldResume() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (model, player, coordinator) = try await failedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus == .paused, "the replacement played during the interruption")

        Self.postInterruptionEnded(shouldResume: true)
        try await wait { actions.contains(.resume) }
        #expect(player.timeControlStatus != .paused, "the end's .resume did not play the replacement")
    }

    /// Fix r5 N4-1(b): iOS ended the interruption WITHOUT `.shouldResume` (another app took the audio):
    /// the end forwards `.pause`, which drops the intent, so a replacement landing afterwards stays paused.
    @Test(.timeLimit(.minutes(1))) func anInterruptionEndedWithoutShouldResumeKeepsTheReplacementPaused() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (model, player, coordinator) = try await failedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        Self.postInterruptionEnded(shouldResume: false)
        try await wait { actions.contains(.pause) }
        #expect(!coordinator.resumeOnNextItem, "the end without .shouldResume kept the resume intent")

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus == .paused, "the replacement played against iOS's answer")
    }

    /// Fix r5 N4-1(c): headphones out DURING the call. The call's end must not resume through the
    /// speaker (the harm Cubic A6 fixed), whatever `.shouldResume` says.
    @Test(.timeLimit(.minutes(1))) func anUnplugDuringACallKeepsTheReplacementPausedAfterIt() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (model, player, coordinator) = try await failedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        Self.postUnplug()
        try await wait { actions.contains(.pause) }
        Self.postInterruptionEnded(shouldResume: true)
        try await wait { !coordinator.background.interrupted }
        #expect(!actions.contains(.resume), "the call's end resumed after the unplug")

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus == .paused, "the replacement played through the speaker")
    }

    /// Fix r5 N4-1, the missing end: iOS does not promise `.ended` (Apple: treat the user's Play as
    /// one). Once the player is seen playing again, recovery's replacement may resume again, or every
    /// later failure would wait for a tap.
    @Test(.timeLimit(.minutes(1))) func aPlayAfterAnInterruptionThatNeverEndedLetsRecoveryResume() async throws {
        let (model, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        let deadline = ContinuousClock.now + .seconds(10)   // the user plays; no end ever comes
        while coordinator.background.interrupted, ContinuousClock.now < deadline {
            if player.timeControlStatus == .paused, player.currentItem?.status == .readyToPlay {
                await player.seek(to: .zero)
                player.play()
            }
            try await Task.sleep(for: .milliseconds(10))
        }
        #expect(!coordinator.background.interrupted, "a playing player never cleared the interruption")
        PlayerHostTests.postFailure(on: player)
        player.pause()

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus != .paused, "recovery's replacement stayed paused for good")
    }

    /// 1.0.4 I2 (final-check "Outside r5"): an auto-advance whose resolve lands DURING an interruption
    /// (Siri, an alarm, another app's audio) built the next video playing, and its first `.playing`
    /// tick then cleared the interruption too. The update pass's swap now waits for the end, whose
    /// `.resume` plays it when iOS says `.shouldResume`.
    @Test(.timeLimit(.minutes(1))) func anAdvanceLandingMidInterruptionWaitsForShouldResume() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (_, player, coordinator) = try await attachedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        let next = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        _ = PlayerHostView.player(for: .rung2Progressive(PlayerHostTests.resolved(.progressive(url: next, label: "360p"))),
                                  replacing: player, continuesCurrentVideo: false,
                                  interrupted: coordinator.background.interrupted)   // the update pass's read
        #expect(player.timeControlStatus == .paused, "the next video played during the interruption")

        Self.postInterruptionEnded(shouldResume: true)
        try await wait { actions.contains(.resume) }
        #expect(player.timeControlStatus != .paused, "the end's .resume did not play the next video")
    }

    /// 1.0.4 round 3 (review m1): an AUTO-ADVANCE in flight at the begin. The clip has ended, so the
    /// player reads paused and the begin recorded "not playing"; the end's `.shouldResume` then left
    /// the next lecture paused. An advance in flight is playback the user started, so it counts.
    @Test(.timeLimit(.minutes(1))) func anAutoAdvanceInFlightAtTheBeginResumesOnShouldResume() async throws {
        var actions: [PlaybackPolicyAction] = []
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "BPCTests.\(UUID().uuidString)")!)
        settings.safeMode = false
        let resolver = RecordingResolver(.progressive)
        let model = PlayerViewModel(resolver: resolver, settings: settings, args: PlayerArgs(videoId: "a", playlistId: "PL"),
                                    queueSource: FakeQueueSource(pages: [(ids: ["a", "b"], next: nil)]))
        await model.open()                                  // a on .player, b on .prefetch
        let clip = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let player = try #require(PlayerHostView.player(
            for: .rung2Progressive(PlayerHostTests.resolved(.progressive(url: clip, label: "360p"))), replacing: nil))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: false)
        coordinator.observe(item: try #require(player.currentItem), player: player, model: model, isLive: false, playToEnd: .none)
        let production = PlayerHostView.policyHandler(coordinator: coordinator)
        coordinator.background.onPolicyAction = { actions.append($0); production($0) }
        coordinator.background.attach(player: player)
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        player.pause()                                      // the clip ended: an ended item leaves it paused
        resolver.hold("b")
        let advancing = Task { await model.playToEnd() }
        await resolver.waitUntilCalled(count: 3)            // b on .player, held
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        resolver.release(id: "b")
        await advancing.value
        _ = PlayerHostView.player(for: model.state, replacing: player, continuesCurrentVideo: false,
                                  interrupted: coordinator.background.interrupted)   // the advance's update pass
        #expect(player.timeControlStatus == .paused, "the next lecture played during the interruption")

        Self.postInterruptionEnded(shouldResume: true)
        try await wait { actions.contains(.resume) }
        #expect(player.timeControlStatus != .paused, "the end's .shouldResume left the auto-advanced lecture paused")
    }

    /// 1.0.4 round 5 (re-review N1, test a): once the advance has LANDED the next lecture is the
    /// user's again. If they pause it and an interruption comes and goes with `.shouldResume`, it stays
    /// paused (spec R12, rule I5). The advance's flag used to stay set through the prefetch, so the
    /// end resumed a lecture the user had paused.
    @Test(.timeLimit(.minutes(1))) func aLecturePausedAfterTheAdvanceLandedStaysPausedAfterTheInterruption() async throws {
        var actions: [PlaybackPolicyAction] = []
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "BPCTests.\(UUID().uuidString)")!)
        settings.safeMode = false
        let resolver = RecordingResolver(.progressive)
        resolver.prefetchRefusals = ["c"]                   // open() leaves c unprefetched
        let model = PlayerViewModel(resolver: resolver, settings: settings, args: PlayerArgs(videoId: "a", playlistId: "PL"),
                                    queueSource: FakeQueueSource(pages: [(ids: ["a", "b", "c"], next: nil)]))
        await model.open()
        resolver.prefetchRefusals = []
        resolver.hold("c")                                   // after it lands, the advance waits in c's prefetch
        let clip = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let player = try #require(PlayerHostView.player(
            for: .rung2Progressive(PlayerHostTests.resolved(.progressive(url: clip, label: "360p"))), replacing: nil))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: false)
        coordinator.observe(item: try #require(player.currentItem), player: player, model: model, isLive: false, playToEnd: .none)
        let production = PlayerHostView.policyHandler(coordinator: coordinator)
        coordinator.background.onPolicyAction = { actions.append($0); production($0) }
        coordinator.background.attach(player: player)
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        player.pause()                                       // a ended
        let advancing = Task { await model.playToEnd() }
        defer { resolver.release(id: "c") }
        await resolver.waitUntilCalled(count: 5)             // b landed on .player; c's prefetch held
        _ = PlayerHostView.player(for: model.state, replacing: player, continuesCurrentVideo: false,
                                  interrupted: coordinator.background.interrupted)   // the swap: b plays
        player.pause()                                       // the user pauses b
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        Self.postInterruptionEnded(shouldResume: true)
        try await wait { !coordinator.background.interrupted }

        #expect(!actions.contains(.resume), "the end resumed a lecture the user had paused")
        #expect(player.timeControlStatus == .paused)
        resolver.release(id: "c")
        await advancing.value
    }

    /// 2026-11-03 review P1: an interruption (a call, Siri) began while this lecture played, and a
    /// newer open paused it before the end. The end's `.shouldResume` played it under the opened one.
    @Test(.timeLimit(.minutes(1))) func anInterruptionEndingAfterANewerOpenLeavesItPaused() async throws {
        var actions: [PlaybackPolicyAction] = []
        let (_, player, coordinator) = try await attachedWhilePlaying { actions.append($0) }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        let players = NSHashTable<AVPlayer>.weakObjects()
        players.add(player)

        Router(players: players).push(.player(PlayerArgs(videoId: "nhaGO__rxHQ")))
        let (opened, server) = try await PlayerHostTests.openedPlayer()
        defer { opened.pause(); server.stop() }
        Self.postInterruptionEnded(shouldResume: true)
        try await wait { actions.contains(.resume) }

        #expect(opened.rate > 0, "the opened video plays")
        let playing = [player, opened].filter { $0.rate > 0 }.count
        #expect(playing == 1, "the interruption's end played the covered lecture")
    }

    /// 1.0.4 round 5 (re-review N2): the user's Play is the route PUSH (a tap or a deep link), not
    /// the screen's mount.
    @Test(.timeLimit(.minutes(1))) func aPlayerRoutePushIsTheUsersPlay() async throws {
        let (_, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { BackgroundPlaybackController.sessionInterrupted }

        Router(players: NSHashTable<AVPlayer>.weakObjects()).push(.player(PlayerArgs(videoId: "pushed-1")))

        #expect(!BackgroundPlaybackController.sessionInterrupted, "a player push left the next start gated")
    }

    /// 1.0.4 round 5 (re-review N2): an iPad Split View or Stage Manager resize across 600 pt
    /// re-creates a pushed screen's state and re-runs its `.task`. That is a gesture, not a Play: a
    /// player screen mounting (here, rebuilt in a window) leaves the session's interruption alone.
    /// An offline open, so the mount resolves with no network.
    @Test(.timeLimit(.minutes(1))) func aRebuiltPlayerScreenLeavesTheSessionInterruptionAlone() async throws {
        let (_, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { BackgroundPlaybackController.sessionInterrupted }

        var args = PlayerArgs(videoId: "rebuilt-1")
        args.offlineItemId = "no-such-row"
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UIHostingController(rootView: PlayerScreen(args: args))
        window.isHidden = false
        defer { window.isHidden = true }
        let deadline = ContinuousClock.now + .seconds(3)    // the mount's `.task` creates the model
        while BackgroundPlaybackController.sessionInterrupted, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(20))
        }

        #expect(BackgroundPlaybackController.sessionInterrupted, "mounting the player screen counted as the user's Play")
    }

    /// 1.0.4 round 3 (review m2, ruling): a user's open is the user's Play. Music or Spotify started
    /// from Control Center sends a begin and never an end, so without this every later open waited
    /// for a tap. A player/Shorts route push, an Up Next tap and Retry call it (round 5: the push,
    /// not the screen's mount); an automatic start (an advance, an auto-skip's rebuild) never does.
    @Test(.timeLimit(.minutes(1))) func aUsersOpenEndsTheSessionInterruption() async throws {
        let (_, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { BackgroundPlaybackController.sessionInterrupted }

        BackgroundPlaybackController.userAskedToPlay()

        #expect(!BackgroundPlaybackController.sessionInterrupted)
    }

    /// 1.0.4 round 4 (ruling): Retry on the player's error card is a user tap, so it is the user's
    /// Play, the same as an open: both screens' cards call `PlayerScreen.retryTapped`.
    @Test(.timeLimit(.minutes(1))) func aRetryTapEndsTheSessionInterruption() async throws {
        let (model, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { BackgroundPlaybackController.sessionInterrupted }

        PlayerScreen.retryTapped(model)

        #expect(!BackgroundPlaybackController.sessionInterrupted, "a Retry tap left the next start gated")
    }

    /// 1.0.4 round 2 (the I2 gap): an auto-skip past an error state dismantles the host and builds a
    /// FRESH one, whose controller never saw the begin, and that player started mid-interruption.
    /// The session's interruption is one per process: a player built while it lasts does not start,
    /// a controller attached meanwhile knows it, an end that lands with no player mounted still clears
    /// it, and a player built after that starts again.
    @Test(.timeLimit(.minutes(1))) func aPlayerBuiltMidInterruptionWaitsThoughNoLiveHostSawTheBegin() async throws {
        let (_, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        coordinator.stopObserving()
        coordinator.background.detach()                    // the error state dismantles the host

        let next = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let state = StreamState.rung2Progressive(PlayerHostTests.resolved(.progressive(url: next, label: "360p")))
        let rebuilt = try #require(PlayerHostView.player(for: state, replacing: nil,
                                                         interrupted: BackgroundPlaybackController.sessionInterrupted))
        defer { rebuilt.pause() }
        #expect(rebuilt.timeControlStatus == .paused, "the rebuilt host's player started mid-interruption")
        let fresh = BackgroundPlaybackController(backgroundPlay: false)
        fresh.attach(player: rebuilt)
        #expect(fresh.interrupted, "a controller attached mid-interruption did not know")
        fresh.detach()

        Self.postInterruptionEnded(shouldResume: false)    // no player mounted
        try await wait { !BackgroundPlaybackController.sessionInterrupted }
        let after = try #require(PlayerHostView.player(for: state, replacing: nil,
                                                       interrupted: BackgroundPlaybackController.sessionInterrupted))
        defer { after.pause() }
        #expect(after.timeControlStatus != .paused, "a player built after the end did not start")
    }

    /// Stage 9 I-C9b (Cubic P2): a call starts during an auto-advance, and an auto-skip rebuilds the host. Its FRESH
    /// controller never saw the begin, so it takes the playback intent from `resumeIntentPending` at attach, and the call's
    /// end with `.shouldResume` resumes the playlist instead of leaving it stopped.
    @Test(.timeLimit(.minutes(1))) func aControllerAttachedMidInterruptionResumesOnShouldResume() async throws {
        let (_, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { player.pause() }
        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        coordinator.stopObserving()
        coordinator.background.detach()                    // the auto-skip dismantles the host

        let next = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let rebuilt = AVPlayer(url: next)                  // paused: the advance's next lecture, not yet played
        defer { rebuilt.pause() }
        let fresh = BackgroundPlaybackController(backgroundPlay: false)
        var actions: [PlaybackPolicyAction] = []
        fresh.onPolicyAction = { actions.append($0) }
        fresh.resumeIntentPending = { true }               // the advance in flight counts as playing (round 3, m1)
        fresh.attach(player: rebuilt)
        defer { fresh.detach() }
        #expect(fresh.interrupted)

        Self.postInterruptionEnded(shouldResume: true)
        try await wait { !fresh.interrupted }

        #expect(actions.contains(.resume), "the call's end left the rebuilt host's playlist stopped")
    }

    /// 1.0.4 I1 (final-check N5-1): the user's Play after an interruption that never ended can sit
    /// WAITING and fail (a 403, say) before any tick reads `.playing`. Wanting to play is that Play,
    /// so it ends the interruption too, and recovery's replacement resumes instead of waiting for a tap.
    @Test(.timeLimit(.minutes(1))) func aPlayThatFailsBeforeEverPlayingStillLetsRecoveryResume() async throws {
        let server = try StallingServer(body: Data(), withheldFrom: 0)   // accepts, never answers
        await server.start()
        defer { server.stop() }
        let (model, player, coordinator) = try await attachedWhilePlaying { _ in }
        defer { coordinator.stopObserving(); coordinator.background.detach(); player.pause() }

        Self.postInterruptionBegan()
        defer { Self.postInterruptionEnded(shouldResume: false) }   // never leak the session flag (m7)
        try await wait { coordinator.background.interrupted }
        try PlayerHostTests.swap(player, to: server.url, coordinator, model)   // paused: nothing plays it
        let tapped = Date()
        player.play()                                      // the user; no end ever comes
        let deadline = ContinuousClock.now + .seconds(10)
        while (coordinator.lastPlayingAt ?? .distantPast) <= tapped, ContinuousClock.now < deadline {
            try await Task.sleep(for: .milliseconds(10))
        }
        try #require((coordinator.lastPlayingAt ?? .distantPast) > tapped, "the sampler never saw the Play")
        try #require(player.timeControlStatus == .waitingToPlayAtSpecifiedRate, "not a real wait")
        PlayerHostTests.postFailure(on: player)            // it dies before its first frame
        player.pause()

        try PlayerHostTests.swap(player, to: PlayerHostTests.unanswered, coordinator, model)
        #expect(player.timeControlStatus != .paused, "recovery's replacement waited for a tap")
    }
}
