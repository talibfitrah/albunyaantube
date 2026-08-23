import SwiftUI

/// Android's `MainShellFragment` (`shell-home.md:A1-A6`, spec §6). `TabView(.sidebarAdaptable)`
/// gives bottom tabs on compact width and a sidebar/rail on regular+ automatically, matching
/// Android's `BottomNavigationView` / `NavigationRailView` split without separate layouts per
/// width class.
struct MainShellView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.accessibilityReduceMotion) private var reduceMotion

    var body: some View {
        TabView(selection: selectionBinding) {
            ForEach(Tab.allCases, id: \.self) { tab in
                // `SwiftUI.Tab` disambiguates the tab-item view builder from this file's own `Tab` enum.
                SwiftUI.Tab(title(for: tab), systemImage: symbol(for: tab), value: tab) {
                    NavigationStack(path: pathBinding(for: tab)) {
                        rootView(for: tab)
                            .navigationDestination(for: Route.self) { PhaseTwoPlaceholderView(route: $0) }
                    }
                }
            }
        }
        .tabViewStyle(.sidebarAdaptable)
        .tint(.brand) // shell-home.md:A2 — selected tab/rail item is tint-only, brand green, no pill indicator.
        .toolbar(router.isFullscreen ? .hidden : .visible, for: .tabBar)
        .overlay(alignment: .top) {
            if !container.network.isOnline {
                OfflineBanner()
                    .transition(.move(edge: .top).combined(with: .opacity))
            }
        }
        .animation(reduceMotion ? nil : .easeInOut(duration: 0.25), value: container.network.isOnline)
        .task { router.shellDidAppear() }
    }

    /// A plain `onChange(of: selectedTab)` never fires on reselect -- SwiftUI only calls it when
    /// the value actually differs, and tapping the current tab reassigns the same case. A custom
    /// `Binding`'s setter, in contrast, is invoked by `TabView` on every tap regardless of whether
    /// the value changed, so it's the only reliable place to detect "user tapped the active tab".
    private var selectionBinding: Binding<Tab> {
        Binding(
            get: { router.selectedTab },
            set: { newTab in
                if newTab == router.selectedTab {
                    // ponytail: ReselectAction.scrollToTop is unconsumed until the tab roots have scrollable content (Tasks 9–13).
                    _ = router.reselect(newTab)
                } else {
                    router.selectedTab = newTab
                }
            }
        )
    }

    private func pathBinding(for tab: Tab) -> Binding<[Route]> {
        Binding(get: { router.paths[tab] ?? [] }, set: { router.paths[tab] = $0 })
    }

    // Tab roots are placeholders for now -- tasks 9-13 replace each with its real screen.
    @ViewBuilder
    private func rootView(for tab: Tab) -> some View {
        switch tab {
        case .home: Text("Home")
        case .channels: Text("Channels")
        case .me: Text("Me")
        case .playlists: Text("Playlists")
        case .videos: Text("Videos")
        }
    }

    private func title(for tab: Tab) -> String {
        switch tab {
        case .home: String(localized: "nav_home")
        case .channels: String(localized: "nav_channels")
        case .me: String(localized: "nav_me")
        case .playlists: String(localized: "nav_playlists")
        case .videos: String(localized: "nav_videos")
        }
    }

    // SF Symbols per `strings-assets.md:732-770`.
    private func symbol(for tab: Tab) -> String {
        switch tab {
        case .home: "house.fill"
        case .channels: "tv"
        case .me: "person.crop.circle"
        case .playlists: "list.bullet.rectangle"
        case .videos: "film.stack"
        }
    }
}

#Preview {
    MainShellView()
        .environment(\.container, .sharedFake)
}
