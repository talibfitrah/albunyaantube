import Foundation
import Testing
@testable import FitrahTube

/// `content-lists.md:603-701` -- Featured "See all". Probe `/home`, decide sections-vs-flat mode
/// by `hasSubcategories = probe.items.any { $0.id != categoryId }`, then either stay in sections
/// mode or fall through to a flat `/content` fetch (`type: nil`, `limit: 50`). Same `loadTask`/
/// `loadMoreTask` ownership pattern as `HomeViewModel`/`ContentListViewModel`.
@Suite(.perTest)
struct FeaturedViewModelTests {
    private func section(_ id: String, items: [ContentItem] = []) -> HomeSection {
        HomeSection(id: id, name: "Section \(id)", localizedNames: nil, icon: nil, items: items)
    }

    private func items(count: Int, prefix: String) -> [ContentItem] {
        (0..<count).map { i in
            ContentItem(id: "\(prefix)-\(i)", type: .video, title: "Item \(prefix)-\(i)", category: nil,
                        description: nil, thumbnailURL: nil, durationSeconds: 60, uploadedDaysAgo: 1,
                        viewCount: nil, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)
        }
    }

    // MARK: - Test doubles (same shape as HomeViewModelTests/ContentListViewModelTests)

    /// Records every `home()`/`content()` call's params -- proves the probe shape, the flat-mode
    /// shape, the `FEATURED_CATEGORY_ID` fallback, and cursor progression.
    private actor RecordingCatalogClient: CatalogClient {
        struct HomeCall: Sendable { let cursor: String?; let categoryLimit: Int; let contentLimit: Int; let category: String? }
        struct ContentCall: Sendable { let type: ListType?; let cursor: String?; let limit: Int; let category: String? }

        private var homePages: [String: CursorPage<HomeSection>] // keyed by cursor ?? "first"
        private var contentPages: [String: CursorPage<ContentItem>]
        private(set) var homeCalls: [HomeCall] = []
        private(set) var contentCalls: [ContentCall] = []

        init(homePages: [String: CursorPage<HomeSection>], contentPages: [String: CursorPage<ContentItem>] = [:]) {
            self.homePages = homePages
            self.contentPages = contentPages
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            homeCalls.append(HomeCall(cursor: cursor, categoryLimit: categoryLimit, contentLimit: contentLimit, category: category))
            return homePages[cursor ?? "first"] ?? CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            contentCalls.append(ContentCall(type: type, cursor: cursor, limit: limit, category: filter.categoryId))
            return contentPages[cursor ?? "first"] ?? CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// `home()` always fails (probe never resolves sections mode); `content()` succeeds once then
    /// fails -- for the flat-mode load-more latch test.
    private actor FlakyFlatCatalogClient: CatalogClient {
        struct Boom: Error {}
        private let firstPage: CursorPage<ContentItem>
        private var contentCallCount = 0

        init(firstPage: CursorPage<ContentItem>) { self.firstPage = firstPage }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            throw Boom() // probe always fails -> flat mode
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            contentCallCount += 1
            if contentCallCount == 1 { return firstPage }
            throw Boom()
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// Probe always fails, and the flat fetch itself fails too -- for the terminal `.error` test.
    private struct AlwaysFailingCatalogClient: CatalogClient {
        struct Boom: LocalizedError { var errorDescription: String? { "network unreachable" } }
        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> { throw Boom() }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> { throw Boom() }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    /// Rendezvous actor (same shape as HomeViewModelTests.Gate).
    private actor Gate {
        private var blockedContinuation: CheckedContinuation<Void, Never>?
        private var releaseContinuation: CheckedContinuation<Void, Never>?
        func block() async {
            await withCheckedContinuation { continuation in
                releaseContinuation = continuation
                blockedContinuation?.resume()
                blockedContinuation = nil
            }
        }
        func waitUntilBlocked() async {
            if releaseContinuation != nil { return }
            await withCheckedContinuation { blockedContinuation = $0 }
        }
        func release() {
            releaseContinuation?.resume()
            releaseContinuation = nil
        }
    }

    /// The first `home()` call resolves immediately (flat-mode fallback via empty sections);
    /// every call after that suspends on `gate`.
    private actor GatedFlatCatalogClient: CatalogClient {
        private let page: CursorPage<ContentItem>
        private let gate: Gate
        private var contentCallCount = 0

        init(page: CursorPage<ContentItem>, gate: Gate) {
            self.page = page
            self.gate = gate
        }
        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil) // always empty -> flat mode every time
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            contentCallCount += 1
            if contentCallCount > 1 { await gate.block() }
            return page
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] { [] }
    }

    // MARK: - Probe -> sections vs flat

    @Test func probeWithASubcategoryEntersSectionsMode() async {
        let client = RecordingCatalogClient(homePages: [
            "first": CursorPage(items: [section("child1"), section("child2")], nextCursor: "s2"),
        ])
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: "Parent", catalog: client)

        await vm.load()

        guard case .content(.sections(let sections), let hasMore) = vm.state else {
            Issue.record("expected sections mode, got \(vm.state)"); return
        }
        #expect(sections.map(\.id) == ["child1", "child2"])
        #expect(hasMore == true)
        let homeCalls = await client.homeCalls
        #expect(homeCalls.count == 1)
        #expect(homeCalls[0].category == "parent1")
        #expect(homeCalls[0].categoryLimit == 10)
        #expect(homeCalls[0].contentLimit == 20)
        #expect(homeCalls[0].cursor == nil)
        #expect(await client.contentCalls.isEmpty) // never falls through to flat
    }

    @Test func probeWithNoSubcategoriesFallsThroughToFlatMode() async {
        // Every section id equals categoryId itself -- no distinct subcategory -> flat mode.
        let client = RecordingCatalogClient(
            homePages: ["first": CursorPage(items: [section("parent1")], nextCursor: "ignored")],
            contentPages: ["first": CursorPage(items: items(count: 3, prefix: "f"), nextCursor: nil)]
        )
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()

        guard case .content(.flat(let flatItems), let hasMore) = vm.state else {
            Issue.record("expected flat mode, got \(vm.state)"); return
        }
        #expect(flatItems.map(\.id) == ["f-0", "f-1", "f-2"])
        #expect(hasMore == false)
        let contentCalls = await client.contentCalls
        #expect(contentCalls.count == 1)
        #expect(contentCalls[0].type == nil) // no ALL type on the wire -- omitted
        #expect(contentCalls[0].limit == 50)
        #expect(contentCalls[0].category == "parent1")
        #expect(contentCalls[0].cursor == nil)
    }

    @Test func emptyProbeFallsThroughToFlatMode() async {
        let client = RecordingCatalogClient(
            homePages: ["first": CursorPage(items: [], nextCursor: nil)],
            contentPages: ["first": CursorPage(items: items(count: 1, prefix: "f"), nextCursor: nil)]
        )
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()

        guard case .content(.flat, _) = vm.state else { Issue.record("expected flat mode, got \(vm.state)"); return }
    }

    @Test func probeFailureSilentlyFallsThroughToFlatMode() async {
        let client = FlakyFlatCatalogClient(firstPage: CursorPage(items: items(count: 2, prefix: "f"), nextCursor: nil))
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()

        guard case .content(.flat(let flatItems), _) = vm.state else { Issue.record("expected flat mode, got \(vm.state)"); return }
        #expect(flatItems.count == 2)
    }

    @Test func flatModeFailureAfterProbeFailureSurfacesError() async {
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: AlwaysFailingCatalogClient())

        await vm.load()

        guard case .error(let message) = vm.state else { Issue.record("expected .error, got \(vm.state)"); return }
        #expect(message == "network unreachable")
    }

    // MARK: - FEATURED_CATEGORY_ID fallback

    @Test func nilCategoryIdFallsBackToFeaturedConstant() async {
        let client = RecordingCatalogClient(homePages: ["first": CursorPage(items: [], nextCursor: nil)],
                                             contentPages: ["first": CursorPage(items: [], nextCursor: nil)])
        let vm = FeaturedViewModel(categoryId: nil, categoryName: nil, catalog: client)

        await vm.load()

        #expect(await client.homeCalls.first?.category == FeaturedViewModel.featuredCategoryId)
    }

    @Test func emptyCategoryIdFallsBackToFeaturedConstant() async {
        let client = RecordingCatalogClient(homePages: ["first": CursorPage(items: [], nextCursor: nil)],
                                             contentPages: ["first": CursorPage(items: [], nextCursor: nil)])
        let vm = FeaturedViewModel(categoryId: "", categoryName: nil, catalog: client)

        await vm.load()

        #expect(await client.homeCalls.first?.category == FeaturedViewModel.featuredCategoryId)
    }

    // MARK: - Empty state (RULINGS #20 -- an iOS addition, Android has none)

    @Test func zeroSectionsAndZeroFlatItemsIsEmptyState() async {
        // Empty probe -> flat mode; flat mode also returns zero items.
        let client = RecordingCatalogClient(
            homePages: ["first": CursorPage(items: [], nextCursor: nil)],
            contentPages: ["first": CursorPage(items: [], nextCursor: nil)]
        )
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()

        #expect(vm.state == .empty)
    }

    // MARK: - Load more (mode-dependent cursor + hasMore)

    @Test func loadMoreSectionsAppendsAndTracksItsOwnCursor() async {
        let client = RecordingCatalogClient(homePages: [
            "first": CursorPage(items: [section("child1")], nextCursor: "s2"),
            "s2": CursorPage(items: [section("child2")], nextCursor: nil),
        ])
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()
        await vm.loadMore()

        guard case .content(.sections(let sections), let hasMore) = vm.state else { Issue.record("expected sections mode"); return }
        #expect(sections.map(\.id) == ["child1", "child2"])
        #expect(hasMore == false)
        let calls = await client.homeCalls
        #expect(calls.map(\.cursor) == [nil, "s2"])
    }

    @Test func loadMoreFlatAppendsAndTracksItsOwnCursor() async {
        let client = RecordingCatalogClient(
            homePages: ["first": CursorPage(items: [], nextCursor: nil)], // always empty -> flat mode
            contentPages: [
                "first": CursorPage(items: items(count: 2, prefix: "p0"), nextCursor: "c2"),
                "c2": CursorPage(items: items(count: 2, prefix: "p1"), nextCursor: nil),
            ]
        )
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()
        await vm.loadMore()

        guard case .content(.flat(let flatItems), let hasMore) = vm.state else { Issue.record("expected flat mode"); return }
        #expect(flatItems.count == 4)
        #expect(hasMore == false)
        let calls = await client.contentCalls
        #expect(calls.map(\.cursor) == [nil, "c2"])
    }

    // MARK: - Load-more latch (search-categories.md:412 / content-lists.md §7.4 "retry latch")

    @Test func loadMoreFailureSetsLatchKeepsItemsAndBlocksFurtherAutoRetries() async {
        let firstPage = CursorPage(items: items(count: 2, prefix: "a"), nextCursor: "next")
        let client = FlakyFlatCatalogClient(firstPage: firstPage)
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load() // flat mode (probe always fails), first content() call succeeds
        guard case .content(.flat(let items1), let hasMore1) = vm.state else { Issue.record("expected flat mode"); return }
        #expect(items1.count == 2)
        #expect(hasMore1 == true)

        await vm.loadMore() // content() throws -> silent failure, latch set
        guard case .content(.flat(let items2), let hasMore2) = vm.state else { Issue.record("expected flat mode after failed loadMore"); return }
        #expect(items2.count == 2) // unchanged
        #expect(hasMore2 == true) // cursor kept
        #expect(vm.lastLoadFailed == true)

        await vm.loadMore() // latched -- must not even attempt another fetch
        guard case .content(.flat(let items3), _) = vm.state else { Issue.record("expected flat mode"); return }
        #expect(items3.count == 2) // still unchanged, no third content() call took effect
    }

    @Test func refreshClearsTheLatch() async {
        let firstPage = CursorPage(items: items(count: 2, prefix: "a"), nextCursor: "next")
        let client = FlakyFlatCatalogClient(firstPage: firstPage)
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

        await vm.load()
        await vm.loadMore() // fails -> latch set
        #expect(vm.lastLoadFailed == true)

        await vm.refresh() // full reload -- the recovery path (pull-to-refresh, RULINGS #20)
        #expect(vm.lastLoadFailed == false)
    }

    // MARK: - Refresh never shows .loading while content exists (same pattern as Home/ContentList)

    @Test func refreshNeverShowsLoadingWhenContentExists() async {
        let gate = Gate()
        let client = GatedFlatCatalogClient(page: CursorPage(items: items(count: 2, prefix: "a"), nextCursor: nil), gate: gate)
        let vm = FeaturedViewModel(categoryId: "parent1", categoryName: nil, catalog: client)

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
}
