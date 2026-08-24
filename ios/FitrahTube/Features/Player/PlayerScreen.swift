import Foundation
import InnerTubeKit
import SwiftUI

/// The SwiftUI screen for `Route.player(PlayerArgs)` (spec §10). Phase-1 VM-creation pattern:
/// `@Environment(\.container)` read inside `.task`, `@State private var model: PlayerViewModel?`
/// held across body re-renders (`FavoritesView`/`SearchView`).
struct PlayerScreen: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @State private var model: PlayerViewModel?

    var body: some View {
        Group {
            if let model {
                stateView(model.state)
            } else {
                ProgressView()
            }
        }
        .task {
            guard model == nil else { return }
            let vm = PlayerViewModel(resolver: Self.resolver(container: container), catalog: container.catalog,
                                      favorites: container.favorites, settings: container.settings, args: args)
            model = vm
            await vm.open()
        }
    }

    // ponytail: Task 9 replaces every branch here with the real per-state UI (loading skeleton,
    // cooldown countdown, contentUnavailable/error copy + retry, the rung-2 pill, recoveryExhausted
    // escape hatch). This task only needs *a* screen to route `.player(args)` to and proof the host
    // decodes a real frame, so every non-playable state gets one shared spinner or the raw message
    // key as placeholder text -- not real, localized copy.
    @ViewBuilder
    private func stateView(_ state: StreamState) -> some View {
        switch state {
        case .ready, .rung2Progressive:
            PlayerHostView(state: state)
                .ignoresSafeArea()
        case .error(let messageKey):
            Text(messageKey)
        case .contentUnavailable:
            Text(String(localized: "player_error_message"))
        case .idle, .loading, .cooldown, .recoveryExhausted:
            ProgressView()
        }
    }

    /// `-fitrah-fake-player`: the UI-test/screenshot hook -- swaps the real InnerTubeKit-backed
    /// resolver for `FixturePlayerResolver` (below), which resolves to the bundled local fixture
    /// clip, so the screenshot rig never touches the network. Compiled out of Release with every
    /// other debug hook in this app (`FitrahTubeApp.swift`).
    private static func resolver(container: AppContainer) -> any StreamResolving {
        #if DEBUG
        if ProcessInfo.processInfo.arguments.contains("-fitrah-fake-player") {
            return FixturePlayerResolver()
        }
        #endif
        return LiveStreamResolver(resolver: container.resolver)
    }
}

#if DEBUG
/// Resolves every video id to `player-fixture.mp4` (`ios/FitrahTube/Resources/`), a 2s local clip
/// generated with AVFoundation for exactly this purpose (no ffmpeg on the build machine, no network
/// dependency in the UI-test rig). `.progressive`, not `.hls`: the fixture is a plain local file,
/// which AVPlayer plays natively without an HLS manifest -- `PlayerScreen` routes `.rung2Progressive`
/// through the same `PlayerHostView` as `.ready`, so this still exercises the real playback path.
private struct FixturePlayerResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
        guard let url = Bundle.main.url(forResource: "player-fixture", withExtension: "mp4") else {
            throw ExtractionError.transport("player-fixture.mp4 missing from the app bundle")
        }
        return Resolved(stream: .progressive(url: url, label: "360p"), client: .visionos,
                         userAgent: "FitrahTube/DebugFixture", resolvedAt: Date(), expiresAt: nil)
    }
}

#Preview {
    PlayerScreen(args: PlayerArgs(videoId: "preview"))
        .environment(\.container, .sharedFake)
}
#endif
