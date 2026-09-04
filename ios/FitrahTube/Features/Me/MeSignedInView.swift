import InnerTubeKit
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
    @Environment(\.locale) private var locale

    @State private var model: MeViewModel?
    @State private var showSignOutConfirm = false
    @State private var paginationGuard = PaginationGuard()
    /// Geometry *state*, not an event (gate B1-C1), exactly as `ContentListView` keeps it.
    @State private var feedFits = false
    /// RULINGS #24's one transient-error surface, the way Home reports a failed reload that kept
    /// its rows (`HomeView.swift:65`): it announces to VoiceOver and carries a real dismiss
    /// control, neither of which the bare `Text` this replaces had.
    @State private var feedBanner: BannerMessage?
    /// The two refreshes that are NOT owned by `.task`/`.refreshable` (a subscription change and a
    /// chip tap). Held so they are cancelled on the next one and on dismissal, instead of running
    /// on — with, at worst, `perChannelTimeout` still on the clock behind them.
    @State private var refreshTask: Task<Void, Never>?

    /// The container's ONE feed repository, not a per-view `@State`: it holds the loaded-week depth
    /// and the per-channel refresh bookkeeping, which must survive this view being rebuilt.
    private var feed: MeFeedRepository { container.meFeed }

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
                        feedSection
                    }
                }
                .padding(Spacing.md(widthClass))
            }
            .refreshable {
                paginationGuard.reset()
                await refreshFeed(model?.subscribedChannelIds ?? [], force: true)
            }
            .onContentFits { fits in
                feedFits = fits
                triggerFeedAutoFill()
            }
            // The companion every other autofill site pairs with `onContentFits`
            // (`ContentListView.swift:51,101`): re-arm the guards after each completed load rather
            // than relying on the fit margin alone changing.
            .onChange(of: feed.weeks) { _, _ in triggerFeedAutoFill() }
            .onChange(of: router.scrollToTopSignal) { _, signal in
                guard signal?.tab == .me else { return }
                withAnimation { proxy.scrollTo(Self.topAnchor, anchor: .top) }
            }
            // Android's `.drop(1)` (`MeViewModel.kt:181-195`): a nil `old` is the model being built,
            // which the `.task` below already covers -- refreshing here too would double-fetch every
            // channel on first appearance. Every LATER change (subscribe/unsubscribe elsewhere, an
            // import that graduates) reaches the feed, which is the bug that comment describes: a
            // fresh install that opened Me before subscribing would otherwise stay empty forever.
            .onChange(of: model?.subscribedChannelIds.count) { old, _ in
                guard old != nil, let model else { return }
                start { await refreshFeed(model.subscribedChannelIds, force: false) }
            }
        }
        .background(Color.background.ignoresSafeArea())
        .transientBanner($feedBanner)
        .navigationTitle(String(localized: "nav_me"))
        .toolbar { ToolbarItem(placement: .topBarTrailing) { kebab } }
        .signOutConfirmation(isPresented: $showSignOutConfirm) { model?.signOut() }
        .onDisappear { refreshTask?.cancel() }
        .task {
            // Bound once and read back from the local, never from the `@State` this closure just
            // wrote: a `@State` write is not visible to the writing closure, and a nil read here
            // would refresh over an EMPTY channel list -- which `.onChange`'s `guard old != nil`
            // deliberately never retries.
            let model = self.model ?? MeViewModel(session: container.session, favorites: container.favorites,
                                                  subscriptions: container.subscriptions,
                                                  savedPlaylists: container.savedPlaylists)
            self.model = model
            // Ruling F6's burst-if-stale, and the ONLY scheduled refresh there is: foreground only,
            // no `BGAppRefreshTask`, no `UIBackgroundModes`. `force: false` leaves the TTL and the
            // backoff ladder in charge, so a tab revisit inside 30 min sends nothing at all.
            await refreshFeed(model.subscribedChannelIds, force: false)
        }
    }

    /// One refresh, one banner. Posted per COMPLETED refresh rather than from
    /// `.onChange(of: feed.lastError)`, which cannot fire twice for the same message and so would
    /// stay silent on a second failed pull-to-refresh.
    private func refreshFeed(_ channelIds: [String], force: Bool) async {
        await feed.refresh(channelIds: channelIds, force: force)
        if let error = feed.lastError { feedBanner = BannerMessage(text: error) }
    }

    /// The one unstructured-task slot: the previous occupant is cancelled first, and `.onDisappear`
    /// cancels the last one.
    private func start(_ work: @escaping @MainActor () async -> Void) {
        refreshTask?.cancel()
        refreshTask = Task { await work() }
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
                        // Tap-to-select, tap-again-to-clear.
                        let next = model.selectedChipId == chip.id ? nil : chip.id
                        model.setFilter(next)
                        // I4's ruling: the chip always SELECTS, but only a channel chip filters
                        // the feed — the Atom feed is per channel, so a playlist chip filtered it
                        // to a section with no rows and no explanation.
                        let feedFilter = MeViewModel.feedFilter(for: next, in: model.chips)
                        // Cleared in the TAP, not in the rebucket that follows: a `.task(id:)` runs
                        // after the render, so the frame between the tap and the rebucket would
                        // show the old weeks under the new chip.
                        feed.setFilter(feedFilter)
                        paginationGuard.reset()
                        start { await feed.rebucket(filter: feedFilter) }
                    }
                }
            }
            .padding(.horizontal, Spacing.xs)
        }
    }

    // MARK: - Feed

    /// The week-bucketed feed over the subscribed channels' Atom caches. `LazyVStack`, not the
    /// enclosing plain `VStack`: `onAppear` in a non-lazy stack fires for every row at once, which
    /// would make the load-more sentinel below page the whole cache on first render.
    @ViewBuilder
    private var feedSection: some View {
        LazyVStack(alignment: .leading, spacing: Spacing.lg(widthClass)) {
            // WHAT, never WHY: `me_refresh_error` says the feed didn't refresh and how to retry,
            // and it is delivered by the banner above (`refreshFeed`) rather than as a line of
            // low-emphasis body text no assistive technology announced.
            // Task 19: feed empty-state copy — an EMPTY, not-refreshing feed still renders
            // nothing, because saying so needs a string this round is not allowed to author.
            if feed.isRefreshing && feed.weeks.isEmpty {
                ProgressView()
                    .tint(.brand)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.md(widthClass))
                    .accessibilityLabel(String(localized: "loading"))
            }
            ForEach(feed.weeks) { week in
                VStack(alignment: .leading, spacing: Spacing.sm) {
                    SectionHeader(emoji: nil, title: Self.weekTitle(week.index, locale: locale), onSeeAll: nil)
                    ForEach(week.items, id: \.id) { feedRow($0) }
                }
            }
            // The scroll-position half of the pagination rule; `triggerFeedAutoFill` is the
            // large-screen half, for the tablet page that already fits and never scrolls.
            if !feed.weeks.isEmpty && !feed.reachedEnd {
                Color.clear.frame(height: 1).onAppear { feed.loadMoreWeeks() }
            }
        }
    }

    /// `me_week_this` / `me_week_last` / `me_week_n_ago`, looked up in the passed locale's own
    /// `.lproj` so the count renders in that language's digits regardless of the device language.
    private static func weekTitle(_ index: Int, locale: Locale) -> String {
        let header = WeekBucket.headerKey(weekIndex: index)
        guard let argument = header.argument else {
            return Format.localizedFormat(header.key, locale: locale)
        }
        return Format.localizedFormat(header.key, locale: locale, Int64(argument))
    }

    /// An Atom row is missing fields, not zero fields: no duration and no view count, so `VideoRow`
    /// renders neither chip nor "0 views".
    private func feedRow(_ item: VideoItem) -> some View {
        let contentItem = ContentItem(id: item.id, type: .video, title: item.title, category: nil,
                                      description: nil, thumbnailURL: item.thumbnailURL,
                                      durationSeconds: nil, uploadedDaysAgo: nil, viewCount: nil,
                                      channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)
        // Humanized HERE from the exact instant, never from the row's own `publishedText`: that
        // string was humanized when the fetch WROTE the cache, so a feed served from cache (or
        // replayed through a 304) would keep saying "2 days ago" indefinitely.
        return VideoRow(item: contentItem,
                        subtitle: AtomFeedFetcher.humanizePublished(from: item.publishedAt, locale: locale)) {
            router.push(.player(PlayerArgs(videoId: item.id, title: item.title,
                                           thumbnailURL: item.thumbnailURL)))
        }
        .accessibilityIdentifier("me.feed.row.\(item.id)")
    }

    /// CLAUDE.md's pagination rule: a tablet/TV page whose loaded weeks already fit the viewport
    /// never fires the sentinel's `onAppear`, so the six `PaginationGuard` checks run on every
    /// layout delta instead. No async commit race here -- `loadMoreWeeks()` is synchronous.
    private func triggerFeedAutoFill() {
        guard !feed.weeks.isEmpty else { return }
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: !feed.reachedEnd,
                                     paginationError: false, contentFits: feedFits,
                                     itemCount: feed.weeks.reduce(0) { $0 + $1.items.count }) else {
            paginationGuard = attempt
            return
        }
        feed.loadMoreWeeks()
        paginationGuard = attempt
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
