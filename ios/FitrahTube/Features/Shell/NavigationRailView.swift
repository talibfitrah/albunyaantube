import SwiftUI

/// Android's `NavigationRailView` (`layout-sw600dp/fragment_main_shell.xml`,
/// `layout-sw720dp/fragment_main_shell.xml`) -- the leading nav bar `MainShellView` shows at
/// regular/large width instead of `.tabViewStyle(.sidebarAdaptable)` (which renders iPadOS 18+'s
/// floating top capsule bar, rejected by the user 2026-08-23 as unlike the Android tablet UI).
/// Five centred items (`app:menuGravity="center"`), brand tint when selected else `navInactive`
/// (`bottom_nav_item_color`), on a `background` surface with a trailing elevation shadow
/// (`android:elevation="@dimen/elevation_lg"`).
struct NavigationRailView: View {
    @Environment(\.widthClass) private var widthClass
    let selectedTab: Tab
    let onSelect: (Tab) -> Void

    var body: some View {
        VStack(spacing: 0) {
            Spacer(minLength: 0)
            VStack(spacing: Spacing.sm) {
                ForEach(Tab.allCases, id: \.self, content: railItem)
            }
            Spacer(minLength: 0)
        }
        .frame(width: NavigationRailMetrics.width(widthClass))
        .frame(maxHeight: .infinity)
        .background(Color.background)
        // Symmetric ambient shadow (not offset toward one edge) so it reads correctly in both LTR
        // and RTL without direction-aware offset math -- Android's `elevation` shadow is likewise
        // a soft ambient cue, not a hard one-sided edge.
        .shadow(color: .black.opacity(0.12), radius: 8, x: 0, y: 2)
        .accessibilityElement(children: .contain)
        .accessibilityAddTraits(.isTabBar)
    }

    private func railItem(_ tab: Tab) -> some View {
        let isSelected = tab == selectedTab
        return Button {
            onSelect(tab)
        } label: {
            VStack(spacing: Spacing.xxs) {
                Image(systemName: tab.symbolName)
                    .font(.system(size: NavigationRailMetrics.iconSize(widthClass)))
                Text(tab.title)
                    .font(NavigationRailMetrics.label(widthClass))
            }
            .foregroundStyle(isSelected ? Color.brand : Color.navInactive)
            .frame(minWidth: NavigationRailMetrics.width(widthClass), minHeight: Size.button(widthClass))
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(isSelected ? [.isButton, .isSelected] : .isButton)
        .accessibilityLabel(tab.title)
    }
}

#Preview {
    HStack(spacing: 0) {
        NavigationRailView(selectedTab: .home) { _ in }
        Color.homeSurface
    }
    .environment(\.widthClass, .large)
}
