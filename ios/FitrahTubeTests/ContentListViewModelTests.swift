import Foundation
import Testing
@testable import FitrahTube

/// `content-lists.md:24-400` -- shared `ContentListViewModel`, one instance per tab (`ListType`).
@Suite(.perTest)
struct ContentListViewModelTests {

    // MARK: - Test doubles (same pattern as HomeViewModelTests)

    /// Records every `content()` call's params -- proves per-type request shapes, cursor
    /// progression, and the `q` omission threshold.
    private actor RecordingCatalogClient: CatalogClient {
        /// `filter` added fix round 1, finding #2 -- the original `Call` recorded every request
        /// param except this one, so nothing ever proved `ContentListViewModel` actually forwards
        /// `filter.state` to `catalog.content(...)` unchanged.
        struct Call: Sendable { let type: ListType?; let cursor: String?; let limit: Int; let filter: FilterState; let query: String? }
        private let pages: [CursorPage<ContentItem>]
        private(set) var calls: [Call] = []

        init(pages: [CursorPage<ContentItem>]) { self.pages = pages }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            calls.append(Call(type: type, cursor: cursor, limit: limit, filter: filter, query: query))
            let index = cursor.flatMap(Int.init) ?? 0
            guard pages.indices.contains(index) else { return CursorPage(items: [], nextCursor: nil) }
            return pages[index]
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// The first `content()` call succeeds; every call after that throws -- for pagination-error
    /// and retry-after-failure tests.
    private actor FlakyAfterFirstCallClient: CatalogClient {
        struct Boom: Error {}
        private let firstPage: CursorPage<ContentItem>
        private let recoverAfter: Int
        private var callCount = 0

        init(firstPage: CursorPage<ContentItem>, recoverAfter: Int = .max) {
            self.firstPage = firstPage
            self.recoverAfter = recoverAfter
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            callCount += 1
            if callCount == 1 { return firstPage }
            if callCount >= recoverAfter { return CursorPage(items: [], nextCursor: nil) }
            throw Boom()
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// The first `content()` call resolves immediately; every later call suspends on `gate`.
    private actor GatedCatalogClient: CatalogClient {
        private let page: CursorPage<ContentItem>
        private let gate: Gate
        private var callCount = 0

        init(page: CursorPage<ContentItem>, gate: Gate) {
            self.page = page
            self.gate = gate
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            callCount += 1
            if callCount > 1 { await gate.block() }
            return page
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// The Nth `content()` call suspends on `gates[N]` (1-based); every other call resolves at
    /// once. Lets a test hold two load-mores in flight at the same time, which is what the gate
    /// wave-2 W2 defer fix is about.
    private actor MultiGateCatalogClient: CatalogClient {
        private let page: CursorPage<ContentItem>
        private let gates: [Int: Gate]
        private(set) var callCount = 0

        init(page: CursorPage<ContentItem>, gates: [Int: Gate]) {
            self.page = page
            self.gates = gates
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            callCount += 1
            if let gate = gates[callCount] { await gate.block() }
            return page
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    // MARK: - Tests

    @Test func perTypeRequestShapes() async {
        for type in [ListType.channels, .playlists, .videos] {
            let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
            let vm = ContentListViewModel(type: type, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

            await vm.load()

            let calls = await client.calls
            #expect(calls.count == 1)
            #expect(calls[0].type == type)
            #expect(calls[0].cursor == nil)
            #expect(calls[0].limit == 20) // default pageSize
            #expect(calls[0].query == nil)
        }
    }

    /// Gate wave-4 V2: `.task` restarts on every compact-width tab re-selection, so the view's
    /// unconditional `load()` refetched page 1 -- discarding the cursor and every page after the
    /// first -- each time the user came back to the tab. `loadIfNeeded()` fetches on the first
    /// appearance and no-ops on the ones after it.
    @Test func loadIfNeededFetchesOnceThenNoOpsOnLaterAppearances() async {
        let pages: [CursorPage<ContentItem>] = [
            CursorPage(items: items(count: 20, prefix: "p0"), nextCursor: "1"),
            CursorPage(items: items(count: 20, prefix: "p1"), nextCursor: nil),
        ]
        let client = RecordingCatalogClient(pages: pages)
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.loadIfNeeded()   // first appearance
        await vm.loadMore()       // the user paginated
        #expect(await client.calls.count == 2)

        await vm.loadIfNeeded()   // tab switched away and back
        await vm.loadIfNeeded()

        #expect(await client.calls.count == 2) // no refetch...
        guard case .content(let items, _, _, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(items.count == 40) // ...and both loaded pages survive
    }

    /// The first appearance is the *only* one that fetches, whatever the outcome -- but a load
    /// still in flight when the tab is revisited has not resolved into any state yet, so that one
    /// re-issues (and `performFullLoad` supersedes the first cleanly).
    @Test func loadIfNeededStillFetchesWhileTheFirstLoadIsUnresolved() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        #expect(vm.state == .loading(.initial))
        await vm.loadIfNeeded()
        #expect(await client.calls.count == 1)
    }

    @Test func cursorProgressesAndHasMoreReflectsNextCursor() async {
        let pages: [CursorPage<ContentItem>] = [
            CursorPage(items: items(count: 20, prefix: "p0"), nextCursor: "1"),
            CursorPage(items: items(count: 20, prefix: "p1"), nextCursor: nil),
        ]
        let client = RecordingCatalogClient(pages: pages)
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        guard case .content(let items1, let hasMore1, let error1, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(items1.count == 20)
        #expect(hasMore1 == true)
        #expect(error1 == false)

        await vm.loadMore()
        guard case .content(let items2, let hasMore2, _, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(items2.count == 40)
        #expect(hasMore2 == false)

        let calls = await client.calls
        #expect(calls.map(\.cursor) == [nil, "1"])

        await vm.loadMore() // guarded no-op: hasMore is false
        #expect(await client.calls.count == 2)
    }

    @Test func paginationFailureSetsFlagKeepsItemsAndRetrySucceeds() async {
        let firstPage = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: "1")
        let client = FlakyAfterFirstCallClient(firstPage: firstPage, recoverAfter: 3) // 2nd call (loadMore) throws, 3rd (retry) succeeds
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        await vm.loadMore() // throws

        guard case .content(let items1, let hasMore1, let error1, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(items1.count == 20) // unchanged -- pagination failure keeps accumulated items
        #expect(hasMore1 == true) // cursor kept, so a retry can still work
        #expect(error1 == true)

        await vm.retryPagination() // succeeds (empty page, nextCursor nil)

        guard case .content(let items2, let hasMore2, let error2, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(items2.count == 20)
        #expect(hasMore2 == false)
        #expect(error2 == false) // flag clears on the next successful fetch
    }

    @Test func refreshNeverShowsLoadingWhenContentExists() async {
        let gate = Gate()
        let client = GatedCatalogClient(page: CursorPage(items: items(count: 5, prefix: "a"), nextCursor: nil), gate: gate)
        let vm = ContentListViewModel(type: .channels, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        guard case .content = vm.state else { Issue.record("expected .content after initial load"); return }

        let refreshTask = Task { await vm.refresh() }
        await gate.waitUntilBlocked()
        if case .loading = vm.state {
            Issue.record("refresh() must not show .loading while content is already on screen")
        }

        await gate.release()
        await refreshTask.value

        guard case .content = vm.state else { Issue.record("expected .content after refresh completes"); return }
    }

    @Test func queryBelowTwoCharsOmitsQParam() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load() // call 1: q == nil (empty query)
        vm.query = "a" // 1 char -- below the ≥2 threshold
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 2)
        #expect(calls[1].query == nil)
        guard case .content(_, _, _, let isSearchActive) = vm.state else { Issue.record("expected .content"); return }
        #expect(isSearchActive == true) // non-empty query still marks search as active, even below the fetch threshold
    }

    @Test func queryAtTwoCharsIncludesQParamAfterDebounce() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        vm.query = "ab"
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 2)
        #expect(calls[1].query == "ab")
        #expect(calls[1].cursor == nil) // search resets pagination
    }

    @Test func rapidQueryChangesCoalesceToOneRequest() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load() // call 1
        vm.query = "a"
        vm.query = "ab"
        vm.query = "abc" // only this one should ever actually fetch -- the first two get cancelled
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 2) // load() + exactly one debounced search fetch
        #expect(calls[1].query == "abc")
    }

    @Test func debounceWaitsBeforeFetching() async {
        actor DurationRecorder { var seen: Duration?; func record(_ d: Duration) { seen = d } }
        let recorder = DurationRecorder()
        let client = RecordingCatalogClient(pages: [CursorPage(items: [], nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore()) { duration in
            await recorder.record(duration)
        }

        await vm.load()
        vm.query = "ab"
        await vm.searchTask?.value

        #expect(await recorder.seen == .milliseconds(300))
    }

    // MARK: - Fix round 1

    /// Finding #1 (`content-lists.md:256,278-280`): a terminal (initial/refresh) failure with
    /// existing content must keep that content retrievable, not blank it. `lastItems` is the VM
    /// half of that contract -- `ContentListView` reads it to keep the list on screen while
    /// `.error` shows a banner instead of the full-page `ErrorStateView`.
    @Test func refreshFailureAfterContentYieldsErrorStateButRetainsLastItems() async {
        let firstPage = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: nil)
        let client = FlakyAfterFirstCallClient(firstPage: firstPage) // every call after the first throws
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        #expect(vm.lastItems.count == 20)

        await vm.refresh() // throws -- terminal failure, not a pagination failure

        #expect(vm.state == .error)
        #expect(vm.lastItems.count == 20) // still the last successfully fetched page, not emptied
    }

    /// Finding #2: nothing previously asserted that `filter.state` actually reaches
    /// `catalog.content(type:cursor:limit:filter:query:)` unchanged -- a persisted category plus
    /// every typed length/date/sort filter must all survive the round trip.
    @Test func persistedFilterReachesContentRequestUnchanged() async {
        let filterState = FilterState(categoryId: "c9", categoryName: "Tafsir", length: .short, date: .last7Days, sort: .mostPopular)
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(state: filterState), sleep: noSleep)

        await vm.load()

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].filter == filterState)
    }

    /// Gate B1-I1: the banner is driven by an *event*, not a Bool level. `refresh()` never passes
    /// through `.loading`, so a second consecutive terminal failure assigns `.error` over `.error`
    /// -- and a second consecutive pagination failure re-emits `paginationError: true` over
    /// `true`. Either way the view's `.onChange` saw no transition and never refired: the user
    /// pulled to refresh and *nothing at all* happened. `errorToken` must advance once per
    /// failure, whatever the state around it looks like.
    @Test func everyConsecutiveFailureBumpsTheErrorToken() async {
        let firstPage = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: "1")
        let client = FlakyAfterFirstCallClient(firstPage: firstPage) // every call after the first throws
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        #expect(vm.errorToken == 0)

        await vm.loadMore()       // pagination failure #1
        #expect(vm.errorToken == 1)
        await vm.retryPagination() // pagination failure #2 -- state is byte-identical to failure #1
        #expect(vm.errorToken == 2)

        await vm.refresh()        // terminal failure #1
        #expect(vm.errorToken == 3)
        await vm.refresh()        // terminal failure #2 -- `.error` assigned over `.error`
        #expect(vm.errorToken == 4)
    }

    /// Gate B1-C2: `lastItems` retention is scoped to the parameters the items were fetched under.
    /// `performFullLoad` serves filter changes and search too, so a failed reload used to leave the
    /// previous query's list on screen under the new label -- 20 Fiqh channels under a
    /// "Category: Quran" chip, with nothing saying it was stale.
    @Test func failedReloadUnderANewFilterDoesNotRetainThePreviousFiltersItems() async {
        let firstPage = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: nil)
        let client = FlakyAfterFirstCallClient(firstPage: firstPage) // every call after the first throws
        let filter = FakeFilterStore(state: FilterState(categoryId: "fiqh", categoryName: "Fiqh"))
        let vm = ContentListViewModel(type: .channels, catalog: client, filter: filter, sleep: noSleep)

        await vm.load()
        #expect(vm.lastItems.count == 20)

        filter.setCategory(id: "quran", name: "Quran")
        await vm.load() // fails under the *new* filter

        #expect(vm.state == .error)
        #expect(vm.lastItems.isEmpty) // nothing to present as an answer for "Quran"
    }

    /// The same rule for a search-query change: the pre-search list must not survive a failed
    /// search under a non-empty search field.
    @Test func failedReloadUnderANewQueryDoesNotRetainThePreSearchItems() async {
        let firstPage = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: nil)
        let client = FlakyAfterFirstCallClient(firstPage: firstPage)
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        #expect(vm.lastItems.count == 20)

        vm.query = "nasheed"
        await vm.searchTask?.value // the debounced fetch fails

        #expect(vm.state == .error)
        #expect(vm.lastItems.isEmpty)
    }


    // MARK: - Gate wave-2

    /// W2: load-more A is cancelled by a full load while it is suspended, then load-more B starts.
    /// A's late return used to run an unconditional `defer { isLoadingMore = false }` and unlock
    /// B's in-flight guard, so a third `loadMore()` ran concurrently with B against the same
    /// cursor -- a duplicate fetch and a flickering spinner. The `defer` is generation-scoped now.
    @Test func aCancelledLoadMoreDoesNotUnlockANewerOnesGuard() async {
        let gateA = Gate()
        let gateB = Gate()
        let page = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: "1")
        let client = MultiGateCatalogClient(page: page, gates: [2: gateA, 4: gateB])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()                              // call 1
        let loadMoreA = Task { await vm.loadMore() } // call 2 -- suspends on gateA
        await gateA.waitUntilBlocked()
        await vm.refresh()                           // call 3 -- cancels A, resolves immediately
        let loadMoreB = Task { await vm.loadMore() } // call 4 -- suspends on gateB
        await gateB.waitUntilBlocked()

        await gateA.release()                        // A returns late, cancelled
        _ = await loadMoreA.value

        #expect(await vm.loadMore() == false)        // B still holds the guard
        #expect(await client.callCount == 4)         // no fifth request

        await gateB.release()
        _ = await loadMoreB.value
    }

    /// W8: whitespace does not count toward the ≥2-char threshold and is never sent, and a field
    /// holding only spaces is not an active search (it used to show "No results" for `q="  "`).
    @Test func whitespaceOnlyQueryIsNeitherSentNorTreatedAsAnActiveSearch() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        vm.query = "  "
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 2)
        #expect(calls[1].query == nil)
        guard case .content(_, _, _, let isSearchActive) = vm.state else { Issue.record("expected .content"); return }
        #expect(isSearchActive == false)
    }

    @Test func queryIsTrimmedBeforeItIsSent() async {
        let client = RecordingCatalogClient(pages: [CursorPage(items: items(count: 3, prefix: "a"), nextCursor: nil)])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()
        vm.query = "  ab  "
        await vm.searchTask?.value

        #expect(await client.calls[1].query == "ab")
    }

    /// Gate wave-3 D1: `loadMore()` used to return `true` unconditionally once its task completed,
    /// including when a concurrent full load had cancelled it. The view takes that as "the fetch
    /// ran" and commits its spent `PaginationGuard` attempt -- over the guard the pull-to-refresh
    /// had just reset -- after which guard 5's progress invariant refuses every later autofill.
    @Test func aLoadMoreCancelledByAFullLoadReportsThatItDidNotRun() async {
        let gate = Gate()
        let page = CursorPage(items: items(count: 20, prefix: "a"), nextCursor: "1")
        let client = MultiGateCatalogClient(page: page, gates: [2: gate])
        let vm = ContentListViewModel(type: .videos, catalog: client, filter: FakeFilterStore(), sleep: noSleep)

        await vm.load()                             // call 1
        let loadMore = Task { await vm.loadMore() } // call 2 -- suspends on the gate
        await gate.waitUntilBlocked()
        await vm.refresh()                          // call 3 -- cancels it, bumps the generation
        await gate.release()

        #expect(await loadMore.value == false)
    }
}
