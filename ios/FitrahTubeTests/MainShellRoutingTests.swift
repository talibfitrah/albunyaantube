import Foundation
import SwiftUI
import Testing
@testable import FitrahTube

/// `MainShellView.destination(for:)` is an exhaustive switch since Plan C Task 5, so a missing arm is
/// a compile error; these tests pin that each Plan C route resolves to its real screen. They walk the
/// `_ConditionalContent` chain the `@ViewBuilder` switch produces down to the leaf view that was
/// actually chosen for the route.
@Suite(.perTest)
struct MainShellRoutingTests {
    private func leafTypeName(for route: Route) -> String {
        var mirror = Mirror(reflecting: MainShellView().destination(for: route))
        // `_ConditionalContent<A, B>` stores `.trueContent(A)` / `.falseContent(B)`; descend until
        // the subject is no longer one of them.
        while String(describing: mirror.subjectType).hasPrefix("_ConditionalContent"),
              let storage = mirror.children.first(where: { $0.label == "storage" }) {
            let payload = Mirror(reflecting: storage.value)
            guard let inner = payload.children.first else { break }
            mirror = Mirror(reflecting: inner.value)
        }
        return String(describing: mirror.subjectType)
    }

    @Test func thePlaylistRouteRendersTheRealScreen() {
        let leaf = leafTypeName(for: .playlist(id: "PL1", title: "T", category: nil, count: 3))
        #expect(leaf == "PlaylistDetailScreen")
    }

    @Test func theChannelRouteRendersTheRealScreen() {
        let leaf = leafTypeName(for: .channel(id: "UC1", name: nil, avatarURL: nil))
        #expect(leaf == "ChannelDetailScreen")
    }

    /// Phase 3 Task 6: the ONE new `Route` case this plan adds.
    @Test func theOfflineRouteRendersTheSavedScreen() {
        #expect(leafTypeName(for: .offline) == "SavedScreen")
    }

    /// Phase 4 Task 10: the ONE new `Route` case this task adds — it lands WITH its screen, which
    /// is what this arm pins.
    @Test func theSignInRouteRendersTheRealScreen() {
        #expect(leafTypeName(for: .signIn) == "SignInScreen")
    }

    /// Phase 4 Task 11: the ONE new `Route` case this task adds — it lands WITH its screen, which
    /// is what this arm pins.
    @Test func theEmailVerificationRouteRendersTheRealScreen() {
        #expect(leafTypeName(for: .emailVerification) == "EmailVerificationScreen")
    }

    /// Phase 4 Task 12: the TWO new `Route` cases this task adds — both land WITH their screens.
    @Test func theProfileBootstrapRouteRendersTheRealScreen() {
        #expect(leafTypeName(for: .profileBootstrap) == "ProfileBootstrapScreen")
    }

    @Test func theAgeIneligibleRouteRendersTheRealScreen() {
        #expect(leafTypeName(for: .ageIneligible) == "AgeIneligibleScreen")
    }

    /// T0-1: `railStacks` is the only publisher of `\.tabIsSelected` — the compact `TabView` sets
    /// nothing, and neither does a sheet, a preview or a test host. So the DEFAULT is what every
    /// one of those readers gets, and it has to mean "you are on screen": `false` there would have
    /// `PlayerScreen`'s `.onChange` arm reconcile `.disappear` on a player nobody hid, releasing
    /// its cast stamp and refusing to resume the phone on hand-back.
    @Test func anUnpublishedTabVisibilitySignalReadsAsOnScreen() {
        #expect(EnvironmentValues().tabIsSelected)
    }

    @Test func theWalkerReachesADistinctLeafPerRoute() {
        // Guards the helper: Plan C Task 5 gave the last route its screen, so no placeholder route is
        // left to pin; two different routes resolving to two different leaves proves the walker
        // still descends to the chosen branch rather than stopping at the outer conditional.
        #expect(leafTypeName(for: .settings) == "SettingsView")
        #expect(leafTypeName(for: .about) == "AboutView")
    }
}
