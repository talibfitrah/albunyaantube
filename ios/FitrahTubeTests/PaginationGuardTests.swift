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

    // MARK: - Gate wave-4 V1/V6: reset generation (the view's commit check)

    /// The view copies the guard, spends an attempt on it, and writes the copy back only after the
    /// fetch returns. Anything that resets the guard inside that window -- a filter change that
    /// swaps `HomeView`'s whole ViewModel, or a query change 300 ms ahead of the ViewModel's own
    /// generation bump -- must make the copy detectably stale, or it lands on top of the reset.
    @Test func externalResetInvalidatesAnAttemptCopiedBeforeIt() {
        var guardState = PaginationGuard()
        var attempt = guardState
        #expect(attempt.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20) == true)

        guardState.reset() // query/filter change or pull-to-refresh, while the fetch is in flight

        #expect(attempt.generation != guardState.generation) // the view refuses the commit
        #expect(guardState.attempts == 0)
        #expect(guardState.lastCount == 0)
    }

    /// ...but guards 2 and 6 renew the budget *without* bumping the generation, so the in-flight
    /// attempt still commits. If they bumped it, an endpoint returning empty-but-`hasMore` pages
    /// while the content kept fitting would never record an attempt and guard 4's cap could never
    /// bite.
    @Test func internalRenewalKeepsTheGenerationSoInFlightAttemptsStillCommit() {
        var guardState = PaginationGuard()
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 20)
        let attempt = guardState

        // guard 6: content no longer fits -> renew.
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: false, itemCount: 40)
        #expect(guardState.attempts == 0)
        #expect(attempt.generation == guardState.generation)

        // guard 2: list exhausted -> renew.
        _ = guardState.shouldAutoLoad(widthClass: .regular, hasMore: false, paginationError: false, contentFits: true, itemCount: 40)
        #expect(attempt.generation == guardState.generation)
    }

    @Test func resetsAccumulateSoOnlyTheNewestCopyCommits() {
        var guardState = PaginationGuard()
        guardState.reset()
        let first = guardState
        guardState.reset()

        #expect(first.generation != guardState.generation)
        #expect(guardState.generation == 2)
    }

    @Test func firstAttemptIgnoresProgressInvariant() {
        // attempts == 0 on the very first call -- lastCount defaults to 0, itemCount 0 must still pass.
        var guardState = PaginationGuard()
        let result = guardState.shouldAutoLoad(widthClass: .regular, hasMore: true, paginationError: false, contentFits: true, itemCount: 0)
        #expect(result == true)
        #expect(guardState.attempts == 1)
    }

    // MARK: - R7-P1 #2: the Me feed's opt-in to guard 1

    /// Guard 1 ("phones never autofill") is right for every NETWORK-paged list behind it: a phone
    /// always scrolls, and the scroll listener is the cheaper trigger. It is wrong for the Me feed,
    /// whose `loadMoreWeeks()` is `loadedWeekCount += 1` over weeks ALREADY IN MEMORY (ruling F4,
    /// no deep paging): a user with one channel uploading once a week sees week 1, fires the
    /// trigger once, gets week 2 — which still fits the viewport, so nothing ever scrolls out —
    /// and the remaining weeks are unreachable forever. That is exactly the case CLAUDE.md's
    /// pagination rule names, so the Me feed opts IN and the other four callers keep guard 1
    /// untouched (their default is `false`).
    @Test func aCompactCallerThatOptsInAutoFillsWhileTheContentStillFits() {
        var optedIn = PaginationGuard()
        let first = optedIn.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                           contentFits: true, itemCount: 5, compactAutoFills: true)
        // Twice in a row: one week appended, the content still fits, and the next one is owed.
        let second = optedIn.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                            contentFits: true, itemCount: 9, compactAutoFills: true)
        #expect(first)
        #expect(second, "a phone whose content still fits stalled after one page")

        var other = PaginationGuard()
        let untouched = other.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                             contentFits: true, itemCount: 5)
        #expect(untouched == false, "guard 1 moved for the four callers that never asked for it")
    }

    /// And the opt-in buys only guard 1: everything that stops a retry storm on a regular-width
    /// screen still stops one here. Guard 6 is what hands the page back to scroll pagination the
    /// moment the content overflows.
    @Test func theCompactOptInStillHonoursTheOtherFiveGuards() {
        var guardState = PaginationGuard()
        let exhausted = guardState.shouldAutoLoad(widthClass: .compact, hasMore: false, paginationError: false,
                                                  contentFits: true, itemCount: 5, compactAutoFills: true)
        let errored = guardState.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: true,
                                                contentFits: true, itemCount: 5, compactAutoFills: true)
        let overflowing = guardState.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                                    contentFits: false, itemCount: 5, compactAutoFills: true)
        #expect(exhausted == false, "guard 2")
        #expect(errored == false, "guard 3")
        #expect(overflowing == false, "guard 6: scroll pagination takes over once it overflows")

        var capped = PaginationGuard()
        var spent = 0
        for attempt in 0..<capped.maxAttempts
        where capped.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                    contentFits: true, itemCount: attempt + 1, compactAutoFills: true) {
            spent += 1
        }
        let sixth = capped.shouldAutoLoad(widthClass: .compact, hasMore: true, paginationError: false,
                                          contentFits: true, itemCount: 99, compactAutoFills: true)
        #expect(spent == capped.maxAttempts)
        #expect(sixth == false, "guard 4's attempt cap")
    }
}
