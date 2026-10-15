import Foundation

/// Android's `ContentListViewModel` (`content-lists.md:24-400`), one instance per tab
/// (`ChannelsFragmentNew`/`PlaylistsFragmentNew`/`VideosFragmentNew` share this one class, differing
/// only by `type`). Follows Task 9's `HomeViewModel` pattern throughout: a `loadTask` for full
/// reloads (load/refresh/search) that supersedes any in-flight `loadMoreTask` (never the reverse --
/// the fix for Android's `loadJob`-cancels-everything race at `content-lists.md:239-243`), and
/// `refresh()` never shows a loading state -- it keeps whatever is already on screen while
/// `.refreshable`'s native spinner is the only visual cue (RULINGS #12 spirit).
///
/// `.loading` is the full-screen first load only: on `load()`, and on a search only when nothing is
/// on screen yet -- otherwise the list stays and `isSearching` drives a small spinner (spec §6.2,
/// 2026-10-13). The pagination and refresh spinners are `ContentListView`'s own `@State`.
@MainActor @Observable final class ContentListViewModel {
    enum State: Equatable {
        case loading
        case content(items: [ContentItem], hasMore: Bool, paginationError: Bool, isSearchActive: Bool)
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

    /// Spec §6.2: true while a search-query reload runs with content still on screen.
    private(set) var isSearching = false

    /// Bound directly by `ContentListView`'s search field. No fragment-level pre-debounce (RULING
    /// 22 collapses Android's two composed 300 ms timers into this one). <2 chars still updates
    /// `isSearchActive`, and the fetch omits `q` -- the server requires ≥2 chars, so below that it
    /// returns the unfiltered list. A change that leaves the request as it is on screen (an undone
    /// keystroke, trailing spaces) fetches nothing -- unless a reload asked for that list again.
    var query: String = "" {
        // Read before the field moves on: `lastItems` empties once the text no longer matches them.
        willSet { if state == .error, !lastItems.isEmpty { keepsFailedList = true } }
        didSet {
            guard query != oldValue else { return }
            submitted = false
            // The keystroke drops the search already in flight, not only its debounce, so an older
            // answer or error never lands under the newer text.
            searchTask?.cancel()
            loadTask?.cancel()
            // Back on the query whose results are on screen: nothing to reload. Unless a refresh or a
            // reload asked for those rows again and none has landed: the debounce must run it.
            if case .content = state, itemsFilter == filter.state, itemsQuery == queryParam, !reloadPending {
                isSearching = false
                return
            }
            // A failed refresh leaves its list on screen with the banner; it stays there under the
            // search, as it does over `.content`, instead of turning into the error page.
            if keepsFailedList, case .error = state {
                state = .content(items: items, hasMore: hasMore, paginationError: false, isSearchActive: isSearchActive)
            }
            // An emptied field brings the full list back at once (stage 3a S3-3, Android parity):
            // only a typed query waits out the debounce.
            let debounces = !query.isEmpty
            let task = Task { [sleep] in
                if debounces {
                    do { try await sleep(.milliseconds(300)) } catch { return }
                }
                guard !Task.isCancelled else { return }
                await self.search()
            }
            searchTask = task
        }
    }

    /// The keyboard's Search key searches the field at once -- no debounce and no 2-character floor
    /// (RULINGS #22; the server answers a 1-character `q` with the plain list). On the text a live
    /// fetch is already asking for it changes nothing; a debounce still pending searches now.
    func submit() async {
        submitted = true
        if isFullLoading, loadTask?.isCancelled == false, loadQuery == queryParam { return }
        searchTask?.cancel()
        let task = Task { await self.search() }
        searchTask = task
        await task.value
    }

    private let type: ListType
    private let catalog: any CatalogClient
    private let filter: any FilterStore
    private let pageSize: Int
    private let sleep: @Sendable (Duration) async throws -> Void
    private let announce: @MainActor (String) -> Void

    private var items: [ContentItem] = []
    /// The `(filter, query)` `items` were actually fetched under. `nil` until the first success.
    private var itemsFilter: FilterState?
    /// Readable by `ContentListView`, which scrolls to the top when it changes: a new query's
    /// results replaced the list. A page appended below or a refresh leaves it as it is.
    /// `fetchMore` pages with it, so a page always pairs with the query its cursor came from.
    private(set) var itemsQuery: String?
    /// The `q` the newest full load fetches, so the Search key can tell it is already running.
    private var loadQuery: String?
    /// The field changed over a failed refresh's retained list: it stays on screen until the next
    /// first page lands or fails.
    private var keepsFailedList = false
    /// Set by `refresh()` (Android's `shownQuery = null`) and by any first page that fails, cleared
    /// when one lands: until then the rows on screen are no answer an undone keystroke can return
    /// to -- the reload asked for has not happened, whether it is in flight, was cancelled by the
    /// keystroke, or failed (a refresh, the banner's Retry, a Search-key resubmit).
    private var reloadPending = false

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

    /// Gate wave-2 W2/W3 -- one mechanism, identical in `HomeViewModel` and `FeaturedViewModel`.
    /// `performFullLoad` (load, refresh, search, filter change) bumps `loadGeneration` and holds
    /// `isFullLoading` for its whole duration, which fixes two races at once:
    ///  - a load-more can no longer *start* against the cursor a full load is about to replace.
    ///    The old `!isRefreshing` guard covered only `refresh()`, not `load()` or the debounced
    ///    search path, and `HomeViewModel` had no such guard at all -- so a load-more landing
    ///    after the reload produced new-page-1 + old-page-2 and a cursor from the dead sequence;
    ///  - a load-more that a full load cancelled while it was suspended can no longer, on its
    ///    late return, clear a *newer* load-more's in-flight guard (its `defer` used to fire
    ///    unconditionally) or append the page it fetched from the superseded cursor.
    private var loadGeneration = 0
    private var isFullLoading = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?
    /// Visible (not `private`) only so tests can `await vm.searchTask?.value` -- same rationale as
    /// `HomeViewModel.loadTask`.
    private(set) var searchTask: Task<Void, Never>?

    init(type: ListType, catalog: any CatalogClient, filter: any FilterStore, pageSize: Int = 20,
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) },
         announce: @escaping @MainActor (String) -> Void = postAnnouncement) {
        self.type = type
        self.catalog = catalog
        self.filter = filter
        self.pageSize = pageSize
        self.sleep = sleep
        self.announce = announce
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// Gate wave-4 V2: SwiftUI restarts a `.task` every time a compact-width `TabView` tab is
    /// re-selected, so the views' `.task { await load() }` blanked every accumulated page back to
    /// the skeleton and refetched page 1 on each tab revisit -- cursor, deep pagination and scroll
    /// position gone, and only on phones (the iPad rail never removes a tab's stack, so the two
    /// behaved differently). First appearance still fetches exactly once; a later appearance with
    /// content is a no-op. Every other entry point keeps its own path: pull-to-refresh, filter
    /// change, the debounced query, and the error states' own Retry.
    func loadIfNeeded() async {
        guard case .loading = state else { return }
        await load()
    }

    /// Never shows `.loading` -- the caller's `.refreshable` holds its own spinner while the
    /// existing `.content`/`.error` stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isFullLoading else { return }
        reloadPending = true
        await performFullLoad(showLoading: false)
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

    /// The debounced keystroke and the Search key share this. A list on screen stays while the next
    /// one loads (spec §6.2); a failure still clears it through `.error` + `lastItems` (gate B1-C2).
    private func search() async {
        // A failed refresh's list stays under the Search key too, as it does under a keystroke
        // (`keepsFailedList`), instead of blanking to the skeleton (stage 3a S3-4).
        if case .error = state, !lastItems.isEmpty {
            state = .content(items: lastItems, hasMore: hasMore, paginationError: false, isSearchActive: isSearchActive)
        }
        let keepContent: Bool
        if case .content = state { keepContent = true } else { keepContent = false }
        isSearching = keepContent
        await performFullLoad(showLoading: !keepContent)
        if !Task.isCancelled { isSearching = false }
    }

    private func performFullLoad(showLoading: Bool) async {
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
        loadQuery = queryParam
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
        // A superseded full load (`load()` never refuses -- a filter change must win) returns here
        // late; only the newest one may reopen pagination.
        if generation == loadGeneration { isFullLoading = false }
    }

    private func fetchFirstPage(showLoading: Bool) async {
        defer { if !Task.isCancelled { keepsFailedList = false } }
        if showLoading { state = .loading }
        let requestFilter = filter.state
        let requestQuery = queryParam
        do {
            let page = try await catalog.content(type: type, cursor: nil, limit: pageSize, filter: requestFilter, query: requestQuery)
            guard !Task.isCancelled else { return }
            // VoiceOver hears a new query's results replace the list (the view scrolls to the top
            // on the same change): how many there are.
            if requestQuery != itemsQuery {
                announce(Format.localizedFormat("list_footer_status", locale: .current, Int64(page.items.count)))
            }
            reloadPending = false
            items = page.items
            itemsFilter = requestFilter
            itemsQuery = requestQuery
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            state = .content(items: items, hasMore: hasMore, paginationError: false, isSearchActive: isSearchActive)
        } catch {
            guard !Task.isCancelled else { return }
            errorToken += 1
            reloadPending = true
            state = .error // initial/refresh failure loses items, matching Android §2.5
        }
    }

    private func fetchMore(generation: Int) async {
        // Covers the cancellation exits below too (gate B1-I3), but only while this is still the
        // newest load-more: a superseded one returning late must not unlock a live guard (W2).
        defer { if generation == loadGeneration { isLoadingMore = false } }
        do {
            // `itemsQuery`, not `queryParam`: inside the debounce the field already holds newer text,
            // but `nextCursor` and the list being paged belong to the query that fetched them.
            let page = try await catalog.content(type: type, cursor: nextCursor, limit: pageSize, filter: filter.state, query: itemsQuery)
            guard !Task.isCancelled, generation == loadGeneration else { return }
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
            guard !Task.isCancelled, generation == loadGeneration else { return }
            errorToken += 1
            state = .content(items: items, hasMore: hasMore, paginationError: true, isSearchActive: isSearchActive)
        }
    }

    /// Trimmed, like `SearchViewModel`/`SearchHistoryStore` (gate wave-2 W8): a field holding
    /// only spaces is not an active search, and must not show the "No results" search empty state.
    /// Not `private` (gate wave-4 V6): `ContentListView`'s pull-to-refresh suppression asks the
    /// same question and used to answer it with its own untrimmed `query.isEmpty`, so a
    /// whitespace-only field disabled pull-to-refresh on a list that was not filtered at all.
    var isSearchActive: Bool { !trimmedQuery.isEmpty }

    /// RULING 22: the server requires ≥2 chars; below that `q` is omitted entirely (falls back to
    /// the unfiltered list), not sent as a too-short string. Interpretation accepted as-is for
    /// these browse tabs: <2 chars means "show the unfiltered list", not "no results" / no fetch.
    /// Whitespace does not count toward the threshold and is never sent (gate wave-2 W8): `"  "`
    /// used to pass `count >= 2` and go out as `q="  "`. A query sent by the Search key skips the floor.
    private var queryParam: String? { trimmedQuery.count >= (submitted ? 1 : 2) ? trimmedQuery : nil }

    /// Set by the Search key, cleared by the next keystroke.
    private var submitted = false

    private var trimmedQuery: String { query.trimmingCharacters(in: .whitespacesAndNewlines) }
}
