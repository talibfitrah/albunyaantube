import SwiftUI

/// Android's `MainShellFragment` (`shell-home.md:A1-A6`, spec §6). Compact width keeps a plain
/// `TabView` bottom bar; regular/large replace it with a leading `NavigationRailView` mirroring
/// Android's `NavigationRailView` (task-7b) -- `.tabViewStyle(.sidebarAdaptable)` is NOT used, it
/// renders iPadOS 18+'s floating top capsule bar instead, rejected by the user 2026-08-23 as
/// unlike the Android tablet UI. `TabView` itself is NOT used for the rail layout either: on this
/// SDK its own chrome (the same floating capsule) survives even `.toolbar(.hidden, for: .tabBar)`
/// -- confirmed live on iPad (screenshot showed both the rail AND the capsule at once). Instead
/// the rail layout renders a `ZStack` of all five tabs' `NavigationStack`s permanently (each one's
/// SwiftUI identity never changes, so its state/scroll position survives a tab switch same as
/// `TabView`'s own tab-keeping does), showing only the selected one via `opacity`/
/// `allowsHitTesting`/`accessibilityHidden` -- so a rail tap can never reach hidden chrome that
/// isn't there.
struct MainShellView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    // task-7b fold-in (task-13): the rail branch used to build all five tabs' `NavigationStack`s
    // unconditionally (see `railStacks`'s doc comment below) -- on iPad that fired every tab's
    // first load at launch. Seeded with the selected tab on first appearance, grown on every
    // subsequent selection; a tab already in the set keeps its state exactly as before (this only
    // gates the *first* mount). The bottom-bar branch needs no such gate: the modern `Tab(...)`
    // view-builder API `tabView` uses already defers a tab's content until first selected.
    @State private var mountedTabs: Set<Tab> = []

    private var shellLayout: ShellLayout { ShellLayout(widthClass) }

    var body: some View {
        layoutBody
            .tint(.brand) // shell-home.md:A2 — selected tab/rail item is tint-only, brand green, no pill indicator.
            .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: container.network.isOnline)
            // Task 11: the category-filter-applied banner is set by CategoriesView/SubcategoriesView
            // right before they pop themselves away, so it has to live above the NavigationStacks --
            // on the shell itself, like Android's Toast (a system overlay that survives the
            // `navigateUp()` in the same gesture) -- not on a screen that's about to disappear.
            .transientBanner(bannerBinding)
            .task { router.shellDidAppear() }
    }

    /// Gate wave-4 V12, accepted: crossing 600 pt mid-session (iPad Split View / Stage Manager
    /// resize -- not rotation, which `WidthClass.init(size:)` deliberately keeps stable) swaps
    /// these two structurally different subtrees, so every tab's content gets a new SwiftUI
    /// identity and its view-local `@State` goes: ViewModels, loaded pages, `PaginationGuard`,
    /// search field text and scroll position. What survives is what lives outside the subtree --
    /// `router.paths` (so the pushed screens are still there), `router.selectedTab`, and this
    /// view's own `mountedTabs` -- so the user lands back where they were, on a list that reloads
    /// its first page. Not cheaply fixable: SwiftUI identity is positional, and the two branches
    /// cannot share one stack set without also sharing one container -- the native `TabView` is
    /// the compact branch's whole point, and the rail branch cannot use it (see the type doc).
    @ViewBuilder
    private var layoutBody: some View {
        switch shellLayout {
        case .bottomBar:
            tabView
                .overlay(alignment: .top) { offlineBannerOverlay }
        case .rail:
            HStack(spacing: 0) {
                if !router.isFullscreen {
                    NavigationRailView(selectedTab: router.selectedTab, onSelect: router.select)
                }
                railStacks
                    .overlay(alignment: .top) { offlineBannerOverlay }
            }
        }
    }

    /// Compact width only -- `SwiftUI.Tab` disambiguates the tab-item view builder from this
    /// file's own `Tab` enum.
    private var tabView: some View {
        TabView(selection: selectionBinding) {
            ForEach(Tab.allCases, id: \.self) { tab in
                SwiftUI.Tab(tab.title, systemImage: tab.symbolName, value: tab) {
                    navigationStack(for: tab)
                }
            }
        }
    }

    /// Regular/large width only (see the type doc comment for why `TabView` isn't reused here).
    /// Only tabs in `mountedTabs` (visited at least once) are built; once mounted a tab's
    /// `NavigationStack` stays alive (same identity every render) so its state/scroll position
    /// survives being hidden, same as before -- this only changes when a tab is first built, not
    /// whether it keeps state after that.
    private var railStacks: some View {
        ZStack {
            ForEach(Tab.allCases, id: \.self) { tab in
                if mountedTabs.contains(tab) {
                    navigationStack(for: tab)
                        .opacity(tab == router.selectedTab ? 1 : 0)
                        .allowsHitTesting(tab == router.selectedTab)
                        .accessibilityHidden(tab != router.selectedTab)
                }
            }
        }
        .onAppear { mountedTabs.insert(router.selectedTab) }
        .onChange(of: router.selectedTab) { _, newValue in mountedTabs.insert(newValue) }
    }

    private func navigationStack(for tab: Tab) -> some View {
        NavigationStack(path: pathBinding(for: tab)) {
            rootView(for: tab)
                .navigationDestination(for: Route.self) { destination(for: $0) }
        }
    }

    @ViewBuilder
    private var offlineBannerOverlay: some View {
        if !container.network.isOnline {
            OfflineBanner()
                .transition(.move(edge: .top).combined(with: .opacity))
        }
    }

    /// A plain `onChange(of: selectedTab)` never fires on reselect -- SwiftUI only calls it when
    /// the value actually differs, and tapping the current tab reassigns the same case. A custom
    /// `Binding`'s setter, in contrast, is invoked by `TabView` on every tap regardless of whether
    /// the value changed, so it's the only reliable place to detect "user tapped the active tab".
    /// `router.select(_:)` is the same reselect-or-switch logic `NavigationRailView`'s buttons call
    /// directly (not via a `Binding` -- a plain tap has no "value" to set).
    private var selectionBinding: Binding<Tab> {
        Binding(get: { router.selectedTab }, set: { router.select($0) })
    }

    private func pathBinding(for tab: Tab) -> Binding<[Route]> {
        Binding(get: { router.paths[tab] ?? [] }, set: { router.paths[tab] = $0 })
    }

    private var bannerBinding: Binding<BannerMessage?> {
        Binding(get: { router.pendingBanner }, set: { router.pendingBanner = $0 })
    }

    // task-13: `.settings`/`.about` replace their placeholders. Plan C Tasks 4/5: `.playlist` and
    // `.channel` too -- the last placeholder, so the `default:` arm is gone and this switch is
    // exhaustive again. Internal, not private: `MainShellRoutingTests` pins the Plan C arms.
    @ViewBuilder
    func destination(for route: Route) -> some View {
        switch route {
        case .channel(let id, let name, let avatarURL):
            ChannelDetailScreen(id: id, name: name, avatarURL: avatarURL)
        case .playlist(let id, let title, let category, let count):
            PlaylistDetailScreen(id: id, title: title, category: category, count: count)
        case .player(let args):
            PlayerScreen(args: args)
        case .shorts(let args):
            ShortsScreen(args: args)
        case .featured(let categoryId, let categoryName):
            FeaturedView(categoryId: categoryId, categoryName: categoryName)
        case .search:
            SearchView()
        case .categories:
            CategoriesView()
        case .subcategories(let parentId, let parentName):
            SubcategoriesView(parentId: parentId, parentName: parentName)
        case .favorites:
            FavoritesView()
        case .settings:
            SettingsView()
        case .about:
            AboutView()
        }
    }

    // Tab roots: task-12 replaces the Me placeholder with the guest Me tab (spec D11).
    @ViewBuilder
    private func rootView(for tab: Tab) -> some View {
        switch tab {
        case .home: HomeView()
        case .channels: ContentListView(type: .channels)
        case .me: MeGuestView()
        case .playlists: ContentListView(type: .playlists)
        case .videos: ContentListView(type: .videos)
        }
    }

}

#if DEBUG
#Preview {
    MainShellView()
        .environment(\.container, .sharedFake)
}
#endif
