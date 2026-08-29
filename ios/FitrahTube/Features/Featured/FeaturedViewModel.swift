import Foundation

/// Android's `FeaturedListViewModel` (`content-lists.md:603-701`, §7.2-7.4). Probes `GET /api/v1/home`
/// scoped to `categoryId`; `hasSubcategories = probe.items.any { $0.id != categoryId }` decides
/// sections vs. flat mode. Flat mode falls through on `hasSubcategories == false` *or* any
/// non-cancellation probe failure -- a probe failure is invisible, matching Android exactly. Only a
/// flat-mode failure ever surfaces `.error`; only a load-more failure ever sets `lastLoadFailed`.
///
/// Same `loadTask`/`loadMoreTask` ownership as `HomeViewModel`/`ContentListViewModel`: a full load
/// always supersedes an in-flight load-more, never the reverse, and the same
/// `loadGeneration`/`isFullLoading` token guards the two races `isLoadingMore` alone misses.
@MainActor @Observable final class FeaturedViewModel {
    enum Mode: Equatable { case sections([HomeSection]), flat([ContentItem]) }
    enum State: Equatable { case loading, content(Mode, hasMore: Bool), error(String), empty }

    /// A production Firestore document id compiled into the app (`content-lists.md:612`, RULINGS
    /// #19) -- the pseudo-category Home's generic "See all featured" entry resolves to when no
    /// specific `categoryId` was passed. RULING 63: the fallback only; `FeaturedView` prefers
    /// `RemoteConfig.featuredCategoryId` when the published config carries one.
    static let bundledFeaturedCategoryId = "itirf9pGpAvoBT5VSkEc"

    /// Empty/nil `categoryId` falls back to `fallback` (the remote-config id, else the bundled one).
    /// `static` so `FeaturedView`'s own `navTitle` can resolve the same way before `.task` creates
    /// the ViewModel, without a second copy of the ternary itself (task-12 fold-in).
    static func resolvedCategoryId(_ raw: String?, fallback: String = bundledFeaturedCategoryId) -> String {
        if let raw, !raw.isEmpty { return raw }
        return fallback
    }

    private(set) var state: State = .loading

    /// Set (and only ever cleared by a full reload) when a load-more fetch fails --
    /// content-lists.md §7.4's "retry latch": blocks further auto-retry attempts on a failing
    /// endpoint so a large-screen content-fits autofill can't spin forever. Not part of `State`
    /// itself (the brief's literal `.content(Mode, hasMore: Bool)` has no such field, matching
    /// `ContentListViewModel`'s `searchTask`/`HomeViewModel`'s `loadTask` precedent of exposing
    /// extra properties beyond the given `State` shape) -- the view feeds this into
    /// `PaginationGuard.shouldAutoLoad(paginationError:)`.
    ///
    /// Android clears the latch on a manual downward scroll gesture (`clearLoadError()`); this
    /// port clears it on `refresh()` instead -- RULINGS #20 already grants Featured pull-to-refresh
    /// for free, which subsumes that fussy directional-scroll-delta mechanic as the recovery action.
    private(set) var lastLoadFailed = false

    /// Resolved once at init (empty/nil `categoryId` falls back to `featuredCategoryId`) and
    /// exposed so `FeaturedView.navTitle` doesn't duplicate that same fallback (task-12 fold-in).
    let categoryId: String
    private let categoryName: String?
    private let catalog: any CatalogClient

    private var sections: [HomeSection] = []
    private var sectionsNextCursor: String?
    private var flatItems: [ContentItem] = []
    private var flatNextCursor: String?
    private var isSectionsMode = false
    private var isLoadingMore = false

    /// Gate wave-2 W2/W3 -- the same token `ContentListViewModel`/`HomeViewModel` use; see the
    /// full rationale on `ContentListViewModel.loadGeneration`. Replaces the narrower
    /// `isRefreshing` (which covered `refresh()` only, not `load()`) and makes the stale `defer`
    /// in `fetchMore` generation-safe.
    private var loadGeneration = 0
    private var isFullLoading = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?

    private static let sectionPageSize = 10 // categoryLimit, content-lists.md:617
    private static let contentPerSection = 20 // contentLimit, content-lists.md:617
    private static let flatPageSize = 50 // FLAT_PAGE_SIZE, content-lists.md:627

    init(categoryId: String?, categoryName: String?, catalog: any CatalogClient,
         fallbackCategoryId: String = bundledFeaturedCategoryId) {
        self.categoryId = Self.resolvedCategoryId(categoryId, fallback: fallbackCategoryId)
        self.categoryName = categoryName
        self.catalog = catalog
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// First appearance fetches; a tab revisit does not -- see
    /// `ContentListViewModel.loadIfNeeded()` for why `.task` re-runs at all (gate wave-4 V2).
    /// Pull-to-refresh and the error state's own Retry keep their own paths.
    func loadIfNeeded() async {
        guard case .loading = state else { return }
        await load()
    }

    /// RULINGS #20: never shows `.loading` -- the caller's `.refreshable` holds its own spinner
    /// while the existing content stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isFullLoading else { return }
        await performFullLoad(showLoading: false)
    }

    /// Returns whether the fetch actually ran -- `FeaturedView` commits its `PaginationGuard`
    /// attempt only then (gate wave-2 W2).
    @discardableResult
    func loadMore() async -> Bool {
        // `!isFullLoading` (gate B1-I4, widened by wave-2 W2): without it, a pull-to-refresh's own
        // layout churn could start a load-more against the arrays the refresh was in the middle of
        // replacing.
        guard !isLoadingMore, !isFullLoading, !lastLoadFailed else { return false }
        guard case .content(_, let hasMore) = state, hasMore else { return false }
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

    private func performFullLoad(showLoading: Bool) async {
        loadGeneration += 1
        let generation = loadGeneration
        isFullLoading = true
        loadTask?.cancel()
        loadMoreTask?.cancel()
        isLoadingMore = false // a cancelled load-more never reaches its own cleanup (gate B1-I3)
        lastLoadFailed = false
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
        // Only the newest full load may reopen pagination -- `load()` never refuses.
        if generation == loadGeneration { isFullLoading = false }
    }

    /// The backing arrays are cleared on the *success* paths, never before the `await` (gate
    /// B1-I4). Clearing up front emptied `flatItems` while `state` still published the old content
    /// (refresh uses `showLoading: false`), so a load-more racing the refresh appended one page to
    /// an empty array and collapsed the visible list from N pages to one.
    private func fetchFirstPage(showLoading: Bool) async {
        if showLoading { state = .loading }
        do {
            let probe = try await catalog.home(cursor: nil, categoryLimit: Self.sectionPageSize,
                                                contentLimit: Self.contentPerSection, category: categoryId)
            guard !Task.isCancelled else { return }
            if probe.items.contains(where: { $0.id != categoryId }) {
                isSectionsMode = true
                sections = probe.items
                sectionsNextCursor = probe.nextCursor
                flatItems = []; flatNextCursor = nil
                state = sections.isEmpty ? .empty : .content(.sections(sections), hasMore: sectionsNextCursor != nil)
                return
            }
        } catch {
            guard !Task.isCancelled else { return }
            // Probe failure falls through to flat mode silently -- content-lists.md:663-666.
        }
        isSectionsMode = false
        await fetchFlatFirstPage()
    }

    private func fetchFlatFirstPage() async {
        do {
            let page = try await fetchFlatPage(cursor: nil)
            guard !Task.isCancelled else { return }
            flatItems = page.items
            flatNextCursor = page.nextCursor
            sections = []; sectionsNextCursor = nil
            state = flatItems.isEmpty ? .empty : .content(.flat(flatItems), hasMore: flatNextCursor != nil)
        } catch {
            guard !Task.isCancelled else { return }
            // Localized copy, not `error.localizedDescription` (gate B1-I5): a `DecodingError` or
            // an OpenAPI runtime error rendered a developer-facing dump -- untranslated, possibly
            // carrying the request path -- as body copy in the middle of an Arabic or Dutch UI.
            // Every other screen shows this same key.
            state = .error(String(localized: "list_error_description"))
        }
    }

    private func fetchMore(generation: Int) async {
        // Covers the cancellation exits below too (gate B1-I3), but only while this is still the
        // newest load-more: a superseded one returning late must not unlock a live guard (W2).
        defer { if generation == loadGeneration { isLoadingMore = false } }
        if isSectionsMode {
            await fetchMoreSections(generation: generation)
        } else {
            await fetchMoreFlat(generation: generation)
        }
    }

    private func fetchMoreSections(generation: Int) async {
        do {
            let page = try await catalog.home(cursor: sectionsNextCursor, categoryLimit: Self.sectionPageSize,
                                               contentLimit: Self.contentPerSection, category: categoryId)
            guard !Task.isCancelled, generation == loadGeneration else { return }
            let existingIDs = Set(sections.map(\.id)) // dedupe by id (gate B1-minor-6)
            sections.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) })
            sectionsNextCursor = page.nextCursor
            state = .content(.sections(sections), hasMore: sectionsNextCursor != nil)
        } catch {
            guard !Task.isCancelled, generation == loadGeneration else { return }
            // Load-more failure keeps the cursor and re-emits the unchanged list, completely
            // silently (no error state, no banner) -- content-lists.md §7.4.
            lastLoadFailed = true
            state = .content(.sections(sections), hasMore: sectionsNextCursor != nil)
        }
    }

    private func fetchMoreFlat(generation: Int) async {
        do {
            let page = try await fetchFlatPage(cursor: flatNextCursor)
            guard !Task.isCancelled, generation == loadGeneration else { return }
            let existingIDs = Set(flatItems.map(\.id)) // dedupe by id (gate B1-minor-6)
            flatItems.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) })
            flatNextCursor = page.nextCursor
            state = .content(.flat(flatItems), hasMore: flatNextCursor != nil)
        } catch {
            guard !Task.isCancelled, generation == loadGeneration else { return }
            lastLoadFailed = true
            state = .content(.flat(flatItems), hasMore: flatNextCursor != nil)
        }
    }

    /// content-lists.md:627: the flat path builds a *fresh* `FilterState(category:)` -- it
    /// deliberately ignores the global filter's length/date/sort, and `type` is never sent (the
    /// backend has no `ALL` type, task-10's note / this task's brief).
    private func fetchFlatPage(cursor: String?) async throws -> CursorPage<ContentItem> {
        try await catalog.content(type: nil, cursor: cursor, limit: Self.flatPageSize,
                                   filter: FilterState(categoryId: categoryId, categoryName: categoryName), query: nil)
    }
}
