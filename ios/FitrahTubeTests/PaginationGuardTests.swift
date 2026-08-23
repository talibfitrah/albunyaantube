import Testing
@testable import FitrahTube

/// `content-lists.md:219-237` (Android's `AutofillPaginationHelper`), guards 1-6, ported as a pure
/// value type per the task-10 brief's Interfaces block.
@Suite(.perTest)
struct PaginationGuardTests {
    @Test func compactWidthNeverAutoLoads() {
        var guardState = PaginationGuard()
        let result = guardState.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(result == false)
        #expect(guardState.attempts == 0)
    }

    @Test func regularAndLargeWidthCanAutoLoad() {
        var regular = PaginationGuard()
        #expect(regular.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20) == true)

        var large = PaginationGuard()
        #expect(large.shouldAutoLoad(widthClass: .large, hasMore: true, paginationError: false, contentFits: true, itemCount: 20) == true)
    }

    @Test func noMorePagesBlocksAndResets() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(guardState.attempts == 1)

        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: false, paginationError: false, contentFits: true, itemCount: 20)
        #expect(result == false)
        #expect(guardState.attempts == 0) // reset -- content-lists.md:224 "reset() and return"
        #expect(guardState.lastCount == 0)
    }

    @Test func paginationErrorBlocksWithoutResetting() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(guardState.attempts == 1)

        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: true, contentFits: true, itemCount: 20)
        #expect(result == false)
        #expect(guardState.attempts == 1) // no retry storm, but no reset either -- content-lists.md:225
    }

    @Test func attemptsCapAtFive() {
        var guardState = PaginationGuard()
        var itemCount = 10
        for _ in 0..<5 {
            let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: itemCount)
            #expect(result == true)
            itemCount += 10
        }
        #expect(guardState.attempts == 5)

        let sixth = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: itemCount)
        #expect(sixth == false)
        #expect(guardState.attempts == 5) // capped, not incremented further
    }

    @Test func progressInvariantBlocksWhenItemCountDoesNotGrow() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(guardState.lastCount == 20)

        // Same item count as last attempt -- the page didn't actually grow the list.
        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(result == false)
        #expect(guardState.attempts == 1) // unchanged, not incremented
    }

    @Test func progressInvariantAllowsGrowth() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)

        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 40)
        #expect(result == true)
        #expect(guardState.attempts == 2)
        #expect(guardState.lastCount == 40)
    }

    @Test func contentNoLongerFittingBlocksAndResets() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        #expect(guardState.attempts == 1)

        // itemCount grew (40 > 20) so the progress invariant (guard 5) alone wouldn't block this --
        // isolates guard 6 (content no longer fits, normal scrolling takes over).
        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: false, itemCount: 40)
        #expect(result == false)
        #expect(guardState.attempts == 0) // scroll-based pagination takes over now -- reset
        #expect(guardState.lastCount == 0)
    }

    @Test func firstAttemptIgnoresProgressInvariant() {
        // attempts == 0 on the very first call -- lastCount defaults to 0, itemCount 0 must still pass.
        var guardState = PaginationGuard()
        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 0)
        #expect(result == true)
        #expect(guardState.attempts == 1)
    }
}
