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
        // Scrollable (gate B1-minor-12): five icon+label stacks between two `Spacer`s clipped at
        // `.accessibility4`/`.accessibility5` on an iPad in landscape, where the available height
        // is smallest -- spec §14 requires Dynamic Type everywhere. `.defaultScrollAnchor(.center)`
        // keeps the items centred (`app:menuGravity="center"`) whenever they do fit, and
        // `.basedOnSize` suppresses the rubber-banding that would otherwise imply scrollable
        // content on a rail that has none.
        ScrollView(.vertical, showsIndicators: false) {
            VStack(spacing: Spacing.sm) {
                ForEach(Tab.allCases, id: \.self, content: railItem)
            }
            .padding(.vertical, Spacing.sm)
        }
        .defaultScrollAnchor(.center)
        .scrollBounceBehavior(.basedOnSize)
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
