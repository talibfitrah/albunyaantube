import Foundation

/// Android's `HomeViewModel` (`shell-home.md` §B9-B11). One `Task`-typed property per "load kind" --
/// `loadTask` for a full reload (`load`/`refresh`, Android's `loadJob`) and
/// `loadMoreTask` for pagination (Android's `loadMoreJob`) -- so a fresh full reload always
/// supersedes an in-flight load-more (B10 step 1), never the reverse. Load-more's own in-flight
/// guard (`isLoadingMore`, set before its first `await`) is not sufficient on its own: see
/// `loadGeneration`/`isFullLoading` below for the two races it misses.
@MainActor @Observable final class HomeViewModel {
    enum State: Equatable {
        case loading
        case content(sections: [HomeSection], hasMore: Bool, isLoadingMore: Bool)
        case error
        case empty
    }

    private(set) var state: State = .loading

    /// Bumped on every terminal (load/refresh) failure, exactly like `ContentListViewModel`'s.
    /// `HomeView` drives its `TransientBanner` off this event rather than off a Bool level, so a
    /// second consecutive refresh failure -- which leaves `state` byte-identical -- still fires
    /// (gate wave-2 W4).
    private(set) var errorToken = 0

    private let catalog: any CatalogClient
    private let filter: any FilterStore
    private let widthClass: () -> WidthClass

    private var sections: [HomeSection] = []
    private var nextCursor: String?
    private var hasMore = true // optimistic default, mirrors Android (shell-home.md:B9)
    private var isLoadingMore = false
    private var category: String?

    /// Gate wave-2 W2/W3 -- the same token `ContentListViewModel`/`FeaturedViewModel` use; see the
    /// full rationale there. Home was the P1 case: its `loadMore()` guard was only
    /// `hasMore, !isLoadingMore`, so a load-more started while `fetchFirstPage` was awaiting (pull
    /// to refresh, then scroll -- or the iPad autofill that `HomeView` re-arms right before
    /// `refresh()`) fetched with the pre-refresh cursor, and when both landed `sections` became
    /// new-page-1 + old-page-2 with `nextCursor` pointing into the dead cursor sequence.
    private var loadGeneration = 0
    private var isFullLoading = false

    private var loadTask: Task<Void, Never>?
    private var loadMoreTask: Task<Void, Never>?

    private static let categoryLimit = 5

    init(catalog: any CatalogClient, filter: any FilterStore, widthClass: @escaping () -> WidthClass) {
        self.catalog = catalog
        self.filter = filter
        self.widthClass = widthClass
        // RULING 10 (double initial fetch): FilterStore is synchronously readable (UserDefaults,
        // not Android's async DataStore Flow), so the persisted category is captured once here --
        // the first load() issues exactly one request instead of Android's unfiltered-then-filtered pair.
        category = filter.state.categoryId
    }

    func load() async { await performFullLoad(showLoading: true) }

    /// RULINGS #12: never shows `.loading` -- the caller (`.refreshable`) holds its own spinner
    /// while the existing content stays on screen, swapped only once the new page arrives.
    func refresh() async {
        guard !isFullLoading else { return }
        await performFullLoad(showLoading: false)
    }

    /// Returns whether the fetch actually ran -- `HomeView` commits its `PaginationGuard` attempt
    /// only then (gate wave-2 W2).
    @discardableResult
    func loadMore() async -> Bool {
        guard hasMore, !isLoadingMore, !isFullLoading else { return false }
        isLoadingMore = true // set before the first `await` -- the in-flight guard (shell-home.md:B10)
        if case .content(let currentSections, let currentHasMore, _) = state {
            state = .content(sections: currentSections, hasMore: currentHasMore, isLoadingMore: true)
        }
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
        // A cancelled load-more returns through its own `guard !Task.isCancelled` before it can
        // clear either the flag or the published spinner (gate B1-I3). Left alone, `loadMore()`
        // returned immediately forever after, and Home's footer `ProgressView` -- which this
        // ViewModel publishes, unlike the other two -- stayed on screen indefinitely.
        isLoadingMore = false
        if case .content(let sections, let hasMore, true) = state {
            state = .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        }
        let task = Task { await self.fetchFirstPage(showLoading: showLoading) }
        loadTask = task
        await task.value
        // Only the newest full load may reopen pagination -- `load()` never refuses (a filter
        // change must win), so a superseded one can return here late.
        if generation == loadGeneration { isFullLoading = false }
    }

    private func fetchFirstPage(showLoading: Bool) async {
        if showLoading { state = .loading }
        do {
            let page = try await catalog.home(cursor: nil, categoryLimit: Self.categoryLimit,
                                               contentLimit: contentLimit(), category: category)
            guard !Task.isCancelled else { return }
            sections = page.items
            nextCursor = page.nextCursor
            hasMore = page.hasMore // CursorPage.hasMore == (nextCursor != nil)
            state = sections.isEmpty ? .empty : .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        } catch {
            guard !Task.isCancelled else { return }
            errorToken += 1
            // Gate wave-2 W4: same policy as the lists (`content-lists.md:277-280`) -- a reload
            // failure with content already on screen keeps that content and says so in a transient
            // banner. Blanking a loaded Home for a full-page error on one dropped pull-to-refresh
            // is the bug `ContentListViewModel` already fixed; Home never got it. `sections` is
            // only ever reassigned on success, so it still holds the last good page here, and a
            // category change builds a whole new `HomeViewModel` (`HomeView`), so retained
            // sections can never be shown under a filter they weren't fetched for.
            state = sections.isEmpty ? .error : .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        }
    }

    private func fetchMore(generation: Int) async {
        // Covers the cancellation exits below too (gate B1-I3), but only while this is still the
        // newest load-more: a superseded one returning late must not unlock a live guard (W2).
        defer { if generation == loadGeneration { isLoadingMore = false } }
        do {
            let page = try await catalog.home(cursor: nextCursor, categoryLimit: Self.categoryLimit,
                                               contentLimit: contentLimit(), category: category)
            guard !Task.isCancelled, generation == loadGeneration else { return }
            let existingIDs = Set(sections.map(\.id))
            sections.append(contentsOf: page.items.filter { !existingIDs.contains($0.id) }) // dedupe by section id
            nextCursor = page.nextCursor
            hasMore = page.hasMore
            state = .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        } catch {
            // RULINGS #13: pagination failures on Home are silent -- no error state, no
            // `errorToken` bump (no banner either), the footer spinner just disappears and the
            // user can scroll again to retry.
            guard !Task.isCancelled, generation == loadGeneration else { return }
            state = .content(sections: sections, hasMore: hasMore, isLoadingMore: false)
        }
    }

    private func contentLimit() -> Int {
        widthClass() == .compact ? 10 : 20
    }
}
