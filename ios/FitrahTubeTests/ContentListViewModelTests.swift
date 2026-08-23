import Foundation
import Testing
@testable import FitrahTube

/// `content-lists.md:24-400` -- shared `ContentListViewModel`, one instance per tab (`ListType`).
@Suite(.perTest)
struct ContentListViewModelTests {
    private func items(count: Int, prefix: String) -> [ContentItem] {
        (0..<count).map { i in
            ContentItem(id: "\(prefix)-\(i)", type: .video, title: "Item \(prefix)-\(i)", category: nil,
                        description: nil, thumbnailURL: nil, durationSeconds: 60, uploadedDaysAgo: 1,
                        viewCount: nil, channelTitle: nil, subscribers: nil, videoCount: nil, itemCount: nil)
        }
    }

    // MARK: - Test doubles (same pattern as HomeViewModelTests)

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

    /// Records every `content()` call's params -- proves per-type request shapes, cursor
    /// progression, and the `q` omission threshold.
    private actor RecordingCatalogClient: CatalogClient {
        struct Call: Sendable { let type: ListType?; let cursor: String?; let limit: Int; let query: String? }
        private let pages: [CursorPage<ContentItem>]
        private(set) var calls: [Call] = []

        init(pages: [CursorPage<ContentItem>]) { self.pages = pages }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            calls.append(Call(type: type, cursor: cursor, limit: limit, query: query))
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

    /// Rendezvous actor (same shape as `HomeViewModelTests.Gate`): `block()` suspends until
    /// `release()`; `waitUntilBlocked()` suspends until some caller has entered `block()`.
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

    private func noSleep(_ duration: Duration) async throws {} // debounce clock stub -- tests never wait real time

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
}
