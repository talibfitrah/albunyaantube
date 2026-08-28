import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// B4 Task 2: the Shorts surface reuses `PlayerViewModel` unchanged, so what this suite pins is the
/// one contract the screen adds on top -- a single short resolves exactly itself.
@Suite(.perTest)
struct ShortsScreenTests {
    private func makeSettings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "ShortsScreenTests.\(UUID().uuidString)")!)
    }

    @Test func shortsResolveTheCurrentItemAndNothingElse() async {
        // Plan 6.2 ("Shorts: resolve the current item only") + CF-B2-2 (.prefetch is B5's lane).
        // Opening the screen's view model must produce EXACTLY ONE resolve -- no neighbour, no
        // speculative warm-up -- on the interactive lane, uncached-forcing nothing.
        let resolver = RecordingResolver(.hls)
        let model = PlayerViewModel(resolver: resolver, settings: makeSettings(),
                                    args: PlayerArgs(videoId: "short1"))
        await model.open()
        #expect(resolver.calls.count == 1)
        #expect(resolver.calls[0].forceRefresh == false)
        #expect(resolver.calls[0].kind == .player)
        #expect(resolver.calls[0].purpose == .player)
        // Ceiling (M7, fix round 2): this drives `PlayerViewModel.open()` directly. A prefetch added
        // in `ShortsScreen.task` (outside the view model) would NOT be caught here.
    }

    /// C1 (fix round 2): `.embed` online must reach `EmbedRungView`, never `PlayerStateView` --
    /// `PlayerStateCopy.map` `preconditionFailure`s on an online `.embed`. Offline it is the
    /// offline card, the same rule `PlayerScreen` follows.
    @Test func embedRoutesToTheEmbedRungOnlineAndTheStatusCardOffline() {
        let resolved = Resolved(stream: .embed(videoId: "xc7keR2piUM"), client: .web, userAgent: "",
                                resolvedAt: Date(), expiresAt: nil)
        #expect(ShortsScreen.branch(for: .embed(resolved), isOnline: true) == .embed)
        #expect(ShortsScreen.branch(for: .embed(resolved), isOnline: false) == .status)
        #expect(ShortsScreen.branch(for: .ready(resolved), isOnline: true) == .player)
        #expect(ShortsScreen.branch(for: .loading, isOnline: true) == .status)
    }
}
