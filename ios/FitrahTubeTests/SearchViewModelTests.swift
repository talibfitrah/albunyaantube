import Foundation
import Testing
@testable import FitrahTube

/// `search-categories.md:40-149,259-281` -- Search screen. 500 ms debounce (RULING 22, distinct
/// from the tabs' 300 ms), min 2 chars; `submit()` bypasses both and is the *only* history writer
/// (`:95-103`); the five `SearchState`s per `:167-214` collapse RULINGS #23's "<2 chars ->
/// zero-state" fix (Android leaves stale state on screen below 2 chars -- a deliberate deviation).
@Suite(.perTest)
struct SearchViewModelTests {

    private func makeHistoryStore() -> (UserDefaultsSearchHistoryStore, UserDefaults, String) {
        let suiteName = "SearchViewModelTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        return (UserDefaultsSearchHistoryStore(defaults: defaults), defaults, suiteName)
    }

    // MARK: - Test doubles

    /// Records every `search()` call's params; returns per-query canned results or throws.
    private actor RecordingCatalogClient: CatalogClient {
        struct Call: Sendable { let query: String; let type: ListType?; let limit: Int }
        struct Boom: Error {}

        private let results: [ContentItem]
        private let shouldThrow: Bool
        private(set) var calls: [Call] = []

        init(results: [ContentItem] = [], shouldThrow: Bool = false) {
            self.results = results
            self.shouldThrow = shouldThrow
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] {
            calls.append(Call(query: query, type: type, limit: limit))
            if shouldThrow { throw Boom() }
            return results
        }
    }

    /// First `search()` throws, every call after that succeeds -- for the retry test.
    private actor FailThenSucceedCatalogClient: CatalogClient {
        struct Boom: Error {}
        private let results: [ContentItem]
        private var callCount = 0
        private(set) var calls: [String] = []

        init(results: [ContentItem]) { self.results = results }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> [ContentItem] {
            calls.append(query)
            callCount += 1
            if callCount == 1 { throw Boom() }
            return results
        }
    }


    // MARK: - Initial state

    @Test func initialStateIsZeroWithPersistedHistory() {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        history.add("tafsir")
        let vm = SearchViewModel(catalog: RecordingCatalogClient(), history: history, sleep: noSleep)

        #expect(vm.state == .zero(history: ["tafsir"]))
    }

    // MARK: - <2 chars: zero-state immediately, no fetch (RULINGS #23)

    @Test func emptyQueryClearsToZeroImmediatelyWithNoFetch() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value
        guard case .results = vm.state else { Issue.record("expected .results before clearing"); return }

        vm.query = ""
        #expect(vm.state == .zero(history: []))
        #expect(await client.calls.count == 1) // no new fetch triggered by clearing
    }

    @Test func oneCharacterQueryClearsToZeroImmediately() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient()
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "a"

        #expect(vm.state == .zero(history: []))
        #expect(await client.calls.isEmpty)
    }

    // MARK: - >=2 chars: debounce then fetch

    @Test func queryAtTwoCharsDebouncesThenFetches() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient(results: items(count: 2, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == "ab")
        #expect(calls[0].type == nil)
        #expect(calls[0].limit == 50)
        guard case .results(let results) = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
        #expect(results.count == 2)
    }

    @Test func debounceWaits500Milliseconds() async {
        actor DurationRecorder { var seen: Duration?; func record(_ d: Duration) { seen = d } }
        let recorder = DurationRecorder()
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(), history: history) { duration in
            await recorder.record(duration)
        }

        vm.query = "ab"
        await vm.searchTask?.value

        #expect(await recorder.seen == .milliseconds(500))
    }

    @Test func rapidQueryChangesCoalesceToOneRequest() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient()
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "ab"
        vm.query = "abc"
        vm.query = "abcd"
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == "abcd")
    }

    @Test func emptyResultsMapToNoResultsState() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(results: []), history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value

        #expect(vm.state == .noResults)
    }

    @Test func fetchFailureMapsToErrorState() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(shouldThrow: true), history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value

        #expect(vm.state == .error)
    }

    // MARK: - submit(): bypasses debounce + min length, only history writer

    @Test func submitBypassesMinLengthAndWritesHistory() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "a" // 1 char -- below the debounce's 2-char threshold
        await vm.submit()

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == "a")
        #expect(history.entries == ["a"])
        guard case .results = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
    }

    @Test func submitWithBlankQueryDoesNothing() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient()
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "  " // whitespace only -- Kotlin's isNotBlank() guard
        await vm.submit()

        #expect(await client.calls.isEmpty)
        #expect(history.entries.isEmpty)
    }

    @Test func debouncedFetchNeverWritesHistory() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(results: items(count: 1, prefix: "r")), history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value

        #expect(history.entries.isEmpty) // only submit() writes history
    }

    // MARK: - selectHistory: sets query, submits, bumps to front

    @Test func selectHistorySubmitsAndBumpsEntryToFront() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        history.add("quran")
        history.add("tafsir")
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        await vm.selectHistory("quran")

        #expect(vm.query == "quran")
        #expect(history.entries == ["quran", "tafsir"]) // bumped to front
        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == "quran")
        guard case .results = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
    }

    // MARK: - removeHistory / clearHistory

    @Test func removeHistoryReturnsToZeroStateEvenWhileShowingResults() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        history.add("quran")
        history.add("tafsir")
        let vm = SearchViewModel(catalog: RecordingCatalogClient(results: items(count: 1, prefix: "r")), history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value
        guard case .results = vm.state else { Issue.record("expected .results before removing"); return }

        vm.removeHistory("quran")

        #expect(history.entries == ["tafsir"])
        #expect(vm.state == .zero(history: ["tafsir"]))
        #expect(vm.query == "ab") // field text is not cleared (search-categories.md:74-75)
    }

    @Test func clearHistoryEmptiesEverythingAndReturnsToZero() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        history.add("quran")
        let vm = SearchViewModel(catalog: RecordingCatalogClient(), history: history, sleep: noSleep)

        vm.clearHistory()

        #expect(history.entries.isEmpty)
        #expect(vm.state == .zero(history: []))
    }

    // MARK: - retry(): re-runs the current query, never writes history

    @Test func retryReRunsTheCurrentQueryWithoutWritingHistory() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = FailThenSucceedCatalogClient(results: items(count: 3, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value
        #expect(vm.state == .error)

        await vm.retry()

        guard case .results(let results) = vm.state else { Issue.record("expected .results after retry, got \(vm.state)"); return }
        #expect(results.count == 3)
        let calls = await client.calls
        #expect(calls == ["ab", "ab"])
        #expect(history.entries.isEmpty)
    }

    // MARK: - YouTube URL/ID fast path (search-categories.md §1.7 -- entirely server-side; the
    // client must forward the string verbatim, with no client-side URL parsing/rewriting)

    @Test func urlLikeQueryIsForwardedVerbatimToSearch() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let url = "https://www.youtube.com/watch?v=dQw4w9WgXcQ"
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = url
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == url)
    }
}
