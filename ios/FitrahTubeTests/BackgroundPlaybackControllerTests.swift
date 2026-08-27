import AVFoundation
import Foundation
import InnerTubeKit
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
    private static func fixture() throws -> (state: StreamState, video: URL, audio: URL) {
        let video = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        let audio = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("fitrah-audio-only.m4a")
        let resolved = Resolved(stream: .hls(url: video, isLive: false, audioOnlyURL: audio, captionTracks: []),
                                client: .visionos, userAgent: "TestUA", resolvedAt: Date(), expiresAt: nil)
        return (.ready(resolved), video, audio)
    }

    /// The observers are registered with `queue: .main`, so delivery is a run-loop hop away rather
    /// than synchronous with `post`. 2 s ceiling, and every one of these settles in one hop.
    private func wait(until condition: () -> Bool) async throws {
        for _ in 0..<100 {
            if condition() { return }
            try await Task.sleep(for: .milliseconds(20))
        }
    }

    private static func assetURL(_ player: AVPlayer) -> URL? {
        (player.currentItem?.asset as? AVURLAsset)?.url
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

    @Test func foregroundRestoresTheVideoURLOnTheSamePlayer() async throws {
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
        #expect(Self.assetURL(player) == fixture.video)
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
}
