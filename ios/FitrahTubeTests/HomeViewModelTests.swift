import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct HomeViewModelTests {
    private func section(_ id: String, items: [ContentItem] = []) -> HomeSection {
        HomeSection(id: id, name: "Section \(id)", localizedNames: nil, icon: nil, items: items)
    }

    // MARK: - Test doubles (local to this file, same pattern as CategoriesCacheTests)

    @MainActor @Observable fileprivate final class FakeFilterStore: FilterStore {
        private(set) var state: FilterState
        init(state: FilterState = FilterState()) { self.state = state }
        func setCategory(id: String?, name: String?) {
            let id = id?.isEmpty == true ? nil : id
            state.categoryId = id
            state.categoryName = id == nil ? nil : name
        }
        func clearCategory() { setCategory(id: nil, name: nil) }
    }

    /// Counts `home()` calls and records the last `category` param, to prove "no double fetch"
    /// and to observe what `clearFilter()` re-requests with.
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

    /// A rendezvous point: `block()` suspends until `release()` is called; `waitUntilBlocked()`
    /// suspends until some caller has actually entered `block()` -- whichever of the two arrives
    /// first at the actor just hands off to the other, so there's no timing race either way.
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

    /// The first `home()` call returns immediately (for `load()`'s setup); every call after that
    /// suspends on `gate` until released, so a test can inspect `state` mid-`refresh()`.
    private actor GatedCatalogClient: CatalogClient {
        private let page: CursorPage<HomeSection>
        private let gate: Gate
        private var callCount = 0

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

    @Test func playerArgsPrefersChannelTitleFallingBackToCategory() {
        let vm = HomeViewModel(catalog: FakeCatalogClient(), filter: FakeFilterStore(), widthClass: { .compact })

        let withChannelTitle = ContentItem(
            id: "v1", type: .video, title: "Title", category: "Fiqh", description: "Desc",
            thumbnailURL: URL(string: "https://example.com/a.jpg"), durationSeconds: 90,
            uploadedDaysAgo: 2, viewCount: 500, channelTitle: "Al-Huda Institute",
            subscribers: nil, videoCount: nil, itemCount: nil
        )
        let argsWithChannel = vm.playerArgs(for: withChannelTitle)
        #expect(argsWithChannel.videoId == "v1")
        #expect(argsWithChannel.title == "Title")
        #expect(argsWithChannel.channelName == "Al-Huda Institute")
        #expect(argsWithChannel.thumbnailURL == withChannelTitle.thumbnailURL)
        #expect(argsWithChannel.description == "Desc")
        #expect(argsWithChannel.durationSeconds == 90)
        #expect(argsWithChannel.viewCount == 500)

        let withoutChannelTitle = ContentItem(
            id: "v2", type: .video, title: "Title 2", category: "Fallback Category", description: nil,
            thumbnailURL: nil, durationSeconds: nil, uploadedDaysAgo: nil, viewCount: nil,
            channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil
        )
        #expect(vm.playerArgs(for: withoutChannelTitle).channelName == "Fallback Category")
    }

    @Test func seeAllLabelContainsSectionName() {
        let vm = HomeViewModel(catalog: FakeCatalogClient(), filter: FakeFilterStore(), widthClass: { .compact })
        let label = vm.seeAllLabel(for: section("a"))
        #expect(label.contains("Section a"))
    }

    @Test func clearFilterClearsTheStoreAndReloadsWithoutACategory() async {
        let filter = FakeFilterStore(state: FilterState(categoryId: "c1", categoryName: "Cat"))
        let client = RecordingCatalogClient(page: CursorPage(items: [section("a")], nextCursor: nil))
        let vm = HomeViewModel(catalog: client, filter: filter, widthClass: { .compact })

        await vm.load()
        #expect(await client.lastCategory == "c1")

        vm.clearFilter()
        await vm.loadTask?.value

        #expect(filter.state.categoryId == nil)
        #expect(filter.state.categoryName == nil)
        #expect(await client.homeCallCount == 2)
        #expect(await client.lastCategory == nil)
        guard case .content = vm.state else { Issue.record("expected .content after clearFilter reload"); return }
    }
}
