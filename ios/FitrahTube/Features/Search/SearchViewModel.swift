import Foundation

/// Android's `SearchFragment`/`SearchViewModel` (`search-categories.md:40-149,259-281`). One
/// `searchTask` handle covers both the debounce timer and the eventual fetch (or, for `submit()`/
/// `retry()`, the fetch alone) -- reassigning it always cancels whatever it previously held,
/// matching Android's `searchJob?.cancel()` "on every text change" (`:47`).
///
/// RULINGS #22/#23: 500 ms debounce (not the tabs' 300 ms), min 2 chars; below that the screen
/// goes straight to the zero-state (history or an `EmptyStateView` prompt) instead of Android's
/// "leaves the previous state on screen" edge case (`:56-59`) -- a deliberate fix, not drift.
/// `submit()` is the *only* history writer (`:60-61`); the YouTube URL/ID fast path
/// (`search-categories.md` §1.7) is entirely server-side -- the client only ever forwards `query`
/// verbatim to `catalog.search(query:type:limit:)`, never inspects or rewrites it.
@MainActor @Observable final class SearchViewModel {
    enum State: Equatable { case zero(history: [String]), loading, results([ContentItem]), noResults, error }

    private(set) var state: State

    /// 500 ms debounce, min 2 chars (`didSet` below); `submit()`/`selectHistory(_:)` bypass both.
    var query: String = "" {
        didSet {
            guard query != oldValue else { return }
            searchTask?.cancel()
            guard query.count >= 2 else {
                state = .zero(history: history.entries) // RULINGS #23: immediate, no stale state
                return
            }
            let task = Task { [sleep] in
                do { try await sleep(.milliseconds(500)) } catch { return }
                guard !Task.isCancelled else { return }
                await self.performSearch(self.query)
            }
            searchTask = task
        }
    }

    private let catalog: any CatalogClient
    private let history: any SearchHistoryStore
    private let sleep: @Sendable (Duration) async throws -> Void

    private static let pageSize = 50 // search-categories.md §1.5 -- one request, no paging

    /// Visible (not `private`) only so tests can `await vm.searchTask?.value` -- same rationale as
    /// `ContentListViewModel.searchTask`. Covers both the pending debounce timer and, once it
    /// fires (or `submit()`/`retry()` runs immediately), the fetch itself.
    private(set) var searchTask: Task<Void, Never>?

    init(catalog: any CatalogClient, history: any SearchHistoryStore,
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.catalog = catalog
        self.history = history
        self.sleep = sleep
        state = .zero(history: history.entries)
    }

    /// Runs immediately -- no debounce, no min-length -- and is the only path that writes history
    /// (`search-categories.md:60-61`). `isNotBlank()` is Android's only guard; a whitespace-only
    /// query does nothing.
    func submit() async {
        searchTask?.cancel()
        guard !query.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else { return }
        history.add(query)
        let task = Task { await self.performSearch(self.query) }
        searchTask = task
        await task.value
    }

    /// Tapping a history row: `setQuery(query, true)` on Android both runs the search and re-saves
    /// the term, bumping it to position 0 (`search-categories.md:80-81`).
    func selectHistory(_ q: String) async {
        query = q
        await submit()
    }

    /// Removing one entry clears any showing results/error and returns to the history/zero state,
    /// without touching the field text (`search-categories.md:74-75`).
    func removeHistory(_ q: String) {
        searchTask?.cancel()
        history.remove(q)
        state = .zero(history: history.entries)
    }

    func clearHistory() {
        searchTask?.cancel()
        history.clear()
        state = .zero(history: [])
    }

    /// RULINGS #23: unlike Android (no retry button), the error state gets one -- re-runs the
    /// current query. Never writes history (only `submit()` does).
    func retry() async {
        searchTask?.cancel()
        let task = Task { await self.performSearch(self.query) }
        searchTask = task
        await task.value
    }

    private func performSearch(_ q: String) async {
        state = .loading
        do {
            let results = try await catalog.search(query: q, type: nil, limit: Self.pageSize)
            guard !Task.isCancelled else { return }
            state = results.isEmpty ? .noResults : .results(results)
        } catch {
            guard !Task.isCancelled else { return }
            state = .error
        }
    }
}
