import SwiftUI

/// The detail screens' collapsing header (plan Task 4 step 1; `PlaylistDetailFragment.kt:253-264`,
/// `ChannelDetailFragment.kt:167-179`): a `ScrollView` whose first child is the hero, with the
/// scroll offset driving a 0…1 collapse fraction that fades the inline title into the navigation
/// bar and flips the bar from transparent-over-the-image (light chrome) to its normal surface.
/// Layout, not a UIKit port -- no `UIViewControllerRepresentable`. Shared with Task 5's channel
/// screen. `onContentFits` / `.refreshable` applied by the caller reach the scroll view inside.
struct DetailHeader<Hero: View, Content: View>: View {
    let title: String
    let heroHeight: CGFloat
    /// Receives the top safe-area inset (status bar + the transparent navigation bar) so the hero's
    /// foreground can keep clear of it while its background fills under the bar.
    @ViewBuilder let hero: (CGFloat) -> Hero
    @ViewBuilder let content: () -> Content

    @State private var collapse: CGFloat = 0

    var body: some View {
        GeometryReader { proxy in
            let inset = proxy.safeAreaInsets.top
            ScrollView {
                VStack(spacing: 0) {
                    hero(inset).frame(height: heroHeight + inset).clipped()
                    content()
                }
            }
            .ignoresSafeArea(edges: .top)
        }
        .onScrollGeometryChange(for: CGFloat.self) { geometry in
            geometry.contentOffset.y + geometry.contentInsets.top
        } action: { _, offset in
            collapse = min(1, max(0, offset / max(1, heroHeight - 44)))
        }
        .navigationBarTitleDisplayMode(.inline)
        .toolbar {
            ToolbarItem(placement: .principal) {
                Text(title).font(.headline).lineLimit(1).opacity(collapse)
                    .accessibilityHidden(collapse < 0.5)
            }
        }
        // ponytail: a threshold, not a continuous tint interpolation -- the bar's own material and
        // the back button only take a colour scheme, not a Color. Upgrade if design asks for the fade.
        .toolbarBackground(collapse < 0.5 ? .hidden : .visible, for: .navigationBar)
        .toolbarColorScheme(collapse < 0.5 ? .dark : nil, for: .navigationBar)
    }
}
