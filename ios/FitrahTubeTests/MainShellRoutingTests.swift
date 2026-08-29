import Foundation
import SwiftUI
import Testing
@testable import FitrahTube

/// Plan C Global Constraints: `MainShellView.destination(for:)` has a `default:` arm, so a missing
/// `case` is a silent `PhaseTwoPlaceholderView` rather than a compile error. These tests walk the
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

    @Test func thePlaylistRouteRendersTheRealScreenNotThePlaceholder() {
        // Global Constraints: MainShellView.destination(for:) has a `default:` at :156, so a missing
        // arm is a silent placeholder rather than a compile error. This is the test that notices.
        let leaf = leafTypeName(for: .playlist(id: "PL1", title: "T", category: nil, count: 3))
        #expect(leaf == "PlaylistDetailScreen")
        #expect(leaf != "PhaseTwoPlaceholderView")
    }

    @Test func theChannelRouteRendersTheRealScreenNotThePlaceholder() {
        let leaf = leafTypeName(for: .channel(id: "UC1", name: nil, avatarURL: nil))
        #expect(leaf == "ChannelDetailScreen")
        #expect(leaf != "PhaseTwoPlaceholderView")
    }

    @Test func theWalkerReachesADistinctLeafPerRoute() {
        // Guards the helper: Plan C Task 5 gave the last route its screen, so no placeholder route is
        // left to pin; two different routes resolving to two different leaves proves the walker
        // still descends to the chosen branch rather than stopping at the outer conditional.
        #expect(leafTypeName(for: .settings) == "SettingsView")
        #expect(leafTypeName(for: .about) == "AboutView")
    }
}
