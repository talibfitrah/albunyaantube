import SwiftUI

/// The favorites block both Me screens render. Extracted from `MeGuestView.favoritesSection`
/// rather than copied — the wave-2 W9 lesson (two ~60-line copies of one section).
///
/// `me_favorites` is byte-identical to `favorites_title` in all three locales, so the guest screen
/// keeps exactly the copy it had; `SectionHeader`'s built-in `see_all` is likewise byte-identical
/// to `me_see_all`, which is why no fourth spelling of either is authored here.
struct MeFavoritesSection: View {
    let maxRows: Int
    let onSeeAll: () -> Void

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @State private var viewModel: FavoritesViewModel?

    var body: some View {
        VStack(alignment: .leading, spacing: Spacing.sm) {
            if let viewModel {
                SectionHeader(
                    emoji: nil,
                    title: String(localized: "me_favorites"),
                    onSeeAll: viewModel.items.isEmpty ? nil : onSeeAll
                )
                if viewModel.items.isEmpty {
                    EmptyStateView(systemImage: "heart", title: String(localized: "favorites_empty_title"),
                                   message: String(localized: "favorites_empty_subtitle"))
                } else {
                    ForEach(viewModel.items.prefix(maxRows), id: \.videoId) { item in
                        VideoRow(item: viewModel.contentItem(for: item), subtitle: item.channelName) {
                            router.push(.player(viewModel.playerArgs(for: item)))
                        }
                    }
                }
            }
        }
        .task {
            if viewModel == nil { viewModel = FavoritesViewModel(store: container.favorites) }
        }
    }
}

/// Ruling C5's second Me screen, over LOCAL stores only: the chip rail (subscriptions + saved
/// playlists, merged), the favorites row, a Saved link and the kebab. No feed (Tasks 14-16), no
/// History rows (F10), no Content/Pending tabs (F14).
struct MeSignedInView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    @State private var model: MeViewModel?
    @State private var showSignOutConfirm = false

    private static let topAnchor = "me-signed-in-top"

    var body: some View {
        ScrollViewReader { proxy in
            ScrollView {
                VStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
                    Color.clear.frame(height: 0).id(Self.topAnchor)
                    if let model {
                        // Fix round 1 / M4: TWO readers of the one favorites store, deliberately.
                        // `model.favoriteTiles` decides the whole-screen empty state (it is the
                        // only place that has to know about chips as well), while the section
                        // reads the store itself and applies the same cap through `maxRows` — the
                        // component is shared with the guest screen, which has no `MeViewModel` to
                        // take rows from. One store, one order, so the two can never disagree; the
                        // day the section needs to render something the view model computes, it
                        // takes the rows instead of the cap.
                        if model.chips.isEmpty && model.favoriteTiles.isEmpty {
                            emptyState
                        } else {
                            chipRail(model)
                            MeFavoritesSection(maxRows: MeViewModel.maxFavoriteTiles) {
                                router.push(.favorites)
                            }
                        }
                        savedLink
                    }
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
        .toolbar { ToolbarItem(placement: .topBarTrailing) { kebab } }
        .signOutConfirmation(isPresented: $showSignOutConfirm) { model?.signOut() }
        .task {
            if model == nil {
                model = MeViewModel(session: container.session, favorites: container.favorites,
                                    subscriptions: container.subscriptions,
                                    savedPlaylists: container.savedPlaylists)
            }
        }
    }

    // MARK: - Chips

    /// A complete local list, so the pagination rule does not apply (Global Constraints) — there is
    /// no `loadMore()` behind it and never will be.
    private func chipRail(_ model: MeViewModel) -> some View {
        ScrollView(.horizontal, showsIndicators: false) {
            HStack(spacing: Spacing.sm) {
                ForEach(model.chips) { chip in
                    MeChip(title: chip.title, avatarURL: chip.avatarURL,
                           isSelected: model.selectedChipId == chip.id) {
                        // Tap-to-select, tap-again-to-clear. The feed it filters lands in Task 16;
                        // until then the selection is the chip rail's own state.
                        model.setFilter(model.selectedChipId == chip.id ? nil : chip.id)
                    }
                }
            }
            .padding(.horizontal, Spacing.xs)
        }
    }

    private var emptyState: some View {
        EmptyStateView(
            systemImage: "square.stack.3d.up",
            title: String(localized: "me_empty_title"),
            message: String(localized: "me_empty_subtitle"),
            action: (title: String(localized: "me_empty_cta"), run: { router.select(.channels) })
        )
    }

    // MARK: - Saved

    /// Phase 3's Saved screen, reached from the Me tab. Reuses `offline_saved_title` — there is one
    /// spelling of "Saved" in this app.
    private var savedLink: some View {
        Button { router.push(.offline) } label: {
            HStack(spacing: Spacing.md(widthClass)) {
                Image(systemName: "checkmark.circle")
                    .foregroundStyle(Color.brand)
                Text(String(localized: "offline_saved_title"))
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textPrimary)
                Spacer(minLength: 0)
                Image(systemName: "chevron.forward")
                    .foregroundStyle(Color.textSecondary)
            }
            .padding(Spacing.md(widthClass))
            .frame(minHeight: 44)
            .background(Color.homeCard, in: RoundedRectangle(cornerRadius: Radius.card))
        }
        .buttonStyle(.plain)
    }

    // MARK: - Kebab

    /// Renders `enabledKebabItems`, NOT `items(isModerator:)`: only `.signOut` has a destination in
    /// this task, and RULING 28 refuses a greyed row that promises one. No `.disabled(true)`
    /// anywhere. The role gate itself is fully tested through the pure `MeKebabItem.items(
    /// isModerator:)`, which needs no rendering.
    @ViewBuilder
    private var kebab: some View {
        if let model, !model.enabledKebabItems.isEmpty {
            Menu {
                ForEach(model.enabledKebabItems, id: \.self) { item in
                    Button {
                        switch item {
                        case .signOut: showSignOutConfirm = true
                        // Tasks 17/25/27/29 each land one of these and widen `MeKebabItem.landed`,
                        // which is what puts the row on screen in the first place — so this arm is
                        // unreachable today and must stay a no-op rather than a placeholder screen.
                        case .profile, .mySubmissions, .suggestContent, .importYouTube: break
                        }
                    } label: {
                        Label(String(localized: String.LocalizationValue(item.titleKey)),
                              systemImage: item.symbolName)
                    }
                }
            } label: {
                Image(systemName: "ellipsis")
            }
            .accessibilityLabel(String(localized: "menu"))
        }
    }
}

extension View {
    /// The sign-out confirmation, shared by the Me kebab and the Settings Account section.
    /// An `.alert`, never a `confirmationDialog` (CF-B3-11).
    func signOutConfirmation(isPresented: Binding<Bool>, onConfirm: @escaping () -> Void) -> some View {
        alert(String(localized: "settings_account_sign_out_confirm_title"), isPresented: isPresented) {
            Button(String(localized: "settings_account_sign_out_cancel"), role: .cancel) {}
            Button(String(localized: "settings_account_sign_out_confirm_action"), role: .destructive,
                   action: onConfirm)
        } message: {
            Text(String(localized: "settings_account_sign_out_confirm_body"))
        }
    }
}

#if DEBUG
#Preview {
    NavigationStack { MeSignedInView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { MeSignedInView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
#endif
