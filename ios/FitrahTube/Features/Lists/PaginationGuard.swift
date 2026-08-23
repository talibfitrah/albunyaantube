import Foundation

/// Android's `AutofillPaginationHelper` (`content-lists.md:219-237`) -- the safety valve for the
/// large-screen case where content already fills the viewport and a normal scroll-position
/// listener never fires. `ContentListView` owns one of these as `@State` per tab (a value type,
/// not part of `ContentListViewModel`) and resets it (`reset()`) on search/filter
/// change and pull-to-refresh, mirroring Android's explicit external `reset()` call sites.
nonisolated struct PaginationGuard: Sendable {
    var attempts = 0
    let maxAttempts = 5
    var lastCount = 0

    /// Gate wave-4 V1/V6. A view commits its spent attempt *after* an `await`
    /// (`if await loadMore() { paginationGuard = attempt }`), so a reset landing inside that window
    /// used to be overwritten by the pre-reset copy. The ViewModels' own `loadGeneration`
    /// (wave-3 D1) covers same-ViewModel supersession only, and misses both of these:
    ///  - `HomeView` *replaces* its ViewModel on a filter change, and the orphaned old one's
    ///    generation never moves, so its late `loadMore()` still answers `true`;
    ///  - `ContentListView` resets the guard the instant the query changes, while the ViewModel's
    ///    generation only bumps 300 ms later, when the debounce fires.
    /// Bumped by `reset()` alone, so a copy taken before a reset is refused at commit time.
    private(set) var generation = 0

    /// External reset -- search/filter change, pull-to-refresh, ViewModel replacement (Android's
    /// explicit `reset()` call sites). Invalidates any attempt copy taken before it.
    mutating func reset() {
        let next = generation + 1
        self = PaginationGuard()
        generation = next
    }

    /// Guards 2/6's internal budget renewal, deliberately *not* an external `reset()`: it must not
    /// invalidate an in-flight attempt, or the attempts spent against an endpoint that keeps
    /// returning empty-but-`hasMore` pages would never be recorded and guard 4's cap could never
    /// bite.
    private mutating func renew() {
        let keep = generation
        self = PaginationGuard()
        generation = keep
    }

    /// Guards 1-6, in Android's order. `contentFits` stands in for guard 6's "after layout, view
    /// still active, !canScrollVertically(1)" check -- the caller computes it via `onContentFits`.
    mutating func shouldAutoLoad(widthClass: WidthClass, hasMore: Bool, paginationError: Bool, contentFits: Bool, itemCount: Int) -> Bool {
        guard widthClass != .compact else { return false } // guard 1: phones never autofill (:52)
        guard hasMore else { renew(); return false } // guard 2: list exhausted (:54-57)
        guard !paginationError else { return false } // guard 3: no retry storm (:60-63)
        guard attempts < maxAttempts else { return false } // guard 4: attempt cap (:66-69)
        guard attempts == 0 || itemCount > lastCount else { return false } // guard 5: progress invariant (:72-75)
        guard contentFits else { renew(); return false } // guard 6: scroll pagination takes over (:78-90)
        attempts += 1
        lastCount = itemCount
        return true
    }
}
