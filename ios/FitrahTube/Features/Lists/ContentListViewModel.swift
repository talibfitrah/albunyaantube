import Foundation

/// Android's `ContentListViewModel` (`content-lists.md:24-400`), one instance per tab
/// (`ChannelsFragmentNew`/`PlaylistsFragmentNew`/`VideosFragmentNew` share this one class, differing
/// only by `type`). Follows Task 9's `HomeViewModel` pattern throughout: a `loadTask` for full
/// reloads (load/refresh/filter change) that supersedes any in-flight `loadMoreTask` (never the reverse --
/// the fix for Android's `loadJob`-cancels-everything race at `content-lists.md:239-243`), and
/// `refresh()` never shows a loading state -- it keeps whatever is already on screen while
/// `.refreshable`'s native spinner is the only visual cue (RULINGS #12 spirit).
///
/// `.loading` is the full-screen first load only. The pagination and refresh spinners are
/// `ContentListView`'s own `@State`. A tab never searches: Home's magnifier opens global search.
@MainActor @Observable final class ContentListViewModel {
    enum State: Equatable {
        case loading
        case content(items: [ContentItem], hasMore: Bool, paginationError: Bool)
        case error
    }

    private(set) var state: State = .loading

    /// Bumped on every terminal *and* every pagination failure. `ContentListView` drives both of
    /// its banners off this rather than off a Bool level (gate B1-I1): `refresh()` never passes
    /// through `.loading`, so a second consecutive refresh failure assigned `.error` over `.error`
    /// and the view's `.onChange(of:)` -- which only fires on a transition -- never refired. The
    /// user pulled to refresh, the spinner retracted, and nothing at all happened: no banner, no
    /// error, no state change. An event needs an event channel, not a level.
    private(set) var errorToken = 0

    private let type: ListType
    private let catalog: any CatalogClient
    private let filter: any FilterStore
    private let pageSize: Int

    private var items: [ContentItem] = []
    /// The filter `items` were actually fetched under. `nil` until the first success.
    private var itemsFilter: FilterState?

    /// Fix round 1, finding #1 (`content-lists.md:256,278-280`): a terminal load/refresh failure
    /// must keep the list visible, not blank it. `items` is only ever reassigned on a *successful*
    /// fetch (see `fetchFirstPage`/`fetchMore` below), so at the moment `.error` is set it already
    /// holds the last good page -- exposing it directly here is smaller than shadowing the same
    /// fact in a second, View-owned `@State`.
    /// ponytail: retention lives in the VM (already the source of truth for `items`), not
    /// `ContentListView`, to avoid two variables that must stay in sync for one fact.
    ///
    /// Gate B1-C2: that retention is scoped to the request the items belong to. `performFullLoad`
    /// is also the path for a filter change, so a failed reload used to leave the *previous*
    /// filter's list on screen under the *new* label -- 20 Fiqh channels under a "Category: Quran"
    /// chip, with nothing on screen saying it was stale. Retained data presented as the answer to a
    /// question it was never asked is worse than no data.
    var lastItems: [ContentItem] { itemsFilter == filter.state ? items : [] }
    private var nextCursor: String?
    private var hasMore = true
    private var isLoadingMore = false
    /// How many of `items` page 1 brought: the rows `refreshInPlace` replaces.
    private var firstPageCount = 0

    /// Gate wave-2 W2/W3 -- one mechanism, identical in `HomeViewModel` and `FeaturedViewModel`.
    /// `performFullLoad` (load, refresh, filter change) bumps `loadGeneration` and holds
    /// `isFullLoading` for its whole duration, which fixes two races at once:
    ///  - a load-more can no longer *start* against the cursor a full load is about to replace.
    ///    The old `!isRefreshing` guard covered only `refresh()`, not `load()`, and
    ///    `HomeViewModel` had no such guard at all -- so a load-more landing
    ///    after the reload produced new-page-1 + old-page-2 and a cursor from the dead sequence;
    ///  - a load-more that a full load cancelled while it was suspended can no longer, on its
    ///    late return, clear a *newer* load-more's in-flight guard (its `defer` used to fire
    ///    unconditionally) or append the page it fetched from the superseded cursor.
    private var loadGeneration = 0
    private var isFullLoading = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?

    init(type: ListType, catalog: any CatalogClient, filter: any FilterStore, pageSize: Int = 20) {
        self.type = type
        self.catalog = catalog
        self.filter = filter
        self.pageSize = pageSize
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// Gate wave-4 V2: SwiftUI restarts a `.task` every time a compact-width `TabView` tab is
    /// re-selected, so the views' `.task { await load() }` blanked every accumulated page back to
    /// the skeleton and refetched page 1 on each tab revisit -- cursor, deep pagination and scroll
    /// position gone, and only on phones (the iPad rail never removes a tab's stack, so the two
    /// behaved differently). First appearance still fetches exactly once; a later appearance with
    /// content is a no-op. Every other entry point keeps its own path: pull-to-refresh, filter
    /// change, and the error states' own Retry.
    func loadIfNeeded() async {
        guard case .loading = state else { return }
        await load()
    }

    /// Never shows `.loading` -- the caller's `.refreshable` holds its own spinner while the
    /// existing `.content`/`.error` stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isFullLoading else { return }
        await performFullLoad(showLoading: false)
    }

    /// The return after an idle spell (`Router.catalogToken`): page 1 again, IN PLACE. The pages loaded below it
    /// stay, and the user's place in them, unless page 1 changed; a failure changes nothing on screen -- no error
    /// state, no banner.
    func refreshInPlace() async {
        guard !isFullLoading else { return }
        await performFullLoad(showLoading: false, inPlace: true)
    }

    /// Returns whether the fetch actually ran. The view commits its `PaginationGuard` attempt only
    /// on `true` (gate wave-2 W2): `shouldAutoLoad` used to spend an attempt -- and advance
    /// `lastCount` -- on a load-more this guard then refused, and after a pull-to-refresh landed
    /// with the same item count, guard 5's progress invariant refused every later autofill.
    @discardableResult
    func loadMore() async -> Bool {
        guard hasMore, !isLoadingMore, !isFullLoading else { return false }
        isLoadingMore = true // set before the first `await` -- the in-flight guard
        let generation = loadGeneration
        let task = Task { await self.fetchMore(generation: generation) }
        loadMoreTask = task
        await task.value
        // Not `true` (gate wave-3 D1): a full load that cancelled this fetch while it was
        // suspended also bumped the generation, and the caller uses this answer to decide whether
        // to commit a spent `PaginationGuard` attempt over the guard that same refresh just reset.
        return generation == loadGeneration
    }

    /// Manual retry from the pagination-error `TransientBanner`. Identical to `loadMore()`: the
    /// cursor and `hasMore` survive a pagination failure (§2.5), so a plain retry is a plain
    /// load-more -- no separate code path needed.
    @discardableResult
    func retryPagination() async -> Bool { await loadMore() }

    private func performFullLoad(showLoading: Bool, inPlace: Bool = false) async {
        loadGeneration += 1
        let generation = loadGeneration
        isFullLoading = true
        loadTask?.cancel()
        loadMoreTask?.cancel()
        // A cancelled load-more returns through its own `guard !Task.isCancelled` *before* it can
        // clear this, so the flag latched pagination off for the rest of the session unless a
        // later full load happened to succeed (gate B1-I3). Cleared here, where the cancellation
        // actually happens.
        isLoadingMore = false
        let task = Task { await self.fetchFirstPage(showLoading: showLoading, inPlace: inPlace) }
        loadTask = task
        await task.value
        // A superseded full load (`load()` never refuses -- a filter change must win) returns here
        // late; only the newest one may reopen pagination.
        if generation == loadGeneration { isFullLoading = false }
    }

    private func fetchFirstPage(showLoading: Bool, inPlace: Bool) async {
        if showLoading { state = .loading }
        let requestFilter = filter.state
        do {
            let page = try await catalog.content(type: type, cursor: nil, limit: pageSize, filter: requestFilter)
            guard !Task.isCancelled else { return }
            if inPlace, itemsFilter == requestFilter, items.count > firstPageCount,
               Set(items.prefix(firstPageCount).map(\.id)) == Set(page.items.map(\.id)) {
                // `refreshInPlace` over more than page 1, and page 1 holds the same rows: the new page 1, then the
                // pages below it, whose (offset) cursor still lines up. A changed page 1 replaces the list instead: a
                // removed row must leave, and the old cursor would skip or repeat rows.
                items = page.items + items.dropFirst(firstPageCount)
            } else {
                items = page.items
                nextCursor = page.nextCursor
                hasMore = page.hasMore
            }
            firstPageCount = page.items.count
            itemsFilter = requestFilter
            state = .content(items: items, hasMore: hasMore, paginationError: false)
        } catch {
            guard !Task.isCancelled, !inPlace else { return }
            errorToken += 1
            state = .error // initial/refresh failure loses items, matching Android §2.5
        }
    }

    private func fetchMore(generation: Int) async {
        // Covers the cancellation exits below too (gate B1-I3), but only while this is still the
        // newest load-more: a superseded one returning late must not unlock a live guard (W2).
        defer { if generation == loadGeneration { isLoadingMore = false } }
        do {
            let page = try await catalog.content(type: type, cursor: nextCursor, limit: pageSize, filter: filter.state)
            guard !Task.isCancelled, generation == loadGeneration else { return }
            // Dedupe by id, mirroring `HomeViewModel.fetchMore` (gate B1-minor-6): the grid's
            // `ForEach(..., id: \.element.id)` is over blindly appended pages, so a cursor overlap
            // on the backend produces a SwiftUI identity collision and dropped/duplicated rows.
            let existingIDs = Set(items.map(\.id))
            items.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) })
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            state = .content(items: items, hasMore: hasMore, paginationError: false)
        } catch {
            // Pagination failure keeps accumulated items and the cursor (so a retry can still
            // work), just flags `paginationError` -- Android §2.5.
            guard !Task.isCancelled, generation == loadGeneration else { return }
            errorToken += 1
            state = .content(items: items, hasMore: hasMore, paginationError: true)
        }
    }
}
