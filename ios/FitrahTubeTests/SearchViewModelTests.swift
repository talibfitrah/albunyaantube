import Foundation
import Testing
@testable import FitrahTube

/// `search-categories.md:40-149,259-281` -- Search screen. 300 ms debounce (owner, 2026-10-13 --
/// supersedes RULING 22's 500 ms; same as the tabs), min 2 chars; `submit()` bypasses both and is the *only* history writer
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
        private let match: SearchMatch
        private let shouldThrow: Bool
        private(set) var calls: [Call] = []

        init(results: [ContentItem] = [], match: SearchMatch = .exact, shouldThrow: Bool = false) {
            self.results = results
            self.match = match
            self.shouldThrow = shouldThrow
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult {
            calls.append(Call(query: query, type: type, limit: limit))
            if shouldThrow { throw Boom() }
            return SearchResult(items: results, match: match)
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
        func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult {
            calls.append(query)
            callCount += 1
            if callCount == 1 { throw Boom() }
            return SearchResult(items: results)
        }
    }

    /// The first `search()` answers at once; every later one suspends on `gate`.
    private actor GatedSearchClient: CatalogClient {
        private let results: [ContentItem]
        private let gate: Gate
        private var calls = 0

        init(results: [ContentItem], gate: Gate) {
            self.results = results
            self.gate = gate
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult {
            calls += 1
            if calls > 1 { await gate.block() }
            return SearchResult(items: results)
        }
    }

    /// Answers each query with its own items (`"<q>-0"`, …; `counts` sets how many, default 3) at
    /// the tier `matches` gives it (default exact). A query in `gates` waits there first; one in
    /// `failing` then throws.
    private actor EchoSearchClient: CatalogClient {
        struct Boom: Error {}
        private let gates: [String: Gate]
        private let failing: Set<String>
        private let counts: [String: Int]
        private let matches: [String: SearchMatch]

        init(gates: [String: Gate] = [:], failing: Set<String> = [], counts: [String: Int] = [:],
             matches: [String: SearchMatch] = [:]) {
            self.gates = gates
            self.failing = failing
            self.counts = counts
            self.matches = matches
        }

        func categories() async throws -> [FitrahTube.Category] { [] }
        func home(cursor: String?, categoryLimit: Int, contentLimit: Int, category: String?) async throws -> CursorPage<HomeSection> {
            CursorPage(items: [], nextCursor: nil)
        }
        func content(type: ListType?, cursor: String?, limit: Int, filter: FilterState, query: String?) async throws -> CursorPage<ContentItem> {
            CursorPage(items: [], nextCursor: nil)
        }
        func search(query: String, type: ListType?, limit: Int) async throws -> SearchResult {
            if let gate = gates[query] { await gate.block() }
            if failing.contains(query) { throw Boom() }
            return SearchResult(items: await items(count: counts[query] ?? 3, prefix: query), match: matches[query] ?? .exact)
        }
    }

    /// What VoiceOver was asked to say, in order.
    @MainActor private final class Announcements {
        private(set) var spoken: [String] = []
        func record(_ text: String) { spoken.append(text) }
    }

    private static func count(_ n: Int) -> String {
        Format.localizedFormat("list_footer_status", locale: .current, Int64(n))
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
        guard case .results(let results, _) = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
        #expect(results.count == 2)
    }

    @Test func debounceWaits300Milliseconds() async {
        actor DurationRecorder { var seen: Duration?; func record(_ d: Duration) { seen = d } }
        let recorder = DurationRecorder()
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(), history: history) { duration in
            await recorder.record(duration)
        }

        vm.query = "ab"
        await vm.searchTask?.value

        #expect(await recorder.seen == .milliseconds(300))
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

        guard case .results(let results, _) = vm.state else { Issue.record("expected .results after retry, got \(vm.state)"); return }
        #expect(results.count == 3)
        let calls = await client.calls
        #expect(calls == ["ab", "ab"])
        #expect(history.entries.isEmpty)
    }

    // MARK: - Spec §6: no spinner swap, the match tier reaches the state

    @Test func resultsStayOnScreenWhileTheNextSearchLoads() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let gate = Gate()
        let vm = SearchViewModel(catalog: GatedSearchClient(results: items(count: 2, prefix: "r"), gate: gate),
                                 history: history, sleep: noSleep)
        vm.query = "ab"
        await vm.searchTask?.value

        let next = Task { vm.query = "abc"; await vm.searchTask?.value }
        await gate.waitUntilBlocked()
        guard case .results = vm.state else { Issue.record("results must stay while loading, got \(vm.state)"); return }
        #expect(vm.isSearching)

        await gate.release()
        await next.value
        #expect(!vm.isSearching)
    }

    @Test func theMatchTierReachesTheResultsState() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(results: items(count: 1, prefix: "r"), match: .approximate),
                                 history: history, sleep: noSleep)
        vm.query = "zakit"
        await vm.searchTask?.value
        #expect(vm.state == .results(items(count: 1, prefix: "r"), match: .approximate))
    }

    // MARK: - YouTube URL/ID fast path (search-categories.md §1.7 -- entirely server-side; the
    // client must forward the string verbatim, with no client-side URL parsing/rewriting)

    @Test func urlLikeQueryIsForwardedVerbatimToSearch() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let url = "https://www.youtube.com/watch?v=xc7keR2piUM"
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = url
        await vm.searchTask?.value

        let calls = await client.calls
        #expect(calls.count == 1)
        #expect(calls[0].query == url)
    }

    // MARK: - Gate wave-3 D5 (trim, matching ContentListViewModel)

    @Test func whitespacePaddedQueryIsTrimmedBeforeSearchingAndBeforeHistory() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient(results: items(count: 1, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "  ab  "
        await vm.searchTask?.value          // the debounced path
        #expect(await client.calls.map(\.query) == ["ab"])

        await vm.submit()                   // the immediate path
        #expect(await client.calls.map(\.query) == ["ab", "ab"])
        #expect(history.entries == ["ab"])  // not "  ab  "
    }

    /// Gate wave-4 V5: the trim moved into `performSearch`, the funnel all three entry points
    /// share, because `retry()` had no trim of its own and forwarded `query` verbatim -- so
    /// retrying a failed `"  ab  "` ran a different search than the one that failed (and, against a
    /// real backend, most likely returned "No results").
    @Test func retryTrimsTheQueryLikeEveryOtherEntryPoint() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = FailThenSucceedCatalogClient(results: items(count: 3, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "  ab  "
        await vm.searchTask?.value
        #expect(vm.state == .error)

        await vm.retry()

        #expect(await client.calls == ["ab", "ab"]) // not ["ab", "  ab  "]
    }

    /// Gate cubic-r3 X4: the "no results" message must show the trimmed value the search ran
    /// with, not the raw (padded) field text.
    @Test func theNoResultsQueryIsTrimmedNotRawFieldText() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: RecordingCatalogClient(results: []), history: history, sleep: noSleep)

        vm.query = "  quran  "
        await vm.searchTask?.value

        #expect(vm.state == .noResults)
        #expect(vm.shownQuery == "quran")
    }

    @Test func whitespaceOnlyQueryNeverFetches() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient()
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)

        vm.query = "  "                     // two chars, zero of them a query
        await vm.searchTask?.value          // no task was ever spawned

        #expect(await client.calls.isEmpty)
        guard case .zero = vm.state else { Issue.record("expected .zero, got \(vm.state)"); return }
    }

    // MARK: - Final wave: the shown query, the spinner's exits, VoiceOver

    /// `SearchView` scrolls to the top when `shownQuery` changes, so it must move only when a
    /// different query's results land: never for the same query again, and never mid-flight.
    @Test func theShownQueryMovesOnlyWhenANewQuerysResultsLand() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let abc = Gate()
        let vm = SearchViewModel(catalog: EchoSearchClient(gates: ["abc": abc]), history: history, sleep: noSleep)

        vm.query = "ab"
        await vm.searchTask?.value
        #expect(vm.shownQuery == "ab")

        vm.query = "abc"
        let typed = vm.searchTask
        await abc.waitUntilBlocked()
        #expect(vm.shownQuery == "ab")   // the old results are still the ones on screen
        await abc.release()
        await typed?.value
        #expect(vm.shownQuery == "abc")

        vm.query = ""
        #expect(vm.shownQuery == nil)
    }

    /// The toolbar spinner only shows over results, but the flag must not outlive them: leaving
    /// the results while a search runs (fewer than 2 characters, removing or clearing history)
    /// ends it.
    @Test(arguments: ["short", "remove", "clear"])
    func everyWayOutOfTheResultsStopsTheSpinner(exit: String) async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        history.add("tafsir")
        let abc = Gate()
        let vm = SearchViewModel(catalog: EchoSearchClient(gates: ["abc": abc]), history: history, sleep: noSleep)
        vm.query = "ab"
        await vm.searchTask?.value

        vm.query = "abc"
        let typed = vm.searchTask
        await abc.waitUntilBlocked()
        #expect(vm.isSearching)

        switch exit {
        case "short": vm.query = "a"
        case "remove": vm.removeHistory("tafsir")
        default: vm.clearHistory()
        }
        #expect(!vm.isSearching, "\(exit) left the spinner on")

        await abc.release()
        await typed?.value
        #expect(!vm.isSearching, "\(exit): the cancelled search turned it back on")
    }

    /// Pins today's UX (the same as Android's): a search that fails over results replaces them
    /// with the error state and its Retry; the spinner stops.
    @Test func aFailedSearchOverResultsShowsTheErrorStateAndStopsTheSpinner() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let vm = SearchViewModel(catalog: EchoSearchClient(failing: ["abc"]), history: history, sleep: noSleep)
        vm.query = "ab"
        await vm.searchTask?.value

        vm.query = "abc"
        await vm.searchTask?.value

        #expect(vm.state == .error)
        #expect(!vm.isSearching)
        #expect(vm.shownQuery == nil)
    }

    /// Stage 3a S3-1 / stage 5 F3 (Android parity): "No results" stays on screen under the
    /// toolbar spinner while the next keystroke's search runs, instead of flashing the full-screen
    /// "Searching…" view and back on every pause in typing.
    @Test func noResultsStayOnScreenWhileTheNextSearchLoads() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let zzzq = Gate()
        let vm = SearchViewModel(catalog: EchoSearchClient(gates: ["zzzq": zzzq], counts: ["zzz": 0]),
                                 history: history, sleep: noSleep)
        vm.query = "zzz"
        await vm.searchTask?.value
        #expect(vm.state == .noResults)

        vm.query = "zzzq"
        let typed = vm.searchTask
        await zzzq.waitUntilBlocked()
        #expect(vm.state == .noResults, "the empty state flipped to \(vm.state) mid-search")
        #expect(vm.isSearching)
        #expect(vm.shownQuery == "zzz", "the no-results message names what was searched, not what is typed")

        await zzzq.release()
        await typed?.value
        guard case .results = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
        #expect(!vm.isSearching)
    }

    /// Stage 3a S3-2 / stage 5 F2 (Android parity): typing back to the query on screen (a
    /// keystroke undone inside the debounce, a trailing space) fetches nothing, so a flaky
    /// refetch can never swap a good list for the error card.
    @Test(arguments: ["undo", "space"])
    func typingBackToTheShownQueryFetchesNothing(edit: String) async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let client = RecordingCatalogClient(results: items(count: 3, prefix: "r"))
        let vm = SearchViewModel(catalog: client, history: history, sleep: noSleep)
        vm.query = "quran"
        await vm.searchTask?.value

        if edit == "undo" { vm.query = "qurans"; vm.query = "quran" } else { vm.query = "quran " }
        await vm.searchTask?.value

        #expect(await client.calls.map(\.query) == ["quran"])
        #expect(vm.state == .results(items(count: 3, prefix: "r"), match: .exact))
        #expect(!vm.isSearching)
    }

    /// With nothing on screen there is no list to keep: the full-screen `.loading`, no spinner.
    @Test func aSearchFromAnEmptyScreenShowsLoadingWithoutTheSpinner() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let ab = Gate()
        let vm = SearchViewModel(catalog: EchoSearchClient(gates: ["ab": ab]), history: history, sleep: noSleep)

        vm.query = "ab"
        let typed = vm.searchTask
        await ab.waitUntilBlocked()
        #expect(vm.state == .loading)
        #expect(!vm.isSearching)

        await ab.release()
        await typed?.value
        guard case .results = vm.state else { Issue.record("expected .results, got \(vm.state)"); return }
    }

    /// VoiceOver hears a new query's results replace the list: their count, once the search lands.
    /// No results says so (`search_no_results`); the same query again and a failure say nothing new.
    @Test func aNewQuerysResultsAreAnnouncedWithTheirCount() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let spoken = Announcements()
        let vm = SearchViewModel(catalog: EchoSearchClient(failing: ["abce"], counts: ["abc": 5, "abcd": 0]),
                                 history: history, sleep: noSleep, announce: spoken.record)

        vm.query = "ab"
        await vm.searchTask?.value
        #expect(spoken.spoken == [Self.count(3)])

        await vm.submit()                    // the same query: the list does not change
        #expect(spoken.spoken == [Self.count(3)])

        vm.query = "abc"
        await vm.searchTask?.value
        #expect(spoken.spoken == [Self.count(3), Self.count(5)])

        vm.query = "abcd"                    // no results: the empty state posts nothing itself
        await vm.searchTask?.value
        let none = String(localized: "search_no_results")
        #expect(spoken.spoken == [Self.count(3), Self.count(5), none])
        vm.query = "abce"                    // a failure: the error state and its Retry
        await vm.searchTask?.value
        #expect(spoken.spoken == [Self.count(3), Self.count(5), none])

        vm.query = "abc"                     // back to results after leaving them: a new list
        await vm.searchTask?.value
        #expect(spoken.spoken == [Self.count(3), Self.count(5), none, Self.count(5)])
    }

    /// Android's note is a live region; here the note's text is spoken when it appears, ahead of
    /// the count, and not again while it stays.
    @Test func theClosestResultsNoteIsAnnouncedWhenItAppears() async {
        let (history, defaults, suite) = makeHistoryStore()
        defer { defaults.removePersistentDomain(forName: suite) }
        let spoken = Announcements()
        let note = String(localized: "search_closest_results")
        let vm = SearchViewModel(catalog: EchoSearchClient(matches: ["zakit": .approximate, "zakitt": .related,
                                                                     "zakix": .approximate]),
                                 history: history, sleep: noSleep, announce: spoken.record)

        vm.query = "zakit"
        await vm.searchTask?.value
        #expect(spoken.spoken.last == note + "\n" + Self.count(3))

        vm.query = "zakitt"                  // the note stays on screen
        await vm.searchTask?.value
        #expect(spoken.spoken.last == Self.count(3))

        vm.query = "zakir"                   // exact: the note goes
        await vm.searchTask?.value
        #expect(spoken.spoken.last == Self.count(3))

        vm.query = "zakix"                   // and comes back
        await vm.searchTask?.value
        #expect(spoken.spoken.last == note + "\n" + Self.count(3))
        #expect(spoken.spoken.count == 4)
    }
}
