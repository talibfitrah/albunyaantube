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
    /// Task 25 moved the walk itself to `Support/TestDoubles.swift` — `MySubmissionsScreen`'s four
    /// state arms need the same descent, and a second copy is the wave-2 W9 lesson again.
    private func leaf(for route: Route) -> String {
        leafTypeName(of: MainShellView().destination(for: route))
    }

    @Test func thePlaylistRouteRendersTheRealScreen() {
        #expect(leaf(for: .playlist(id: "PL1", title: "T", category: nil, count: 3)) == "PlaylistDetailScreen")
    }

    @Test func theChannelRouteRendersTheRealScreen() {
        #expect(leaf(for: .channel(id: "UC1", name: nil, avatarURL: nil)) == "ChannelDetailScreen")
    }

    /// Phase 3 Task 6: the ONE new `Route` case this plan adds.
    @Test func theOfflineRouteRendersTheSavedScreen() {
        #expect(leaf(for: .offline) == "SavedScreen")
    }

    /// Phase 4 Task 10: the ONE new `Route` case this task adds — it lands WITH its screen, which
    /// is what this arm pins.
    @Test func theSignInRouteRendersTheRealScreen() {
        #expect(leaf(for: .signIn) == "SignInScreen")
    }

    // The `.emailVerification` / `.profileBootstrap` / `.ageIneligible` rows went with their
    // `Route` cases (R7-P3 bloat): nothing pushed any of the three, and all three screens are
    // reached from `RootView` instead — the first two as `SplashDestination`s
    // (`RootViewDestinationTests.everySplashDestinationRendersItsScreen`), the third as the full
    // screen cover R7-P1 #3 presents on `AccountSession.isAgeIneligible`.

    /// Phase 4 Task 17: the ONE new `Route` case this task adds — it lands WITH its screen, and
    /// with the Me kebab row that pushes it (`MeKebabItem.landed`).
    @Test func theProfileRouteRendersTheRealScreen() {
        #expect(leaf(for: .profile) == "ProfileScreen")
    }

    /// Phase 4 Task 25: the ONE new `Route` case this task adds — it lands WITH its screen, and
    /// with the moderator-only Me kebab row that pushes it (`MeKebabItem.landed`, ruling C4).
    @Test func theMySubmissionsRouteRendersTheRealScreen() {
        #expect(leaf(for: .mySubmissions) == "MySubmissionsScreen")
    }

    /// Phase 4 Task 27: the ONE new `Route` case this task adds — it lands WITH its screen, and
    /// with the second half of ruling C4's moderator-only kebab pair (`MeKebabItem.landed`).
    @Test func theSuggestContentRouteRendersTheRealScreen() {
        #expect(leaf(for: .suggestContent) == "SuggestContentScreen")
    }

    /// Phase 4 Task 29: the ONE new `Route` case this task adds — it lands WITH its screen and with
    /// the LAST Me kebab row (`MeKebabItem.landed` is now every case). Unlike the C4 pair the gate
    /// on this row is capability, not role: `MeViewModel.enabledKebabItems` drops it for an account
    /// with no Google grant to extend.
    @Test func theImportFromYouTubeRouteRendersTheRealScreen() {
        #expect(leaf(for: .importFromYouTube) == "ImportFromYouTubeScreen")
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
        #expect(leaf(for: .settings) == "SettingsView")
        #expect(leaf(for: .about) == "AboutView")
    }
}
