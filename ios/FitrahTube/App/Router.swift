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
    /// The DEBUG environment default below (gate A-I2). `@Entry` generates a *computed* default
    /// (`static var defaultValue: Router { Router() }`), so every un-injected read of `\.router`
    /// used to mint a **different** instance: a preview's `selectionBinding` getter read one
    /// Router, `select(_:)` mutated another, `pathBinding` a third, and tab switching / deep links
    /// / the banner all looked dead with nothing to explain it. One stored instance, mirroring
    /// `AppContainer.sharedFake`.
    static let shared = Router()

    var selectedTab: Tab = .home
    var paths: [Tab: [Route]] = Dictionary(uniqueKeysWithValues: Tab.allCases.map { ($0, []) })
    var pendingRoute: Route?
    /// Android's Toast for the category-filter-applied message: a system-level overlay that must
    /// survive the "pop to origin" navigation that happens in the same gesture (`CategoriesView`/
    /// `SubcategoriesView` set this, then immediately `popToRoot`) -- so it lives on the shell,
    /// shown by `MainShellView.transientBanner`, not on the screen that's about to disappear.
    var pendingBanner: BannerMessage?
    /// Set by the player screen on entering/exiting fullscreen (B5); hides the iPad rail while true
    /// (the compact tab bar is hidden by `PlayerScreen`'s own `.toolbar` -- see its comment).
    var isFullscreen = false
    /// Bumped by `reselect(_:)` when it returns `.scrollToTop` -- carries which tab so only that
    /// tab's own root view reacts (`.onChange(of:)` needs a value that actually changes, which a
    /// bare `Tab` wouldn't on a second consecutive reselect of a tab already at rest).
    // Consumed by every scrollable tab root: `HomeView`, `ContentListView`
    // (channels/playlists/videos) and `MeGuestView` (gate B1-minor-1 -- the guest card sits in a
    // `ScrollView` above up to five favourite rows, so it is not the static card this comment used
    // to claim it was).
    var scrollToTopSignal: ScrollToTopSignal?
    /// Task 27 fix round / M7: bumped when a submission is created from a screen that is NOT My
    /// Submissions. `MySubmissionsScreen` re-reads on a change, so the row is there when the user
    /// gets back to it -- the same re-read the **+** ON that screen already does for its own sheet,
    /// reaching the sibling route that has its own ViewModel and cannot be called into directly.
    /// Cross-screen state on the router, `pendingBanner`'s precedent.
    ///
    /// A TOKEN, not a `Bool`: `.onChange` needs a value that actually changes, so two consecutive
    /// submits are two refreshes rather than one silently dropped (`scrollToTopSignal`'s reason).
    private(set) var submissionsToken = 0

    private var shellIsReady = false

    nonisolated struct ScrollToTopSignal: Equatable {
        let tab: Tab
        let token: Int
    }

    func push(_ route: Route) {
        paths[selectedTab, default: []].append(route)
    }

    /// See `submissionsToken`.
    func submissionsChanged() { submissionsToken += 1 }

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

    /// Part B gate (Codex 11): every tab whose path holds an account-only screen goes back to its
    /// root — the whole tab, not a splice, because everything ABOVE such a screen was reached from
    /// it. Tabs with no account screen keep their stacks: a guest browsing a channel loses nothing.
    func dropAccountRoutes() {
        for (tab, path) in paths where path.contains(where: \.requiresAccount) { paths[tab] = [] }
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
    ///
    /// Same shape as `\.container`: previews and tests get one shared instance, Release traps
    /// rather than silently handing every reader its own Router (gate A-I2).
    #if DEBUG
    @Entry var router: Router = .shared   // previews / tests
    #else
    @Entry var router: Router = { preconditionFailure("Router not injected — wrap the root in .environment(\\.router, …)") }()
    #endif
}
