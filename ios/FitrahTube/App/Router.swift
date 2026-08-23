import Foundation
import Observation
import SwiftUI

/// What a tap on the already-selected tab should do (`shell-home.md:A3`): pop that tab's stack to
/// root if it's pushed somewhere, or signal the root screen to scroll to top if already there.
nonisolated enum ReselectAction: Equatable {
    case popToRoot
    case scrollToTop
}

/// One `Router` per app, owning a per-tab `NavigationStack` path each (`shell-home.md:A3` -- a
/// deliberate improvement over Android's single shared back stack) plus the deep-link hold
/// (RULING 4: a pending deep link is held until the shell has actually appeared, then applied
/// exactly once).
@MainActor @Observable final class Router {
    var selectedTab: Tab = .home
    var paths: [Tab: [Route]] = Dictionary(uniqueKeysWithValues: Tab.allCases.map { ($0, []) })
    var pendingRoute: Route?
    /// Android's Toast for the category-filter-applied message: a system-level overlay that must
    /// survive the "pop to origin" navigation that happens in the same gesture (`CategoriesView`/
    /// `SubcategoriesView` set this, then immediately `popToRoot`) -- so it lives on the shell,
    /// shown by `MainShellView.transientBanner`, not on the screen that's about to disappear.
    var pendingBanner: BannerMessage?
    /// Set by the player screen on entering/exiting fullscreen (phase 2); hides the tab bar while true.
    var isFullscreen = false
    /// Bumped by `reselect(_:)` when it returns `.scrollToTop` -- carries which tab so only that
    /// tab's own root view reacts (`.onChange(of:)` needs a value that actually changes, which a
    /// bare `Tab` wouldn't on a second consecutive reselect of a tab already at rest).
    // ponytail: consumed by the two scrollable tab roots -- `HomeView` and `ContentListView`
    // (channels/playlists/videos). The `me` tab's root (`MeGuestView`) is a static guest card with
    // nothing to scroll, so it deliberately ignores the signal; wire it up if that tab ever
    // becomes a list.
    var scrollToTopSignal: ScrollToTopSignal?

    private var shellIsReady = false

    nonisolated struct ScrollToTopSignal: Equatable {
        let tab: Tab
        let token: Int
    }

    func push(_ route: Route) {
        paths[selectedTab, default: []].append(route)
    }

    /// Shared tap handler for the bottom `TabView` and the leading `NavigationRailView` (task-7b)
    /// -- switching tabs assigns directly; tapping the already-selected tab reselects (pop to
    /// root, or scroll to top if already there) instead of a no-op. One function so the two bars
    /// can never disagree on reselect semantics.
    func select(_ tab: Tab) {
        if tab == selectedTab {
            _ = reselect(tab)
        } else {
            selectedTab = tab
        }
    }

    func popToRoot(_ tab: Tab) {
        paths[tab] = []
    }

    func reselect(_ tab: Tab) -> ReselectAction {
        if paths[tab]?.isEmpty == false {
            popToRoot(tab)
            return .popToRoot
        }
        scrollToTopSignal = ScrollToTopSignal(tab: tab, token: (scrollToTopSignal?.token ?? 0) + 1)
        return .scrollToTop
    }

    /// Parses `url` via `DeepLinkParser`; an unrecognized URL is silently ignored -- there's
    /// nowhere to route it. Before the shell has appeared there are no tab paths to push onto yet,
    /// so the route is held in `pendingRoute` (RULING 4) instead of applied immediately.
    func open(_ url: URL) {
        guard let route = DeepLinkParser.route(for: url) else { return }
        if shellIsReady {
            push(route)
        } else {
            pendingRoute = route
        }
    }

    /// Called once the shell view has appeared. Applies any deep link that arrived before then,
    /// exactly once: `pendingRoute` is cleared as it's consumed, so a second call is a no-op.
    func shellDidAppear() {
        shellIsReady = true
        guard let route = pendingRoute else { return }
        pendingRoute = nil
        push(route)
    }
}

extension EnvironmentValues {
    /// Owned by `FitrahTubeApp` (not `MainShellView`) so a deep link that arrives before the
    /// shell exists -- e.g. tapped while Onboarding is still showing -- has somewhere to land.
    @Entry var router: Router = Router()
}
