import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct HomeViewModelTests {
    private func section(_ id: String, items: [ContentItem] = []) -> HomeSection {
        HomeSection(id: id, name: "Section \(id)", localizedNames: nil, icon: nil, items: items)
    }

    // MARK: - Test doubles (local to this file, same pattern as CategoriesCacheTests)

    /// Counts `home()` calls and records the last `category` param, to prove "no double fetch"
    /// and to observe what each load re-requests with.
    private actor RecordingCatalogClient: CatalogClient {
        private let page: CursorPage<HomeSection>
        private(set) var homeCallCount = 0
        private(set) var lastCategory: String?

        init(page: CursorPage<HomeSection>) { self.page = page }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            homeCallCount += 1
            lastCategory = category
            return page
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// Succeeds once (for the initial `load()`), then throws on every later call -- for testing
    /// that a `loadMore()` failure leaves the existing `.content` untouched (RULINGS #13).
    private actor FlakyCatalogClient: CatalogClient {
        struct Boom: Error {}
        private let firstPage: CursorPage<HomeSection>
        private var callCount = 0

        init(firstPage: CursorPage<HomeSection>) { self.firstPage = firstPage }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            callCount += 1
            if callCount == 1 { return firstPage }
            throw Boom()
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// The first `home()` call returns immediately (for `load()`'s setup); every call after that
    /// suspends on `gate` until released, so a test can inspect `state` mid-`refresh()`.
    private actor GatedCatalogClient: CatalogClient {
        private let page: CursorPage<HomeSection>
        private let gate: Gate
        /// `private(set)`: the W3 test proves a load-more was *refused*, which is only observable
        /// as the absence of a third request.
        private(set) var callCount = 0

        init(page: CursorPage<HomeSection>, gate: Gate) {
            self.page = page
            self.gate = gate
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            callCount += 1
            if callCount > 1 { await gate.block() }
            return page
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// Every call fails -- for the "nothing on screen yet" terminal-error path.
    private actor AlwaysFailingCatalogClient: CatalogClient {
        struct Boom: Error {}
        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            throw Boom()
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    // MARK: - Tests

    @Test func initialLoadUsesPersistedCategoryWithoutDoubleFetch() async {
        let filter = FakeFilterStore(state: FilterState(categoryId: "c9", categoryName: "Tafsir"))
        let client = RecordingCatalogClient(page: CursorPage(items: [section("a")], nextCursor: nil))
        let vm = HomeViewModel(catalog: client, filter: filter, widthClass: { .compact })

        await vm.load()

        #expect(await client.homeCallCount == 1)
        #expect(await client.lastCategory == "c9")
        guard case .content(let sections, _, _) = vm.state else {
            Issue.record("expected .content, got \(vm.state)")
            return
        }
        #expect(sections.map(\.id) == ["a"])
    }

    @Test func loadMoreDedupesByIdAndStopsWhenHasMoreIsFalse() async {
        let pages: [CursorPage<HomeSection>] = [
            CursorPage(items: [section("a")], nextCursor: "1"),
            CursorPage(items: [section("a"), section("b")], nextCursor: "2"), // "a" is a duplicate id
            CursorPage(items: [section("c")], nextCursor: nil),
        ]
        let vm = HomeViewModel(catalog: FakeCatalogClient(homePages: pages), filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load()
        guard case .content(let s1, let hasMore1, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(s1.map(\.id) == ["a"])
        #expect(hasMore1 == true)

        await vm.loadMore()
        guard case .content(let s2, let hasMore2, let loading2) = vm.state else { Issue.record("expected .content"); return }
        #expect(s2.map(\.id) == ["a", "b"]) // the duplicate "a" from page 2 was dropped
        #expect(hasMore2 == true)
        #expect(loading2 == false)

        await vm.loadMore()
        guard case .content(let s3, let hasMore3, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(s3.map(\.id) == ["a", "b", "c"])
        #expect(hasMore3 == false) // nextCursor nil -> hasMore false

        await vm.loadMore() // guarded no-op: hasMore is false
        guard case .content(let s4, _, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(s4.map(\.id) == ["a", "b", "c"]) // unchanged
    }

    @Test func loadMoreFailureLeavesContentUnchanged() async {
        let firstPage = CursorPage(items: [section("a")], nextCursor: "1")
        let vm = HomeViewModel(catalog: FlakyCatalogClient(firstPage: firstPage), filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load()
        guard case .content(let s1, let hasMore1, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(s1.map(\.id) == ["a"])

        await vm.loadMore() // the flaky client throws on this call

        guard case .content(let s2, let hasMore2, let loading2) = vm.state else {
            Issue.record("expected .content after a loadMore failure, got \(vm.state)")
            return
        }
        #expect(s2.map(\.id) == ["a"]) // unchanged
        #expect(hasMore2 == hasMore1) // unchanged
        #expect(loading2 == false) // spinner cleared, silently (RULINGS #13)
    }

    @Test func refreshNeverShowsLoadingWhenContentExists() async {
        let gate = Gate()
        let client = GatedCatalogClient(page: CursorPage(items: [section("a")], nextCursor: nil), gate: gate)
        let vm = HomeViewModel(catalog: client, filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load() // first home() call -- not gated, resolves immediately
        guard case .content = vm.state else { Issue.record("expected .content after initial load"); return }

        let refreshTask = Task { await vm.refresh() }
        await gate.waitUntilBlocked() // refresh()'s home() call has genuinely suspended now
        if case .loading = vm.state {
            Issue.record("refresh() must not show .loading while content is already on screen")
        }

        await gate.release()
        await refreshTask.value

        guard case .content = vm.state else { Issue.record("expected .content after refresh completes"); return }
    }

    // MARK: - Gate wave-2

    /// W3 (the P1): a load-more that starts while `refresh()` is still awaiting used to fetch with
    /// the *pre-refresh* cursor, and when both landed `sections` became new-page-1 + old-page-2
    /// with `nextCursor` pointing into the dead cursor sequence. It must be refused outright.
    @Test func loadMoreIsRefusedWhileARefreshIsInFlight() async {
        let gate = Gate()
        let client = GatedCatalogClient(page: CursorPage(items: [section("a")], nextCursor: "1"), gate: gate)
        let vm = HomeViewModel(catalog: client, filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load() // call 1 -- not gated
        let refreshTask = Task { await vm.refresh() }
        await gate.waitUntilBlocked() // call 2 (the refresh) has genuinely suspended

        #expect(await vm.loadMore() == false) // refused: no third request against the old cursor
        #expect(await client.callCount == 2)

        await gate.release()
        await refreshTask.value
        guard case .content(let sections, _, _) = vm.state else { Issue.record("expected .content"); return }
        #expect(sections.map(\.id) == ["a"]) // exactly the refreshed page, nothing appended to it
    }

    /// W4: a refresh failure with sections already on screen keeps them and bumps `errorToken`
    /// (the view shows a transient banner); only a failure with nothing to show is `.error`.
    @Test func refreshFailureWithVisibleContentKeepsSectionsAndBumpsErrorToken() async {
        let firstPage = CursorPage(items: [section("a")], nextCursor: nil)
        let vm = HomeViewModel(catalog: FlakyCatalogClient(firstPage: firstPage), filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load()
        #expect(vm.errorToken == 0)

        await vm.refresh() // throws

        guard case .content(let sections, _, let isLoadingMore) = vm.state else {
            Issue.record("a refresh failure must not blank loaded Home content, got \(vm.state)")
            return
        }
        #expect(sections.map(\.id) == ["a"])
        #expect(isLoadingMore == false)
        #expect(vm.errorToken == 1)

        await vm.refresh() // second consecutive failure: state is byte-identical, the event is not
        #expect(vm.errorToken == 2)
    }

    /// W4, the other half: with nothing on screen there is nothing to retain, so the first load
    /// failing still gets the full-page `.error` (which carries its own retry).
    @Test func firstLoadFailureWithNothingOnScreenIsStillATerminalError() async {
        let vm = HomeViewModel(catalog: AlwaysFailingCatalogClient(), filter: FakeFilterStore(), widthClass: { .compact })

        await vm.load()

        #expect(vm.state == .error)
        #expect(vm.errorToken == 1)
    }
}
