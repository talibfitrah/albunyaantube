import Foundation
import Observation

/// `SuggestUiState.kt:7-30`, minus the fields that are ViewModel bookkeeping rather than screen
/// state (Android's `Results` carries `allItems`, `searchType`, `query` and `loadingMore` inside the
/// state object; here those live on the ViewModel and only what the screen RENDERS is in the enum).
nonisolated enum SuggestUiState: Equatable {
    case idle, loading, results([SuggestItem]), empty, rateLimited(Int), error(messageKey: String)
}

/// `SuggestContentViewModel.kt`, over Task 26's `YouTubeSearchClient`.
///
/// Four behaviours, all of them Android's: a 300 ms debounce with `distinctUntilChanged` on the
/// query alone; a pasted URL scoping the BACKEND search to its own type; type chips filtering
/// client-side over the unfiltered page; and a `loadMore` that re-checks query/type/token after its
/// suspend so a superseded page is dropped rather than appended.
@MainActor @Observable final class SuggestContentViewModel {

    private let client: YouTubeSearchClient
    private let sleep: @Sendable (Duration) async throws -> Void

    private(set) var state: SuggestUiState = .idle
    /// The chip in effect. A view over `allItems` — never a second request (`:155-161`).
    private(set) var activeFilter: SuggestType = .all
    /// One page in flight at a time, and the footer spinner. On the ViewModel rather than the
    /// screen because this list has TWO triggers (the autofill and the row `.onAppear`) and the
    /// guard that makes a whole frame of appearances cost one page has to sit where both meet —
    /// `MySubmissionsViewModel`'s fix-round-1 / I2 shape.
    private(set) var isLoadingMore = false
    /// Latched by a failed page, read by `PaginationGuard`'s guard 3.
    private(set) var paginationError = false

    /// The query the CURRENT results were fetched for, verbatim — `suggest_empty_results` is
    /// `No results for "%1$@"` and has nothing else to name.
    private(set) var lastQuery = ""

    /// Bound by the screen's search field. `didSet` is the debounce: each write cancels the pending
    /// task synchronously, so three keystrokes inside one window leave one survivor.
    /// `guard query != oldValue` is `distinctUntilChanged` (`:39`).
    var query: String = "" {
        didSet {
            guard query != oldValue else { return }
            searchTask?.cancel()
            searchTask = Task { [sleep] in
                do { try await sleep(.milliseconds(300)) } catch { return }
                guard !Task.isCancelled else { return }
                await self.search()
            }
        }
    }

    /// Visible (not `private`) only so tests can `await model.searchTask?.value` — the same
    /// rationale as `ContentListViewModel.searchTask`.
    private(set) var searchTask: Task<Void, Never>?

    /// The unfiltered page(s). `state` shows `applyFilter(allItems)`.
    private var allItems: [SuggestItem] = []
    /// What the backend was ASKED for, and with what text: `loadMore` must send the same pair the
    /// token was issued against or page two answers a different question (`SuggestUiState.kt:11-13`).
    private var searchType: SuggestType = .all
    /// What actually went out as `q` — the parsed id for a URL, the raw text otherwise. Kept rather
    /// than re-derived, so `loadMore` cannot send a `q` the token was not issued against.
    private var sentQuery = ""
    private var nextPageToken: String?

    var hasMore: Bool { nextPageToken != nil }

    init(client: YouTubeSearchClient,
         sleep: @escaping @Sendable (Duration) async throws -> Void = { try await Task.sleep(for: $0) }) {
        self.client = client
        self.sleep = sleep
    }

    // MARK: - Search

    /// `:40-64`. A blank query is `.idle` with no call — `isBlank`, not `isEmpty`, so a field
    /// holding only spaces does not spend a request on the server's `@NotBlank` 400.
    private func search() async {
        let raw = query
        guard !raw.trimmingCharacters(in: .whitespacesAndNewlines).isEmpty else {
            reset()
            state = .idle
            return
        }
        let (type, text) = YouTubeURLParser.parse(raw).resolved
        // Read BEFORE the fetch, as Android does (`:45-46`): the chip the user had set, which only
        // an `ALL` search may inherit.
        let previousFilter = { if case .results = state { activeFilter } else { SuggestType.all } }()

        reset()
        state = .loading
        do {
            let page = try await client.search(q: text, type: type, pageToken: nil)
            guard !Task.isCancelled else { return }
            lastQuery = raw
            searchType = type
            sentQuery = text
            nextPageToken = Self.advanced(from: nil, to: page.nextPageToken)
            allItems = page.items
            // `:53-60`. Only an ALL search inherits the chip: a URL-resolved search already scopes
            // to one type, so a stale chip on top of it would hide the very hit the user pasted a
            // link for.
            activeFilter = (type == .all && previousFilter != .all) ? previousFilter : .all
            state = page.items.isEmpty ? .empty : .results(filtered)
        } catch {
            guard !Task.isCancelled else { return }
            lastQuery = raw
            state = Self.failureState(error)
        }
    }

    /// `mapSearchResult` (`:143-146`), plus the one arm Android does not have. Android renders a
    /// raw HTTP status for everything unmapped (`suggest_error_server` was `Server error %1$s`);
    /// this build's copy says WHAT, so the status is not in the message and a 401 — a bearer this
    /// backend refused twice, i.e. an expired sign-in — gets the Part A copy that names the actual
    /// remedy instead of a number.
    private static func failureState(_ error: SuggestError) -> SuggestUiState {
        switch error {
        case .forbidden: .error(messageKey: "suggest_error_not_allowed")
        case .rateLimited(let seconds): .rateLimited(seconds)
        case .network: .error(messageKey: "suggest_error_network")
        case .server(status: 401): .error(messageKey: "auth_error_invalid_credential")
        case .server: .error(messageKey: "suggest_error_server")
        }
    }

    /// The error arm's Retry: the same query again, bypassing the debounce (there is nothing to
    /// debounce — the user pressed a button, not a key).
    func retry() async { await search() }

    private func reset() {
        allItems = []
        nextPageToken = nil
        paginationError = false
        activeFilter = .all
        lastQuery = ""
        sentQuery = ""
        searchType = .all
    }

    // MARK: - Chips

    /// Client-side only (`:155-161`): a chip re-slices what is already loaded. It is a no-op unless
    /// there are results to slice, exactly as Android's `as? Results ?: return` is.
    func onTypeChange(_ type: SuggestType) {
        guard case .results = state else { return }
        activeFilter = type
        state = .results(filtered)
    }

    private var filtered: [SuggestItem] {
        activeFilter == .all ? allItems : allItems.filter { $0.type == activeFilter }
    }

    // MARK: - Submitted rows

    /// Fix round 1 / M1. A row whose submit LANDED is in the registry now, so it must stop offering
    /// the `+` — a second tap would 409, which is exactly the affordance RULING 28 refuses, one tap
    /// later. The screen reports the target the sheet actually sent (nil on any failure) and the row
    /// is stamped in place: no re-search, and the badge is decided by the same `SubmissionStatus`
    /// table `SuggestResultRow.badgeKey` reads for every other row.
    ///
    /// The TYPE is part of the match, not just the id: a `SubmitTarget` names a registry collection
    /// as well as an id, and stamping by id alone would mark a row this submit never touched.
    func markSubmitted(_ target: SubmitTarget) {
        guard let index = allItems.firstIndex(where: {
            $0.youtubeId == target.youtubeId && $0.submitTarget?.type == target.type
        }) else { return }
        allItems[index].registryState = SubmissionStatus.pending.rawValue
        if case .results = state { state = .results(filtered) }
    }

    // MARK: - Pagination

    /// The next page, appended. Answers whether a fetch was actually STARTED, which is what the
    /// screen's autofill commits its `PaginationGuard` attempt on.
    @discardableResult
    func loadMore() async -> Bool {
        guard let token = nextPageToken, !isLoadingMore, !paginationError, case .results = state
        else { return false }
        let (text, type) = (lastQuery, searchType)
        isLoadingMore = true
        defer { isLoadingMore = false }
        do {
            let page = try await client.search(q: sentQuery, type: type, pageToken: token)
            // `:169-175`: after the suspend, confirm this is still the same search. A page fetched
            // against a superseded query/type/token must not be appended to the one now on screen.
            guard stillCurrent(text, type, token) else { return true }
            allItems += page.items
            nextPageToken = Self.advanced(from: token, to: page.nextPageToken)
            state = .results(filtered)
        } catch {
            guard stillCurrent(text, type, token) else { return true }
            // The rows already on screen SURVIVE a failed tail — there is nothing wrong with them.
            paginationError = true
        }
        return true
    }

    private func stillCurrent(_ text: String, _ type: SuggestType, _ token: String) -> Bool {
        lastQuery == text && searchType == type && nextPageToken == token
    }

    /// Task 26 concern 1. The client sends and decodes `nextPageToken` VERBATIM — an empty string
    /// is a token to it, and so is the token it was just given back. Either would ask for the same
    /// page forever, so exhaustion is decided HERE, at the one place a token is written, before
    /// `PaginationGuard` ever reads `hasMore`.
    private static func advanced(from sent: String?, to received: String?) -> String? {
        guard let received, !received.isEmpty, received != sent else { return nil }
        return received
    }

    /// The PHONE's trigger. `PaginationGuard`'s guard 1 refuses to autofill on a compact width, so
    /// the screen's autofill alone never runs on the device most users hold — CLAUDE.md asks for the
    /// scroll listener as well. `ContentListView`'s `>=` threshold (`:271-281`): after a failed page
    /// the count is unchanged, so an `==` cell has already appeared and scrolling on does nothing.
    /// A whole frame of appearances costs ONE page, because `loadMore`'s `isLoadingMore` guard is
    /// set before its first `await`.
    func rowAppeared(at index: Int) async {
        guard case .results(let rows) = state, index >= max(0, rows.count - 5) else { return }
        await loadMore()
    }
}
