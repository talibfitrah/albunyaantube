import InnerTubeKit
import SwiftUI

/// RULING 9 / F: a scrollable strip on compact, fixed-and-filled at ≥600 pt, over a swipeable
/// `.page` `TabView` (`ChannelDetailFragment.kt:196` `isUserInputEnabled = true`). Not a `Picker`:
/// it gives neither the scroll nor the swipe. Each tab owns its scroll; the header above pins.
struct ChannelTabsView: View {
    let viewModel: ChannelDetailViewModel
    @Binding var banner: BannerMessage?

    @Environment(\.widthClass) private var widthClass
    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Namespace private var indicator

    var body: some View {
        VStack(spacing: 0) {
            strip
            Divider()
            TabView(selection: selection) {
                ForEach(viewModel.tabs, id: \.self) { tab in
                    tabBody(tab).tag(tab)
                }
            }
            .tabViewStyle(.page(indexDisplayMode: .never))
        }
        .onChange(of: viewModel.selectedTab, initial: true) { _, tab in
            Task { await viewModel.ensureTabLoaded(tab) }
        }
    }

    private var selection: Binding<ChannelTabKind> {
        Binding(get: { viewModel.selectedTab }, set: { viewModel.selectedTab = $0 })
    }

    // MARK: - Strip

    @ViewBuilder
    private var strip: some View {
        if widthClass == .compact {
            ScrollView(.horizontal, showsIndicators: false) { stripButtons }
        } else {
            stripButtons
        }
    }

    private var stripButtons: some View {
        HStack(spacing: 0) {
            ForEach(viewModel.tabs, id: \.self) { tab in
                let selected = tab == viewModel.selectedTab
                Button {
                    withAnimation(reduceMotion ? nil : .easeInOut(duration: 0.2)) { viewModel.selectedTab = tab }
                } label: {
                    VStack(spacing: Spacing.xs) {
                        Text(String(localized: String.LocalizationValue(tab.titleKey)))
                            .font(TypeScale.subtitle).fontWeight(selected ? .bold : .regular)
                            .foregroundStyle(selected ? Color.brand : Color.textSecondary)
                            .lineLimit(1)
                        ZStack {
                            Color.clear.frame(height: 3)
                            if selected {
                                Capsule().fill(Color.brand).frame(height: 3)
                                    .matchedGeometryEffect(id: "indicator", in: indicator)
                            }
                        }
                    }
                    .padding(.horizontal, Spacing.md(widthClass))
                    .frame(maxWidth: widthClass == .compact ? nil : .infinity, minHeight: 44)
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(selected ? [.isSelected] : [])
                .accessibilityIdentifier("channel.tab.\(tab)")
            }
        }
    }

    // MARK: - Bodies

    @ViewBuilder
    private func tabBody(_ tab: ChannelTabKind) -> some View {
        switch tab {
        case .videos, .live, .shorts:
            ChannelVideoTab(viewModel: viewModel, tab: tab)
        case .playlists:
            ChannelPlaylistsTab(viewModel: viewModel)
        case .about:
            ChannelAboutTab(viewModel: viewModel)
        }
    }
}

// MARK: - Video-shaped tabs (Videos / Live / Shorts)

/// One list body for the three `VideoItem` tabs; Shorts swaps the rows for the 2/4/5 grid
/// (spec §11) and the skeleton for `SkeletonShorts`. Pagination is `ChannelTabAutofill`
/// (ruling 10; reconciliation note 2) -- NOT `PaginationGuard`.
private struct ChannelVideoTab: View {
    let viewModel: ChannelDetailViewModel
    let tab: ChannelTabKind

    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize
    @State private var autofill = ChannelTabAutofill()
    @State private var contentFits = false
    @State private var isLoadingMore = false

    var body: some View {
        ScrollView {
            content
        }
        .onContentFits { fits in
            contentFits = fits
            triggerAutoFill()
        }
        .onChange(of: viewModel.visible(tab).items.count) { _, _ in triggerAutoFill() }
        .onChange(of: viewModel.query) { _, _ in
            autofill.reset()
            triggerAutoFill()
        }
    }

    @ViewBuilder
    private var content: some View {
        let state = viewModel.visible(tab)
        switch state {
        case .idle, .loadingInitial:
            if tab.skeletonKind == .shortsGrid {
                SkeletonShorts(columns: shortsColumns, rows: 2)
            } else {
                SkeletonListView().padding(Spacing.md(widthClass))
            }
        case .empty(let key):
            ChannelTabStates.empty(key: key, widthClass: widthClass)
        case .errorInitial(let key):
            ChannelTabStates.error(key: key, viewModel: viewModel, tab: tab, widthClass: widthClass)
        case .loaded, .errorAppend:
            let items = state.items
            if tab == .shorts {
                LazyVGrid(columns: Array(repeating: GridItem(.flexible(), spacing: Spacing.sm), count: shortsColumns),
                          spacing: Spacing.md(widthClass)) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                        shortCell(item).onAppear { nearEnd(offset, of: items.count) }
                    }
                }
                .padding(.horizontal, Spacing.md(widthClass))
                .padding(.top, Spacing.sm)
            } else {
                LazyVStack(spacing: 0) {
                    ForEach(Array(items.enumerated()), id: \.element.id) { offset, item in
                        row(item).onAppear { nearEnd(offset, of: items.count) }
                    }
                }
            }
            ListFooter(state: footerState(state), loadMore: loadMoreTapped, retry: triggerScrollLoadMore)
        }
    }

    private var shortsColumns: Int {
        GridRules.columns(widthClass.pick(2, 4, 5), dynamicTypeSize: dynamicTypeSize)
    }

    /// Degraded rows are missing fields, not zero fields: an Atom item has no duration and no
    /// view count, so `VideoRow` renders neither chip nor "0 views" (`subtitle` carries what exists).
    private func row(_ item: VideoItem) -> some View {
        let contentItem = ContentItem(id: item.id, type: .video, title: item.title, category: nil, description: nil,
                                      thumbnailURL: item.thumbnailURL, durationSeconds: item.durationSeconds,
                                      uploadedDaysAgo: nil, viewCount: nil, channelTitle: viewModel.header.name,
                                      subscribers: nil, videoCount: nil, itemCount: nil)
        let subtitle = PlaylistDetailViewModel.rowSubtitle(item) ?? ""
        return VideoRow(item: contentItem, subtitle: subtitle) {
            router.push(.player(viewModel.playerArgs(for: item, tab: tab)))
        }
        // C T5 fix I2: the badge is parsed from the thumbnail overlay, so an UPCOMING premiere
        // (also duration-less) is no longer labelled LIVE.
        .overlay(alignment: .topLeading) {
            if let badge = item.badge {
                Badge(badge == .live ? .live : .upcoming).padding(Spacing.md(widthClass) + Spacing.xs)
            }
        }
        .accessibilityIdentifier("channel.\(tab).row.\(item.id)")
    }

    private func shortCell(_ item: VideoItem) -> some View {
        Button {
            router.push(.shorts(viewModel.playerArgs(for: item, tab: .shorts)))
        } label: {
            ZStack(alignment: .bottomLeading) {
                RemoteImage(url: item.thumbnailURL)
                LinearGradient(colors: [.clear, .black.opacity(0.7)], startPoint: .center, endPoint: .bottom)
                Text(item.title).font(TypeScale.caption).fontWeight(.bold).foregroundStyle(.white)
                    .lineLimit(2).multilineTextAlignment(.leading).padding(Spacing.sm)
            }
            .aspectRatio(9.0 / 16.0, contentMode: .fit)
            .frame(maxWidth: .infinity)
            .clipShape(RoundedRectangle(cornerRadius: Radius.homeThumbnail))
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityLabel(item.title)
        .accessibilityIdentifier("channel.shorts.cell.\(item.id)")
    }

    // MARK: Pagination (ChannelTabAutofill, ruling 10)

    private func footerState(_ state: TabState<VideoItem>) -> TabState<VideoItem> {
        guard autofill.showsLoadMore, case .loaded(let items, let c, let appending, _) = state else { return state }
        return .loaded(items: items, continuation: c, isAppending: appending, showsLoadMore: true)
    }

    private func nearEnd(_ offset: Int, of count: Int) {
        // `>=`, not `==` (gate B1-I2): a failed load-more must stay recoverable.
        guard offset >= max(0, count - 5) else { return }
        // C T6 finding: while the whole list fits, every row's `onAppear` is "near the end", so this
        // trigger paged through the entire channel and the ruling-10 cap / Load-more button never
        // showed. A scroll trigger needs something to scroll; autofill owns the fitting case. The
        // row appears BEFORE `onContentFits` reports the layout that includes it, so the check is
        // deferred one turn -- read synchronously, `contentFits` is still the previous page's answer.
        Task { @MainActor in
            guard !contentFits else { return }
            triggerScrollLoadMore()
        }
    }

    private func triggerScrollLoadMore() {
        guard viewModel.visible(tab).continuation != nil, !isLoadingMore else { return }
        isLoadingMore = true
        Task { await runLoadMore() }
    }

    private func loadMoreTapped() {
        autofill.loadMoreTapped()
        triggerScrollLoadMore()
    }

    private func triggerAutoFill() {
        guard !isLoadingMore else { return }
        let state = viewModel.visible(tab)
        var attempt = autofill
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: state.continuation != nil,
                                     isAppending: state.isAppending, contentFits: contentFits) else {
            autofill = attempt
            return
        }
        let now = Date()
        if attempt.accepts(at: now) {
            attempt.recordAppend(accepted: true, at: now)
            autofill = attempt
            isLoadingMore = true
            Task { await runLoadMore() }
        } else if let delay = attempt.recordAppend(accepted: false, at: now) {
            autofill = attempt
            let generation = attempt.generation
            Task {
                try? await Task.sleep(for: .seconds(delay))
                guard autofill.generation == generation else { return }
                autofill.recheckFired()
                triggerAutoFill()
            }
        } else {
            autofill = attempt
        }
    }

    private func runLoadMore() async {
        _ = await viewModel.loadMore(tab)
        isLoadingMore = false
    }
}

// MARK: - Playlists tab

private struct ChannelPlaylistsTab: View {
    let viewModel: ChannelDetailViewModel

    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        ScrollView {
            let state = viewModel.visiblePlaylists
            switch state {
            case .idle, .loadingInitial:
                SkeletonListView().padding(Spacing.md(widthClass))
            case .empty(let key):
                ChannelTabStates.empty(key: key, widthClass: widthClass)
            case .errorInitial(let key):
                ChannelTabStates.error(key: key, viewModel: viewModel, tab: .playlists, widthClass: widthClass)
            case .loaded, .errorAppend:
                LazyVStack(spacing: 0) {
                    ForEach(state.items, id: \.id) { tile in
                        PlaylistRow(item: ContentItem(id: tile.id, type: .playlist, title: tile.title, category: nil, description: nil,
                                                      thumbnailURL: tile.thumbnailURL, durationSeconds: nil, uploadedDaysAgo: nil,
                                                      viewCount: nil, channelTitle: tile.channelName, subscribers: nil,
                                                      videoCount: nil, itemCount: nil),
                                    subtitle: tile.itemCountText) {
                            router.push(.playlist(id: tile.id, title: tile.title, category: nil, count: nil))
                        }
                        .accessibilityIdentifier("channel.playlists.row.\(tile.id)")
                    }
                }
                // ponytail: the Playlists tab's continuation shape is uncaptured (Task 1 ledger,
                // CF-C-3); the footer Retry/Load-more path lights up once Task 6 proves it pages.
                ListFooter(state: state, loadMore: {}, retry: { Task { _ = await viewModel.loadMore(.playlists) } })
            }
        }
    }
}

// MARK: - About tab (the header, no second call)

private struct ChannelAboutTab: View {
    let viewModel: ChannelDetailViewModel
    @Environment(\.widthClass) private var widthClass

    var body: some View {
        ScrollView {
            VStack(alignment: .leading, spacing: Spacing.md(widthClass)) {
                Text(String(localized: "channel_about_more_info")).font(TypeScale.subtitle).fontWeight(.bold)
                    .foregroundStyle(Color.textPrimary)
                ForEach(viewModel.aboutRows, id: \.key) { row in
                    Text(row.text).font(TypeScale.itemMeta).foregroundStyle(Color.textSecondary)
                        .accessibilityIdentifier("channel.about.\(row.key)")
                }
                Text(String(localized: "channel_about_description")).font(TypeScale.subtitle).fontWeight(.bold)
                    .foregroundStyle(Color.textPrimary)
                // `ChannelHeader` carries no description (and no links -- owner directive: a YouTube
                // link would be hidden anyway), so this is the honest state today.
                Text(String(localized: "channel_about_no_description")).font(TypeScale.itemMeta)
                    .foregroundStyle(Color.textSecondary)
                    .accessibilityIdentifier("channel.about.description")
            }
            .frame(maxWidth: .infinity, alignment: .leading)
            .padding(Spacing.md(widthClass))
        }
    }
}

// MARK: - Shared tab states

private enum ChannelTabStates {
    static func empty(key: String, widthClass: WidthClass) -> some View {
        EmptyStateView(systemImage: key == "search_no_results" ? "magnifyingglass" : "tray",
                       message: String(localized: String.LocalizationValue(key)))
            .padding(.top, Spacing.lg(widthClass))
    }

    @ViewBuilder
    static func error(key: String, viewModel: ChannelDetailViewModel, tab: ChannelTabKind, widthClass: WidthClass) -> some View {
        if viewModel.isUnavailable {
            EmptyStateView(systemImage: "exclamationmark.triangle.fill", iconColor: .accentRed,
                           title: String(localized: "content_unavailable_title"),
                           message: String(localized: String.LocalizationValue(key)))
                .padding(.top, Spacing.lg(widthClass))
        } else {
            // Ruling B: Live / Shorts / Playlists with no substitute show ERROR + Retry, never empty.
            ErrorStateView(message: String(localized: String.LocalizationValue(key))) {
                Task { await viewModel.reload(tab) }
            }
            .padding(.top, Spacing.lg(widthClass))
            .accessibilityIdentifier("channel.\(tab).error")
        }
    }
}
