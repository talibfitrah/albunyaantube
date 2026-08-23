import SwiftUI

/// Android's `ChannelsFragmentNew`/`PlaylistsFragmentNew`/`VideosFragmentNew` (`content-lists.md:24-491`),
/// one shared view for all three tabs, differing only by `type`: column rule (`GridRules`), row/cell
/// shape, tap destination, Categories toolbar button (Channels only, RULINGS #15), and empty-state
/// copy. Follows `HomeView`'s structure (Task 9): header pieces outside the scroll, all four
/// (loading/content/empty/error) states swapped inside one `.refreshable` `ScrollView` so
/// pull-to-refresh always works (RULINGS #12).
struct ContentListView: View {
    let type: ListType

    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.widthClass) private var widthClass
    @Environment(\.locale) private var locale

    @State private var viewModel: ContentListViewModel?
    @State private var containerWidth: CGFloat = 0
    @State private var isLoadingMore = false
    @State private var paginationGuard = PaginationGuard()
    @State private var bannerMessage: BannerMessage?

    private static let topAnchor = "content-list-top"

    var body: some View {
        VStack(spacing: 0) {
            searchBar
            filterChipRow
            ScrollViewReader { proxy in
                ScrollView {
                    VStack(spacing: 0) {
                        Color.clear.frame(height: 0).id(Self.topAnchor)
                        stateContent
                    }
                }
                .refreshable {
                    guard queryBinding.wrappedValue.isEmpty else { return } // suppressed while searching -- content-lists.md §4.4
                    paginationGuard = PaginationGuard()
                    await viewModel?.refresh()
                }
                .onContentFits { fits in triggerAutoFill(contentFits: fits) }
                .onChange(of: router.scrollToTopSignal) { _, signal in
                    guard signal?.tab == tab else { return }
                    withAnimation { proxy.scrollTo(Self.topAnchor, anchor: .top) }
                }
            }
        }
        .background(Color.background.ignoresSafeArea())
        .navigationTitle(navTitle)
        .toolbar {
            if type == .channels {
                ToolbarItem(placement: .topBarTrailing) {
                    Button { router.push(.categories) } label: {
                        Image(systemName: "square.grid.2x2")
                    }
                    .accessibilityLabel(String(localized: "categories"))
                }
            }
        }
        .transientBanner($bannerMessage)
        .onChange(of: paginationErrorFlag) { _, isError in
            guard isError else { return }
            bannerMessage = BannerMessage(text: String(localized: "list_error_title"), actionTitle: String(localized: "retry")) {
                Task { await viewModel?.retryPagination() }
            }
        }
        .onChange(of: queryBinding.wrappedValue) { _, _ in paginationGuard = PaginationGuard() }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { containerWidth = $0 }
        .task {
            if viewModel == nil {
                viewModel = ContentListViewModel(type: type, catalog: container.catalog, filter: container.filters)
            }
            await viewModel?.load()
        }
    }

    // MARK: - Search bar (content-lists.md §4.1 -- inline, in-header, single 300 ms debounce, RULING 22)

    private var queryBinding: Binding<String> {
        Binding(get: { viewModel?.query ?? "" }, set: { viewModel?.query = $0 })
    }

    private var searchBar: some View {
        HStack(spacing: Spacing.sm) {
            Image(systemName: "magnifyingglass").foregroundStyle(Color.textSecondary)
            TextField(String(localized: "search_hint"), text: queryBinding)
                .textFieldStyle(.plain)
                .accessibilityLabel(String(localized: "cd_search_icon"))
            if !queryBinding.wrappedValue.isEmpty {
                Button { queryBinding.wrappedValue = "" } label: {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.textSecondary)
                }
                .accessibilityLabel(String(localized: "search_clear"))
            }
        }
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.vertical, Spacing.sm)
        .background(Color.surfaceVariant, in: RoundedRectangle(cornerRadius: Radius.pill))
        .padding(.horizontal, Spacing.md(widthClass))
        .padding(.top, Spacing.sm)
    }

    // MARK: - Filter chip (content-lists.md §4.9 -- "Category: %1$@" + Clear)

    @ViewBuilder
    private var filterChipRow: some View {
        if let categoryId = container.filters.state.categoryId, !categoryId.isEmpty {
            HStack(spacing: Spacing.xs) {
                CategoryChip(text: localizedFormat("filtering_by_category", container.filters.state.categoryName ?? categoryId))
                Button(action: clearFilter) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.brand)
                }
            }
            .padding(.horizontal, Spacing.md(widthClass))
            .padding(.top, Spacing.sm)
            .accessibilityElement(children: .combine)
            .accessibilityLabel(String(localized: "category_filter_active"))
        }
    }

    private func clearFilter() {
        container.filters.clearCategory()
        paginationGuard = PaginationGuard()
        Task { await viewModel?.load() }
    }

    private var hasActiveFilter: Bool { container.filters.state.categoryId != nil }

    // MARK: - State body (content-lists.md §4.5 visibility matrix)

    @ViewBuilder
    private var stateContent: some View {
        if let viewModel {
            switch viewModel.state {
            case .loading:
                skeletonView
            case .error:
                ErrorStateView(message: String(localized: "list_error_description")) {
                    Task { await viewModel.load() }
                }
                .containerRelativeFrame(.vertical)
            case .content(let items, let hasMore, _, let isSearchActive):
                if items.isEmpty {
                    if isSearchActive {
                        searchEmptyState.containerRelativeFrame(.vertical)
                    } else {
                        typeEmptyState.containerRelativeFrame(.vertical)
                    }
                } else {
                    contentGrid(items: items, hasMore: hasMore)
                }
            }
        }
    }

    @ViewBuilder
    private var skeletonView: some View {
        switch type {
        case .videos:
            SkeletonGrid(columns: videoColumns, rows: 3)
        case .channels, .playlists:
            SkeletonListView()
        }
    }

    private var searchEmptyState: some View {
        EmptyStateView(systemImage: "magnifyingglass", title: String(localized: "search_no_results"),
                        message: String(localized: "search_try_different_hint"))
    }

    /// RULINGS #11: an active category filter adds a "Clear filter" action to the per-type empty copy.
    private var typeEmptyState: some View {
        EmptyStateView(systemImage: emptyIcon, title: emptyTitle, message: emptySubtitle, action: emptyStateClearAction)
    }

    private var emptyStateClearAction: (title: String, run: () -> Void)? {
        guard hasActiveFilter else { return nil }
        return (title: String(localized: "clear_filter"), run: clearFilter)
    }

    // MARK: - Content grid (content-lists.md §4.2 columns, §4.3 auto-load, §4.10 taps)

    private var videoColumns: Int { GridRules.videoColumns(width: containerWidth > 0 ? containerWidth : 375) }

    private var gridColumns: [GridItem] {
        let count = type == .videos ? videoColumns : GridRules.listColumns(widthClass)
        return Array(repeating: GridItem(.flexible(), spacing: Spacing.sm), count: max(1, count))
    }

    private func contentGrid(items: [ContentItem], hasMore: Bool) -> some View {
        VStack(spacing: 0) {
            LazyVGrid(columns: gridColumns, spacing: Spacing.md(widthClass)) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    rowView(item)
                        .onAppear {
                            guard index == max(0, items.count - 5) else { return } // last visible >= count-5
                            triggerScrollLoadMore(hasMore: hasMore)
                        }
                }
            }
            .padding(.horizontal, Spacing.md(widthClass))
            .padding(.top, Spacing.sm)
            if isLoadingMore {
                ProgressView()
                    .tint(.brand)
                    .frame(maxWidth: .infinity)
                    .padding(.vertical, Spacing.md(widthClass))
                    .accessibilityLabel(String(localized: "loading_more"))
            }
        }
    }

    @ViewBuilder
    private func rowView(_ item: ContentItem) -> some View {
        switch type {
        case .channels:
            ChannelRow(item: item) { router.push(.channel(id: item.id, name: item.title, avatarURL: item.thumbnailURL)) }
        case .playlists:
            PlaylistRow(item: item) { router.push(.playlist(id: item.id, title: item.title, category: item.category, count: item.itemCount)) }
        case .videos:
            VideoGridCell(item: item) { router.push(.player(playerArgs(for: item))) }
        }
    }

    /// RULINGS #17: `channelName` prefers the video's real `channelTitle`, falling back to
    /// `category` only when nil (matches `HomeViewModel.playerArgs`).
    private func playerArgs(for item: ContentItem) -> PlayerArgs {
        PlayerArgs(videoId: item.id, title: item.title, channelName: item.channelTitle ?? item.category,
                   thumbnailURL: item.thumbnailURL, description: item.description,
                   durationSeconds: item.durationSeconds, viewCount: item.viewCount)
    }

    // MARK: - Pagination triggers (content-lists.md §4.3: scroll threshold + guarded content-fits autofill)

    private func triggerScrollLoadMore(hasMore: Bool) {
        guard hasMore, !isLoadingMore else { return }
        Task { await runLoadMore() }
    }

    private func triggerAutoFill(contentFits: Bool) {
        guard !isLoadingMore, let viewModel, case .content(let items, let hasMore, let paginationError, _) = viewModel.state else { return }
        guard paginationGuard.shouldAutoLoad(widthClass: widthClass, hasMore: hasMore, paginationError: paginationError,
                                              contentFits: contentFits, itemCount: items.count) else { return }
        Task { await runLoadMore() }
    }

    private func runLoadMore() async {
        isLoadingMore = true
        await viewModel?.loadMore()
        isLoadingMore = false
    }

    private var paginationErrorFlag: Bool {
        guard let viewModel, case .content(_, _, let paginationError, _) = viewModel.state else { return false }
        return paginationError
    }

    // MARK: - Per-type copy (content-lists.md §4.6, §A2 tab titles)

    private var tab: Tab {
        switch type {
        case .channels: .channels
        case .playlists: .playlists
        case .videos: .videos
        }
    }

    private var navTitle: String {
        switch type {
        case .channels: String(localized: "nav_channels")
        case .playlists: String(localized: "nav_playlists")
        case .videos: String(localized: "nav_videos")
        }
    }

    private var emptyIcon: String {
        switch type {
        case .channels: "tv"
        case .playlists: "list.bullet.rectangle"
        case .videos: "film.stack"
        }
    }

    private var emptyTitle: String {
        switch type {
        case .channels: String(localized: "channels_empty_title")
        case .playlists: String(localized: "playlists_empty_title")
        case .videos: String(localized: "videos_empty_title")
        }
    }

    private var emptySubtitle: String {
        switch type {
        case .channels: String(localized: "channels_empty_subtitle")
        case .playlists: String(localized: "playlists_empty_subtitle")
        case .videos: String(localized: "videos_empty_subtitle")
        }
    }

    /// Same technique as `Components.swift`'s private `localizedFormat` (that one isn't visible
    /// here) -- resolves the `.lproj` bundle for the current `\.locale` so a `%1$@` xcstrings entry
    /// substitutes correctly regardless of the simulator's system language.
    private func localizedFormat(_ key: String, _ args: CVarArg...) -> String {
        let format = Format.localizedBundle(for: locale).localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: locale, arguments: args)
    }
}

#Preview {
    MainShellView()
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    MainShellView()
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
