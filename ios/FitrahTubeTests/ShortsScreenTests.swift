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
        // `Purpose` is not `Equatable` (InnerTubeKit.Models.swift:99, and this plan changes no package
        // source), so the lane is matched rather than compared. This is also the .prefetch assertion:
        // it fails on any call that is not `.player`.
        #expect(resolver.calls.allSatisfy { if case .player = $0.purpose { true } else { false } })
    }

    @Test func shortsScreenExistsAndTakesPlayerArgs() {
        // Compile-time pin: `MainShellView` routes `.shorts(PlayerArgs)` to this screen.
        _ = ShortsScreen(args: PlayerArgs(videoId: "short1"))
    }
}
