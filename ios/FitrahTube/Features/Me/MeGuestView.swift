import SwiftUI

/// Guest Me tab (spec D11: 5 tabs, guest Me shows local favorites + a sign-in card, never a
/// forced sign-in -- Android instead forces a sign-in screen here, which iOS deliberately does
/// not port). Phase 4 Task 10 made the card's button live: it pushes `Route.signIn`, which is a
/// destination the user chooses, never a gate in front of the catalog.
struct MeGuestView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    @State private var viewModel: FavoritesViewModel?

    private static let topAnchor = "me-top"

    var body: some View {
        // Gate B1-minor-1: `Router`'s comment claimed this tab root "is a static guest card with
        // nothing to scroll" and had it deliberately ignore the reselect signal -- but it is a
        // `ScrollView` holding a sign-in card plus up to 5 favourite rows. `shell-home.md:66`:
        // "Re-select at root -> animated scroll to top", same pair the other two roots use.
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
                    Color.clear.frame(height: 0).id(Self.topAnchor)
                    signInCard
                    favoritesSection
                }
                .padding(Spacing.md(widthClass))
            }
            .onChange(of: router.scrollToTopSignal) { _, signal in
                guard signal?.tab == .me else { return }
                withAnimation { proxy.scrollTo(Self.topAnchor, anchor: .top) }
            }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(String(localized: "nav_me"))
        .task {
            if viewModel == nil {
                viewModel = FavoritesViewModel(store: container.favorites)
            }
        }
    }

    // MARK: - Sign-in card

    private var signInCard: some View {
        VStack(spacing: Spacing.sm) {
            Text(String(localized: "me_guest_title"))
                .font(TypeScale.sectionTitle)
                .foregroundStyle(Color.textPrimary)
                .multilineTextAlignment(.center)
            Text(String(localized: "me_guest_body"))
                .font(TypeScale.body(widthClass))
                .foregroundStyle(Color.textSecondary)
                .multilineTextAlignment(.center)
            // Phase 4 Task 10: live. The screen it pushes is the ONLY sign-in surface -- the tab
            // itself stays a guest tab (D11: never a forced sign-in).
            Button(String(localized: "me_guest_sign_in")) { router.push(.signIn) }
                .buttonStyle(.borderedProminent)
                .tint(.brand)
        }
        .frame(maxWidth: .infinity)
        .padding(Spacing.lg(widthClass))
        .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
    }

    // MARK: - Favorites section (up to 5 rows + "See all")

    @ViewBuilder
    private var favoritesSection: some View {
        if let viewModel {
            VStack(alignment: .leading, spacing: Spacing.sm) {
                SectionHeader(
                    emoji: nil,
                    title: String(localized: "favorites_title"),
                    onSeeAll: viewModel.items.isEmpty ? nil : { router.push(.favorites) }
                )
                if viewModel.recentFavorites.isEmpty {
                    EmptyStateView(systemImage: "heart", title: String(localized: "favorites_empty_title"),
                                    message: String(localized: "favorites_empty_subtitle"))
                } else {
                    ForEach(viewModel.recentFavorites, id: \.videoId) { item in
                        VideoRow(item: viewModel.contentItem(for: item), subtitle: item.channelName) {
                            router.push(.player(viewModel.playerArgs(for: item)))
                        }
                    }
                }
            }
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { MeGuestView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { MeGuestView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
