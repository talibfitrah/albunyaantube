import AVFoundation
import AVKit
import Foundation
import InnerTubeKit
import MediaPlayer
import Testing
import UIKit
@testable import FitrahTube

/// T2-1 (Task 2 review): `AudioSessionPolicyTests` covers the pure decision table; this covers the
/// other half -- that a real lifecycle NOTIFICATION reaches `handle()`, and that the action it
/// produces actually moves a real `AVPlayer` through `PlayerHostView`'s wiring (fix round 1, C2).
///
/// `.serialized`: every test here posts a PROCESS-WIDE notification, so two running at once would
/// each drive the other's controller.
@Suite(.serialized) struct BackgroundPlaybackControllerTests {
    /// A real, playable local item -- the same 2 s clip the screenshot rig plays (the unit target's
    /// host IS the app, see `SmokeTests`), plus a second LOCAL url standing in for the itag 140
    /// rendition, so the swap is observable as an asset-url change with no network traffic at all.
    private static func fixture() throws -> (state: StreamState, resolved: Resolved, video: URL, audio: URL) {
        let video = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let audio = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("fitrah-audio-only.m4a")
        let resolved = Resolved(stream: .hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: []),
                                client: .visionos, userAgent: "TestUA", resolvedAt: Date(), expiresAt: nil)
        return (.ready(resolved), resolved, video, audio)
    }

    /// Just enough `StreamResolving` to put a real `PlayerViewModel` into `.ready(fixture)` --
    /// `state` is `private(set)`, so `open()` is the only door in.
    private struct OneShotResolver: StreamResolving {
        let resolved: Resolved
        init(_ resolved: Resolved) { self.resolved = resolved }
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                     sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool) async throws -> Resolved { resolved }
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

    @Test func backgroundingSwapsTheLivePlayerToTheAudioOnlyURL() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let videoItem = player.currentItem
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { [weak player] action in
            actions.append(action)
            PlayerHostView.applyPolicyAction(action, state: fixture.state, player: player, model: nil)
        }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }

        // `.none` is filtered out: activating the audio session on `attach` can emit a route change,
        // which the policy answers with `.none` -- real, but not this test's subject.
        #expect(actions.filter { $0 != .none } == [.swapToAudioOnly])
        // Identity: the item swapped IN PLACE on the very `AVPlayer` this test still holds -- a
        // handler that built a second player would leave this one on the video url.
        #expect(Self.assetURL(player) == fixture.audio)
        #expect(player.currentItem !== videoItem)
    }

    /// IMP-1 (final review) changed this test's subject. It used to assert the video URL was back
    /// on the player the moment `.restoreVideo` was emitted -- but the foreground policy runs on a
    /// 2 s deadline with a TTL refresh possibly still in flight, so a synchronous restore here plus
    /// the foreground `updateUIViewController` pass is TWO `replaceCurrentItem` calls, the second
    /// of which restarts playback from 0. What this pins now is the half `applyPolicyAction` still
    /// owns: the action is emitted, the auto-swap flag is cleared, and the live player is left
    /// exactly where it is for the update pass to restore once, from the freshest `Resolved`. That
    /// pass's own behaviour is `PlayerHostTests.togglingAudioOnlyReplacesTheItemAndKeepsThePlayer`.
    @Test func foregroundEmitsRestoreVideoAndLeavesTheSwapToTheUpdatePass() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { [weak player] action in
            actions.append(action)
            PlayerHostView.applyPolicyAction(action, state: fixture.state, player: player, model: nil)
        }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { actions.contains(.restoreVideo) }

        #expect(actions.filter { $0 != .none } == [.swapToAudioOnly, .restoreVideo])
        #expect(controller.autoSwappedToAudioOnly == false)
        #expect(Self.assetURL(player) == fixture.audio)   // untouched: the update pass owns the restore
    }

    @Test func backgroundingLeavesThePlayerAloneWhenTheStreamHasNoAudioRendition() async throws {
        let video = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let resolved = Resolved(stream: .hls(url: video, isLive: false, audioOnlyURL: nil, captionTracks: []),
                                client: .visionos, userAgent: "TestUA", resolvedAt: Date(), expiresAt: nil)
        let state = StreamState.ready(resolved)
        let player = try #require(PlayerHostView.player(for: state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = false   // gated at the source (`PlaybackPolicyContext`)
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { [weak player] action in
            actions.append(action)
            PlayerHostView.applyPolicyAction(action, state: state, player: player, model: nil)
        }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { !actions.isEmpty }

        #expect(actions.filter { $0 != .none }.isEmpty)
        #expect(Self.assetURL(player) == video)
        #expect(controller.autoSwappedToAudioOnly == false)
    }

    /// `attach`'s identity guard: `updateUIViewController` hands the same `AVPlayer` back on nearly
    /// every pass, so a second registration would double every lifecycle event.
    @Test func attachingTheSamePlayerTwiceRegistersOneObserverSet() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { action in actions.append(action) }
        controller.attach(player: player)
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        // A duplicate registration delivers on the same run-loop hop, so it would already be here;
        // the extra settle is only insurance against a slow simulator.
        try await Task.sleep(for: .milliseconds(200))

        #expect(actions.filter { $0 == .swapToAudioOnly }.count == 1)
    }

    // MARK: - IMP-2 (final review): auto-PiP racing the background swap

    /// `didEnterBackgroundNotification` and AVKit's auto-PiP transition have no guaranteed order.
    /// When the background half wins, the item is already the itag 140 audio rendition by the time
    /// AVKit opens the floating window -- a video window with no video track, i.e. a black box.
    /// `willStartPictureInPicture` therefore UNDOES the automatic swap it finds.
    ///
    /// Fix round 2: SYNCHRONOUSLY. IMP-1 moved the ordinary foreground `.restoreVideo` onto the
    /// SwiftUI update pass (one replace, against the freshest `Resolved`) -- but this path runs
    /// inside the home-swipe transition, where that pass may never get to run before the app
    /// suspends, which is exactly the hazard Task 3's C2 fixed for the swap direction. A deferred
    /// restore here means AVKit opens the window over the itag 140 item: a black box until the
    /// user comes back. So the undo puts the video url back on the live player itself.
    @Test func startingPictureInPictureUndoesAnAutomaticBackgroundSwap() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let controller = coordinator.background
        controller.audioOnlyAvailable = true
        let videoItem = try #require(player.currentItem)
        coordinator.observe(item: videoItem, player: player, model: nil, isLive: false, playToEnd: .none)
        defer { coordinator.stopObserving() }
        var actions: [PlaybackPolicyAction] = []
        controller.onPolicyAction = { [weak player, weak coordinator] action in
            actions.append(action)
            PlayerHostView.applyPolicyAction(action, state: fixture.state, player: player, model: nil,
                                             coordinator: coordinator)
        }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        #expect(Self.assetURL(player) == fixture.audio)
        let audioItem = try #require(player.currentItem)

        coordinator.playerViewControllerWillStartPictureInPicture(AVPlayerViewController())

        // The window AVKit is about to open must have a video track to draw RIGHT NOW.
        #expect(Self.assetURL(player) == fixture.video)
        #expect(player.currentItem !== audioItem)
        // MIN-4, same as the swap direction: the recovery observers are per-item, so a synchronous
        // replace that left them on the outgoing item would silently disarm the recovery ladder.
        #expect(coordinator.observedItem === player.currentItem)
        #expect(actions.filter { $0 != .none } == [.swapToAudioOnly, .restoreVideoNow])
        #expect(controller.autoSwappedToAudioOnly == false)
    }

    /// Fix round 2, the wiring itself: every other test here builds its own `onPolicyAction`
    /// closure by hand, which is exactly how the app shipped an `applyPolicyAction` call that
    /// dropped the `coordinator:` argument -- MIN-4's observer re-arm never ran outside the tests
    /// that passed one deliberately. This one installs the PRODUCTION handler
    /// (`PlayerHostView.policyHandler`) and drives it with a real notification.
    @Test func theProductionPolicyHandlerReArmsTheRecoveryObserversOnTheSwappedItem() async throws {
        let fixture = try Self.fixture()
        let model = PlayerViewModel(resolver: OneShotResolver(fixture.resolved),
                                    settings: UserDefaultsSettingsStore(
                                        defaults: UserDefaults(suiteName: "BGPolicyHandler.\(UUID().uuidString)")!),
                                    args: PlayerArgs(videoId: "abcdefghijk", channelId: "ch1"))
        await model.open()
        #expect(model.state == fixture.state)
        let player = try #require(PlayerHostView.player(for: model.state, replacing: nil, audioOnly: false))
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        let videoItem = try #require(player.currentItem)
        coordinator.observe(item: videoItem, player: player, model: model, isLive: false, playToEnd: .none)
        defer { coordinator.stopObserving() }
        let controller = coordinator.background
        controller.audioOnlyAvailable = true
        controller.onPolicyAction = PlayerHostView.policyHandler(model: model, player: player,
                                                                 coordinator: coordinator)
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { model.audioOnly }

        #expect(Self.assetURL(player) == fixture.audio)
        #expect(player.currentItem !== videoItem)
        #expect(coordinator.observedItem === player.currentItem)
    }

    /// The other half: the ordinary case (PiP started from the transport button with nothing
    /// swapped) must not emit a spurious `.restoreVideo` -- that would rebuild the item under the
    /// window AVKit is opening, which is the very defect this undo exists to prevent.
    @Test func startingPictureInPictureWithNothingSwappedEmitsNoAction() {
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: true)
        var actions: [PlaybackPolicyAction] = []
        coordinator.background.onPolicyAction = { actions.append($0) }

        coordinator.playerViewControllerWillStartPictureInPicture(AVPlayerViewController())

        #expect(actions.isEmpty)
        #expect(coordinator.background.pictureInPictureActive)
    }

    // MARK: - CF-B1-3 ordering (Task 6)

    /// The pre-emptive re-resolve must SETTLE before the lifecycle policy runs, or `.restoreVideo`
    /// rebuilds the item from the expiring URL the app backgrounded with and the fresh one lands a
    /// second re-buffer later (Task 3's swap reads the state the hook just refreshed).
    @Test func theForegroundReResolveRunsBeforeTheVideoRestore() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        var log: [String] = []
        controller.onWillEnterForeground = { log.append("re-resolve") }
        controller.onPolicyAction = { [weak player] action in
            log.append("\(action)")
            PlayerHostView.applyPolicyAction(action, state: fixture.state, player: player, model: nil)
        }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { log.contains("\(PlaybackPolicyAction.swapToAudioOnly)") }
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { log.contains("\(PlaybackPolicyAction.restoreVideo)") }

        let restore = try #require(log.firstIndex(of: "\(PlaybackPolicyAction.restoreVideo)"))
        let reResolve = try #require(log.firstIndex(of: "re-resolve"))
        #expect(reResolve < restore)
    }

    /// Task 5: a live PiP window is playing the very item a re-resolve would replace, so the
    /// window would re-buffer or blank. The unexpired stream keeps playing instead; the refresh
    /// gets its next chance on the following foreground, once PiP has stopped.
    @Test func theForegroundReResolveIsSuppressedWhileAPiPWindowIsLive() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        var reResolves = 0
        var actions: [PlaybackPolicyAction] = []
        controller.onWillEnterForeground = { reResolves += 1 }
        controller.onPolicyAction = { actions.append($0) }
        controller.attach(player: player)
        defer { controller.detach() }
        controller.pictureInPictureActive = true

        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { !actions.isEmpty }
        try await Task.sleep(for: .milliseconds(200))   // a deferred hook would have fired by now

        #expect(reResolves == 0)
    }

    // MARK: - Foreground refresh races (fix round 1)

    /// A `onWillEnterForeground` hook the test can hold open, so the window between "the refresh
    /// started" and "the refresh finished" is observable. Same 1 ms poll as `RecordingResolver`'s
    /// gate -- a continuation registry would be more code than the whole helper.
    private final class HeldHook: @unchecked Sendable {
        private let lock = NSLock()
        private var _entered = false
        private var _released = false
        var entered: Bool { lock.withLock { _entered } }
        func release() { lock.withLock { _released = true } }
        func wait() async {
            lock.withLock { _entered = true }
            while !lock.withLock({ _released }) { try? await Task.sleep(for: .milliseconds(1)) }
        }
    }

    /// I1: the awaited hook outlives the foreground transition. If the user re-backgrounds while a
    /// refresh is still in flight, the `.willEnterForeground` that lands afterwards is stale -- and
    /// `.restoreVideo` puts the VIDEO url back on a player that is now in the background, pulling
    /// video segments over the network for a screen nobody is looking at. The policy cannot catch
    /// this on its own: re-backgrounding answers `.none` (the user's audio-only flag is already
    /// set), so `autoSwappedToAudioOnly` stays true and the stale restore looks legitimate.
    @Test func aStaleForegroundRestoreIsDroppedWhenTheAppReBackgrounds() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        let hook = HeldHook()
        var actions: [PlaybackPolicyAction] = []
        controller.onWillEnterForeground = { await hook.wait() }
        controller.onPolicyAction = { actions.append($0) }
        controller.attach(player: player)
        defer { controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { hook.entered }
        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        hook.release()
        try await Task.sleep(for: .milliseconds(200))   // a stale restore would have landed by now

        #expect(!actions.contains(.restoreVideo))
    }

    /// Same race, the other trigger: the host is dismantled mid-refresh. A controller that has
    /// detached owns no player and no audio session; running the lifecycle policy afterwards
    /// republishes decisions for a player that is gone.
    @Test func aForegroundRestoreIsDroppedWhenTheHostDetachesMidRefresh() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        let hook = HeldHook()
        var actions: [PlaybackPolicyAction] = []
        controller.onWillEnterForeground = { await hook.wait() }
        controller.onPolicyAction = { actions.append($0) }
        controller.attach(player: player)

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { hook.entered }
        controller.detach()
        hook.release()
        try await Task.sleep(for: .milliseconds(200))

        #expect(!actions.contains(.restoreVideo))
    }

    /// I2: the hook walks the resolver ladder, whose per-rung transport timeout is 15 s. Waiting it
    /// out would leave the returning user staring at an audio-only player for a quarter of a minute.
    /// The policy runs on a deadline instead; a refresh that lands late still swaps through the
    /// normal `updateUIViewController` path.
    @Test func theLifecyclePolicyStillRunsWhenTheRefreshOutlivesItsDeadline() async throws {
        let fixture = try Self.fixture()
        let player = try #require(PlayerHostView.player(for: fixture.state, replacing: nil, audioOnly: false))
        let controller = BackgroundPlaybackController(backgroundPlay: true)
        controller.audioOnlyAvailable = true
        controller.foregroundRefreshDeadline = .milliseconds(50)
        let hook = HeldHook()   // never released until the assertions are done
        var actions: [PlaybackPolicyAction] = []
        controller.onWillEnterForeground = { await hook.wait() }
        controller.onPolicyAction = { actions.append($0) }
        controller.attach(player: player)
        defer { hook.release(); controller.detach() }

        NotificationCenter.default.post(name: UIApplication.didEnterBackgroundNotification, object: nil)
        try await wait { actions.contains(.swapToAudioOnly) }
        NotificationCenter.default.post(name: UIApplication.willEnterForegroundNotification, object: nil)
        try await wait { actions.contains(.restoreVideo) }

        #expect(actions.contains(.restoreVideo))
        #expect(hook.entered)   // the deadline fired because the hook was still running, not skipped
    }
}
