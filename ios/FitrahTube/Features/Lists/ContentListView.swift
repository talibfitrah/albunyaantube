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
    @Environment(\.dynamicTypeSize) private var dynamicTypeSize

    @State private var viewModel: ContentListViewModel?
    @State private var containerWidth: CGFloat = 0
    @State private var isLoadingMore = false
    @State private var paginationGuard = PaginationGuard()
    @State private var bannerMessage: BannerMessage?
    /// Geometry *state*, not an event (gate B1-C1). `onContentFits` keeps this current; both it
    /// and every completed load then re-run the six `PaginationGuard` checks, which is what
    /// Android's post-`submitList` autofill callback does.
    @State private var contentFits = false

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
                .onContentFits { fits in
                    contentFits = fits
                    triggerAutoFill()
                }
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
        // One event channel for both banners (gate B1-I1). `errorToken` is bumped on every
        // failure, so a second consecutive failure re-fires this where the previous Bool-level
        // `.onChange` pair silently stopped after the first. Which banner to show is read off the
        // state the token was bumped for:
        //  - pagination failure -> Retry resumes from the surviving cursor;
        //  - terminal failure *with* prior content (fix round 1, finding #1;
        //    `content-lists.md:256,278-280`) -> the list stays on screen (see `stateContent`'s
        //    `.error` branch) and Retry re-runs a full `load()`, since a terminal failure leaves
        //    no cursor to resume from. With no prior content the full-page `ErrorStateView`
        //    carries its own retry and no banner is needed.
        .onChange(of: viewModel?.errorToken) { _, _ in
            guard let viewModel else { return }
            let retry: () -> Void
            if case .content(_, _, true, _) = viewModel.state {
                retry = { Task { await viewModel.retryPagination() } }
            } else if case .error = viewModel.state, !viewModel.lastItems.isEmpty {
                retry = { Task { await viewModel.load() } }
            } else {
                return
            }
            bannerMessage = BannerMessage(text: String(localized: "list_error_title"),
                                          actionTitle: String(localized: "retry"), action: retry)
        }
        // Every completed load re-arms autofill, so a filter change or a search whose first page
        // also fits gets its second round (gate B1-C1 scenarios 2-3). Safe against a retry storm:
        // a pagination failure leaves `paginationError` set, which `PaginationGuard`'s guard 3
        // refuses on.
        .onChange(of: viewModel?.state) { _, _ in triggerAutoFill() }
        .onChange(of: queryBinding.wrappedValue) { _, _ in paginationGuard = PaginationGuard() }
        // Fix round 1, finding #4: a category applied on the Categories/Subcategories screen
        // (Task 11) writes straight into `container.filters` and pops back here without ever
        // re-running this view's `.task` -- `ContentListViewModel` already re-reads `filter.state`
        // live on every fetch (unlike `HomeViewModel`, which snapshots it once at init), so a
        // plain `load()` is enough to pick up the change; no new ViewModel instance needed.
        // `.onChange` never fires for the value it's first attached with, so this doesn't cause
        // the double fetch a naive "reload on appear + reload on change" combo would.
        .onChange(of: container.filters.state) { _, _ in
            paginationGuard = PaginationGuard()
            Task { await viewModel?.load() }
        }
        .onGeometryChange(for: CGFloat.self) { $0.size.width } action: { containerWidth = $0 }
        .task {
            if viewModel == nil {
                viewModel = ContentListViewModel(type: type, catalog: container.catalog, filter: container.filters)
            }
            await viewModel?.load()
            #if DEBUG
            // Debug-only launch hook (fix round 1, finding #1 screenshot): simctl has no
            // pull-to-refresh gesture, so `-fitrah-fail-after-first-load` drives the same
            // `refresh()` a real swipe would call, against a stub server that fails starting on
            // its 2nd request -- reproducing "terminal error with existing content" for capture.
            if ProcessInfo.processInfo.arguments.contains("-fitrah-fail-after-first-load") {
                await viewModel?.refresh()
            }
            #endif
        }
    }

    // MARK: - Search bar (content-lists.md §4.1 -- inline, in-header, single 300 ms debounce, RULING 22)

    private var queryBinding: Binding<String> {
        Binding(get: { viewModel?.query ?? "" }, set: { viewModel?.query = $0 })
    }

    /// Shared chrome with `SearchView` (gate wave-2 W9); this one has neither auto-focus nor a
    /// submit action -- it is one element of a header, and only the debounced `query` change fetches.
    private var searchBar: some View {
        SearchField(text: queryBinding, accessibilityLabel: String(localized: "cd_search_icon"))
    }

    // MARK: - Filter chip (content-lists.md §4.9 -- "Category: %1$@" + Clear)

    @ViewBuilder
    private var filterChipRow: some View {
        if hasActiveFilter, let categoryId = container.filters.state.categoryId {
            let categoryName = container.filters.state.categoryName ?? categoryId
            HStack(spacing: Spacing.xs) {
                CategoryChip(text: Format.localizedFormat("filtering_by_category", locale: locale, categoryName))
                Button(action: clearFilter) {
                    Image(systemName: "xmark.circle.fill").foregroundStyle(Color.brand)
                }
                .accessibilityLabel(String(localized: "clear_filters"))
            }
            .padding(.horizontal, Spacing.md(widthClass))
            .padding(.top, Spacing.sm)
            .accessibilityElement(children: .combine)
            // Label = role, value = the data (gate B1-I7). `category_filter_active` alone
            // ("Active category filter. Double tap to clear.") overwrote the visible
            // "Category: %1$@" text, so a VoiceOver user could not tell *which* category was
            // filtering the list -- the one thing this chip exists to say.
            .accessibilityLabel(String(localized: "category_filter_active"))
            .accessibilityValue(categoryName)
        }
    }

    /// Fix round 1, finding #4: only mutates the shared store now -- the guard-reset + reload
    /// used to happen here too, but that's now the `.onChange(of: container.filters.state)`
    /// modifier's job (it fires for *any* origin, including this one), so doing both here as well
    /// would fire two reloads for one tap.
    private func clearFilter() {
        container.filters.clearCategory()
    }

    /// One predicate for "a category filter is active" (gate B1-minor-2). The chip row used to
    /// require a non-empty id while the empty state's Clear button only checked `!= nil`, so a
    /// legacy empty-string value (`UserDefaultsFilterStore` normalizes empties on write but not on
    /// read) offered "Clear filter" with no chip above it.
    private var hasActiveFilter: Bool { !(container.filters.state.categoryId ?? "").isEmpty }

    // MARK: - State body (content-lists.md §4.5 visibility matrix)

    @ViewBuilder
    private var stateContent: some View {
        if let viewModel {
            switch viewModel.state {
            case .loading:
                skeletonView
            case .error:
                // Fix round 1, finding #1: only the *first* load ever failing (nothing to show)
                // gets the full-page error; a load/refresh failure after content already existed
                // keeps that content on screen (via `lastItems`) plus the banner set by the
                // `.onChange(of: viewModel?.errorToken)` modifier above.
                if !viewModel.lastItems.isEmpty {
                    contentGrid(items: viewModel.lastItems, hasMore: false)
                } else {
                    ErrorStateView(message: String(localized: "list_error_description")) {
                        Task { await viewModel.load() }
                    }
                    .containerRelativeFrame(.vertical)
                }
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

    /// RULINGS #16: the skeleton mirrors the layout it stands in for. Channels/playlists used a
    /// fixed single-column `SkeletonListView` at every width class while the real content is 3-4
    /// columns on a tablet (gate B1-minor-17) -- which also made the skeleton *taller* than the
    /// content it swapped to, and that height drop was the accidental `false -> true` transition
    /// the old edge-triggered autofill depended on (gate B1-C1).
    @ViewBuilder
    private var skeletonView: some View {
        switch type {
        case .videos:
            SkeletonGrid(columns: videoColumns, rows: 3)
        case .channels, .playlists:
            let columns = GridRules.columns(GridRules.listColumns(widthClass), dynamicTypeSize: dynamicTypeSize)
            if columns > 1 {
                SkeletonGrid(columns: columns, rows: 3)
            } else {
                SkeletonListView()
            }
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

    /// Both column rules funnel through `GridRules.columns(_:dynamicTypeSize:)`, which forces a
    /// single column at accessibility text sizes (spec §14).
    private var videoColumns: Int {
        GridRules.columns(GridRules.videoColumns(width: containerWidth > 0 ? containerWidth : 375),
                          dynamicTypeSize: dynamicTypeSize)
    }

    private var gridColumns: [GridItem] {
        let count = type == .videos ? videoColumns : GridRules.columns(GridRules.listColumns(widthClass),
                                                                      dynamicTypeSize: dynamicTypeSize)
        return Array(repeating: GridItem(.flexible(), spacing: Spacing.sm), count: max(1, count))
    }

    private func contentGrid(items: [ContentItem], hasMore: Bool) -> some View {
        VStack(spacing: 0) {
            LazyVGrid(columns: gridColumns, spacing: Spacing.md(widthClass)) {
                ForEach(Array(items.enumerated()), id: \.element.id) { index, item in
                    rowView(item)
                        .onAppear {
                            // `>=`, not `==` (gate B1-I2, `content-lists.md:210-213`). With `==`,
                            // a load-more that fired at index count-5 and then failed was
                            // unrecoverable: `items.count` is unchanged, so the threshold index is
                            // still count-5 and that cell has already appeared -- scrolling on
                            // through the remaining four rows did nothing, and after the banner
                            // auto-dismissed the only way out was a pull-to-refresh that discarded
                            // every loaded page. `hasMore`/`!isLoadingMore` below still stop a storm.
                            guard index >= max(0, items.count - 5) else { return }
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

    /// Row shape per tab; the destination itself comes from the one shared item→route mapping
    /// (gate wave-2 W9).
    @ViewBuilder
    private func rowView(_ item: ContentItem) -> some View {
        switch type {
        case .channels:
            ChannelRow(item: item) { router.push(Route(item: item)) }
        case .playlists:
            PlaylistRow(item: item) { router.push(Route(item: item)) }
        case .videos:
            VideoGridCell(item: item) { router.push(Route(item: item)) }
        }
    }

    // MARK: - Pagination triggers (content-lists.md §4.3: scroll threshold + guarded content-fits autofill)

    private func triggerScrollLoadMore(hasMore: Bool) {
        guard hasMore, !isLoadingMore else { return }
        // Set synchronously, *before* the Task (gate wave-2 W2): several cells can appear in one
        // frame and each used to spawn its own Task, since the flag only flipped inside one. The
        // second Task bounced off the ViewModel's own guard and then cleared this flag while the
        // first fetch was still in flight -- the footer spinner disappeared mid-load. One trigger
        // per in-flight fetch now, so the spinner stays up for the whole of it.
        isLoadingMore = true
        Task { await runLoadMore() }
    }

    private func triggerAutoFill() {
        guard !isLoadingMore, let viewModel, case .content(let items, let hasMore, let paginationError, _) = viewModel.state else { return }
        // The attempt is committed only if the ViewModel actually starts the fetch (gate wave-2
        // W2). `shouldAutoLoad` mutates: during a pull-to-refresh, layout churn triggered an
        // autofill the ViewModel then refused, but the attempt was already spent *and* `lastCount`
        // advanced -- so once the refresh landed with the same item count, guard 5's progress
        // invariant refused every later autofill and a fits-on-screen iPad page never paginated
        // again until the user pulled a second time.
        var attempt = paginationGuard
        guard attempt.shouldAutoLoad(widthClass: widthClass, hasMore: hasMore, paginationError: paginationError,
                                      contentFits: contentFits, itemCount: items.count) else { return }
        isLoadingMore = true
        Task { if await runLoadMore() { paginationGuard = attempt } }
    }

    @discardableResult
    private func runLoadMore() async -> Bool {
        let started = await viewModel?.loadMore() ?? false
        isLoadingMore = false
        return started
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
