import Foundation

/// Android's `AutofillPaginationHelper` (`content-lists.md:219-237`) -- the safety valve for the
/// large-screen case where content already fills the viewport and a normal scroll-position
/// listener never fires. `ContentListView` owns one of these as `@State` per tab (a value type,
/// not part of `ContentListViewModel`) and resets it (`= PaginationGuard()`) on search/filter
/// change and pull-to-refresh, mirroring Android's explicit external `reset()` call sites.
nonisolated struct PaginationGuard: Sendable {
    var attempts = 0
    let maxAttempts = 5
    var lastCount = 0

    /// Guards 1-6, in Android's order. `contentFits` stands in for guard 6's "after layout, view
    /// still active, !canScrollVertically(1)" check -- the caller computes it via `onContentFits`.
    mutating func shouldAutoLoad(widthClass: WidthClass, hasMore: Bool, paginationError: Bool, contentFits: Bool, itemCount: Int) -> Bool {
        guard widthClass != .compact else { return false } // guard 1: phones never autofill (:52)
        guard hasMore else { self = PaginationGuard(); return false } // guard 2: list exhausted (:54-57)
        guard !paginationError else { return false } // guard 3: no retry storm (:60-63)
        guard attempts < maxAttempts else { return false } // guard 4: attempt cap (:66-69)
        guard attempts == 0 || itemCount > lastCount else { return false } // guard 5: progress invariant (:72-75)
        guard contentFits else { self = PaginationGuard(); return false } // guard 6: scroll pagination takes over (:78-90)
        attempts += 1
        lastCount = itemCount
        return true
    }
}
