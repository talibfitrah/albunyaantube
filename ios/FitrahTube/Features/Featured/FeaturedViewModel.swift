import Foundation

/// Android's `FeaturedListViewModel` (`content-lists.md:603-701`, §7.2-7.4). Probes `GET /api/v1/home`
/// scoped to `categoryId`; `hasSubcategories = probe.items.any { $0.id != categoryId }` decides
/// sections vs. flat mode. Flat mode falls through on `hasSubcategories == false` *or* any
/// non-cancellation probe failure -- a probe failure is invisible, matching Android exactly. Only a
/// flat-mode failure ever surfaces `.error`; only a load-more failure ever sets `lastLoadFailed`.
///
/// Same `loadTask`/`loadMoreTask` ownership as `HomeViewModel`/`ContentListViewModel`: a full load
/// always supersedes an in-flight load-more, load-more's own in-flight guard (`isLoadingMore`, set
/// before its first `await`) is suffient on its own.
@MainActor @Observable final class FeaturedViewModel {
    enum Mode: Equatable { case sections([HomeSection]), flat([ContentItem]) }
    enum State: Equatable { case loading, content(Mode, hasMore: Bool), error(String), empty }

    /// A production Firestore document id compiled into the app (`content-lists.md:612`, RULINGS
    /// #19) -- the pseudo-category Home's generic "See all featured" entry resolves to when no
    /// specific `categoryId` was passed.
    static let featuredCategoryId = "itirf9pGpAvoBT5VSkEc"

    /// Empty/nil `categoryId` falls back to `featuredCategoryId`. `static` so `FeaturedView`'s
    /// own `navTitle` can resolve the same way before `.task` creates the ViewModel, without a
    /// second copy of the ternary itself (task-12 fold-in).
    static func resolvedCategoryId(_ raw: String?) -> String {
        (raw?.isEmpty == false) ? raw! : featuredCategoryId
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
    private var isRefreshing = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?

    private static let sectionPageSize = 10 // categoryLimit, content-lists.md:617
    private static let contentPerSection = 20 // contentLimit, content-lists.md:617
    private static let flatPageSize = 50 // FLAT_PAGE_SIZE, content-lists.md:627

    init(categoryId: String?, categoryName: String?, catalog: any CatalogClient) {
        self.categoryId = Self.resolvedCategoryId(categoryId)
        self.categoryName = categoryName
        self.catalog = catalog
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// RULINGS #20: never shows `.loading` -- the caller's `.refreshable` holds its own spinner
    /// while the existing content stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isRefreshing else { return }
        isRefreshing = true
        await performFullLoad(showLoading: false)
        isRefreshing = false
    }

    func loadMore() async {
        guard !isLoadingMore, !lastLoadFailed else { return }
        guard case .content(_, let hasMore) = state, hasMore else { return }
        isLoadingMore = true // set before the first `await` -- the in-flight guard
        let task = Task { await self.fetchMore() }
        loadMoreTask = task
        await task.value
    }

    private func performFullLoad(showLoading: Bool) async {
        loadTask?.cancel()
        loadMoreTask?.cancel()
        lastLoadFailed = false
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
    }

    private func fetchFirstPage(showLoading: Bool) async {
        if showLoading { state = .loading }
        sections = []; sectionsNextCursor = nil
        flatItems = []; flatNextCursor = nil

        do {
            let probe = try await catalog.home(cursor: nil, categoryLimit: Self.sectionPageSize,
                                                contentLimit: Self.contentPerSection, category: categoryId)
            guard !Task.isCancelled else { return }
            if probe.items.contains(where: { $0.id != categoryId }) {
                isSectionsMode = true
                sections = probe.items
                sectionsNextCursor = probe.nextCursor
                isLoadingMore = false
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
            isLoadingMore = false
            state = flatItems.isEmpty ? .empty : .content(.flat(flatItems), hasMore: flatNextCursor != nil)
        } catch {
            guard !Task.isCancelled else { return }
            state = .error(errorMessage(error))
        }
    }

    private func fetchMore() async {
        if isSectionsMode {
            await fetchMoreSections()
        } else {
            await fetchMoreFlat()
        }
    }

    private func fetchMoreSections() async {
        do {
            let page = try await catalog.home(cursor: sectionsNextCursor, categoryLimit: Self.sectionPageSize,
                                               contentLimit: Self.contentPerSection, category: categoryId)
            guard !Task.isCancelled else { return }
            sections.append(contentsOf: page.items)
            sectionsNextCursor = page.nextCursor
            isLoadingMore = false
            state = .content(.sections(sections), hasMore: sectionsNextCursor != nil)
        } catch {
            guard !Task.isCancelled else { return }
            // Load-more failure keeps the cursor and re-emits the unchanged list, completely
            // silently (no error state, no banner) -- content-lists.md §7.4.
            isLoadingMore = false
            lastLoadFailed = true
            state = .content(.sections(sections), hasMore: sectionsNextCursor != nil)
        }
    }

    private func fetchMoreFlat() async {
        do {
            let page = try await fetchFlatPage(cursor: flatNextCursor)
            guard !Task.isCancelled else { return }
            flatItems.append(contentsOf: page.items)
            flatNextCursor = page.nextCursor
            isLoadingMore = false
            state = .content(.flat(flatItems), hasMore: flatNextCursor != nil)
        } catch {
            guard !Task.isCancelled else { return }
            isLoadingMore = false
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

    private func errorMessage(_ error: Error) -> String {
        (error as? LocalizedError)?.errorDescription ?? "\(error)"
    }
}
