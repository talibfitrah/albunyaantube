import Foundation

/// Android's `ContentState.Loading`'s `LoadingType` (`content-lists.md:246-250`). `.refresh` and
/// `.pagination` exist for API completeness with the brief's `case loading(LoadKind)`, but this
/// ViewModel only ever constructs `.loading(.initial)` -- see the file-level note below.
nonisolated enum LoadKind: Sendable, Equatable { case initial, refresh, pagination }

/// Android's `ContentListViewModel` (`content-lists.md:24-400`), one instance per tab
/// (`ChannelsFragmentNew`/`PlaylistsFragmentNew`/`VideosFragmentNew` share this one class, differing
/// only by `type`). Follows Task 9's `HomeViewModel` pattern throughout: a `loadTask` for full
/// reloads (load/refresh/search) that supersedes any in-flight `loadMoreTask` (never the reverse --
/// the fix for Android's `loadJob`-cancels-everything race at `content-lists.md:239-243`), and
/// `refresh()` never shows a loading state -- it keeps whatever is already on screen while
/// `.refreshable`'s native spinner is the only visual cue (RULINGS #12 spirit).
///
/// `.loading(.refresh)`/`.loading(.pagination)` are never actually emitted: `.content` here has no
/// `isLoadingMore` field (unlike `HomeViewModel.State.content`), and `PaginationGuard.shouldAutoLoad`
/// takes no such flag either -- both are the given brief interfaces verbatim -- so the pagination
/// spinner and refresh spinner are necessarily View-local UI concerns (`ContentListView`'s own
/// `@State`), not part of this type. `.loading(.initial)` is the only kind ever set: on `load()` and
/// on every debounced query change (search resets pagination and shows the full skeleton, matching
/// Android's `setSearchQuery` -> `loadContent()` -> `Loading(INITIAL)`, `content-lists.md:59-63`).
@MainActor @Observable final class ContentListViewModel {
    enum State: Equatable {
        case loading(LoadKind)
        case content(items: [ContentItem], hasMore: Bool, paginationError: Bool, isSearchActive: Bool)
        case error
    }

    private(set) var state: State = .loading(.initial)

    /// Bumped on every terminal *and* every pagination failure. `ContentListView` drives both of
    /// its banners off this rather than off a Bool level (gate B1-I1): `refresh()` never passes
    /// through `.loading`, so a second consecutive refresh failure assigned `.error` over `.error`
    /// and the view's `.onChange(of:)` -- which only fires on a transition -- never refired. The
    /// user pulled to refresh, the spinner retracted, and nothing at all happened: no banner, no
    /// error, no state change. An event needs an event channel, not a level.
    private(set) var errorToken = 0

    /// Bound directly by `ContentListView`'s search field. No fragment-level pre-debounce (RULING
    /// 22 collapses Android's two composed 300 ms timers into this one). <2 chars still updates
    /// `isSearchActive` and still re-fetches (matching Android's "any change re-fetches"), just
    /// without a `q` param -- the server requires ≥2 chars, so below that the fetch simply returns
    /// the unfiltered list.
    var query: String = "" {
        didSet {
            guard query != oldValue else { return }
            searchTask?.cancel()
            let task = Task { [sleep] in
                do { try await sleep(.milliseconds(300)) } catch { return }
                guard !Task.isCancelled else { return }
                await self.performFullLoad(showLoading: true)
            }
            searchTask = task
        }
    }

    private let type: ListType
    private let catalog: any CatalogClient
    private let filter: any FilterStore
    private let pageSize: Int
    private let sleep: @Sendable (Duration) async throws -> Void

    private var items: [ContentItem] = []
    /// The `(filter, query)` `items` were actually fetched under. `nil` until the first success.
    private var itemsFilter: FilterState?
    private var itemsQuery: String?

    /// Fix round 1, finding #1 (`content-lists.md:256,278-280`): a terminal load/refresh failure
    /// must keep the list visible, not blank it. `items` is only ever reassigned on a *successful*
    /// fetch (see `fetchFirstPage`/`fetchMore` below), so at the moment `.error` is set it already
    /// holds the last good page -- exposing it directly here is smaller than shadowing the same
    /// fact in a second, View-owned `@State`.
    /// ponytail: retention lives in the VM (already the source of truth for `items`), not
    /// `ContentListView`, to avoid two variables that must stay in sync for one fact.
    ///
    /// Gate B1-C2: that retention is scoped to the request the items belong to. `performFullLoad`
    /// is also the path for a filter change and for a search-query change, so a failed reload used
    /// to leave the *previous* query's list on screen under the *new* label -- 20 Fiqh channels
    /// under a "Category: Quran" chip, or the pre-search list under a non-empty search field, with
    /// nothing on screen saying it was stale. Retained data presented as the answer to a question
    /// it was never asked is worse than no data.
    var lastItems: [ContentItem] {
        (itemsFilter == filter.state && itemsQuery == queryParam) ? items : []
    }
    private var nextCursor: String?
    private var hasMore = true
    private var isLoadingMore = false
    private var isRefreshing = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?
    /// Visible (not `private`) only so tests can `await vm.searchTask?.value` -- same rationale as
    /// `HomeViewModel.loadTask`.
    private(set) var searchTask: Task<Void, Never>?

    init(type: ListType, catalog: any CatalogClient, filter: any FilterStore, pageSize: Int = 20,
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.type = type
        self.catalog = catalog
        self.filter = filter
        self.pageSize = pageSize
        self.sleep = sleep
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// Never shows `.loading` -- the caller's `.refreshable` holds its own spinner while the
    /// existing `.content`/`.error` stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        await performFullLoad(showLoading: false)
        isRefreshing = false
    }

    func loadMore() async {
        guard hasMore, !isLoadingMore, !isRefreshing else { return }
        isLoadingMore = true // set before the first `await` -- the in-flight guard
        let task = Task { await self.fetchMore() }
        loadMoreTask = task
        await task.value
    }

    /// Manual retry from the pagination-error `TransientBanner`. Identical to `loadMore()`: the
    /// cursor and `hasMore` survive a pagination failure (§2.5), so a plain retry is a plain
    /// load-more -- no separate code path needed.
    func retryPagination() async { await loadMore() }

    private func performFullLoad(showLoading: Bool) async {
        loadTask?.cancel()
        loadMoreTask?.cancel()
        // A cancelled load-more returns through its own `guard !Task.isCancelled` *before* it can
        // clear this, so the flag latched pagination off for the rest of the session unless a
        // later full load happened to succeed (gate B1-I3). Cleared here, where the cancellation
        // actually happens.
        isLoadingMore = false
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
    }

    private func fetchFirstPage(showLoading: Bool) async {
        if showLoading { state = .loading(.initial) }
        let requestFilter = filter.state
        let requestQuery = queryParam
        do {
            let page = try await catalog.content(type: type, cursor: nil, limit: pageSize, filter: requestFilter, query: requestQuery)
            guard !Task.isCancelled else { return }
            items = page.items
            itemsFilter = requestFilter
            itemsQuery = requestQuery
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            state = .content(items: items, hasMore: hasMore, paginationError: false, isSearchActive: isSearchActive)
        } catch {
            guard !Task.isCancelled else { return }
            errorToken += 1
            state = .error // initial/refresh failure loses items, matching Android §2.5
        }
    }

    private func fetchMore() async {
        defer { isLoadingMore = false } // covers the cancellation exits below too (gate B1-I3)
        do {
            let page = try await catalog.content(type: type, cursor: nextCursor, limit: pageSize, filter: filter.state, query: queryParam)
            guard !Task.isCancelled else { return }
            // Dedupe by id, mirroring `HomeViewModel.fetchMore` (gate B1-minor-6): the grid's
            // `ForEach(..., id: \.element.id)` is over blindly appended pages, so a cursor overlap
            // on the backend produces a SwiftUI identity collision and dropped/duplicated rows.
            let existingIDs = Set(items.map(\.id))
            items.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) })
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            state = .content(items: items, hasMore: hasMore, paginationError: false, isSearchActive: isSearchActive)
        } catch {
            // Pagination failure keeps accumulated items and the cursor (so a retry can still
            // work), just flags `paginationError` -- Android §2.5.
            guard !Task.isCancelled else { return }
            errorToken += 1
            state = .content(items: items, hasMore: hasMore, paginationError: true, isSearchActive: isSearchActive)
        }
    }

    private var isSearchActive: Bool { !query.isEmpty }

    /// RULING 22: the server requires ≥2 chars; below that `q` is omitted entirely (falls back to
    /// the unfiltered list), not sent as a too-short string. Interpretation accepted as-is for
    /// these browse tabs: <2 chars means "show the unfiltered list", not "no results" / no fetch.
    private var queryParam: String? { query.count >= 2 ? query : nil }
}
