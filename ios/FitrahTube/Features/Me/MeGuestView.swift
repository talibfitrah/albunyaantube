import SwiftUI

/// Guest Me tab (spec D11: 5 tabs, guest Me shows local favorites + a sign-in card, never a
/// forced sign-in -- Android instead forces a sign-in screen here, which iOS deliberately does
/// not port). Real sign-in is a phase-4 dependency (accounts/auth); this screen is the
/// placeholder Phase 1 ships until then.
struct MeGuestView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    @State private var viewModel: FavoritesViewModel?

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
                signInCard
                favoritesSection
            }
            .padding(Spacing.md(widthClass))
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
            // ponytail: sign-in needs phase-4 auth (accounts/Firebase); this button intentionally
            // pushes nothing yet -- wire it to the real sign-in flow when that phase lands.
            Button(String(localized: "me_guest_sign_in")) {}
                .buttonStyle(.borderedProminent)
                .tint(.brand)
                .disabled(true)
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
                        VideoRow(item: viewModel.contentItem(for: item)) { router.push(.player(viewModel.playerArgs(for: item))) }
                    }
                }
            }
        }
    }
}

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
