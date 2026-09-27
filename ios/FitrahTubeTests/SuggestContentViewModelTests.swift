import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftUI
import Synchronization
import Testing
@testable import FitrahTube

/// `SuggestContentViewModel.kt` + `SubmitContentBottomSheet.kt`, over `YouTubeSearchClient`
/// (Task 26) and `ApprovalsClient.submit` (Task 25). Everything canned through `ScriptedTransport`;
/// the debounce is the injected clock, never a wall-clock wait.
@Suite(.perTest)
@MainActor
struct SuggestContentViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let video = "xc7keR2piUM"
    private static let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"

    // MARK: - Fixtures

    private static func hit(_ id: String, _ type: String, knownStatus: String? = nil) -> String {
        let known = knownStatus.map { "\"\($0)\"" } ?? "null"
        return """
        {"youtubeId":"\(id)","name":"Tafsir \(id)","thumbnailUrl":null,"secondary":"Alafasy",
         "alreadyKnown":\(knownStatus == nil ? "false" : "true"),"knownStatus":\(known),
         "contentType":"\(type)"}
        """
    }

    private static func item(_ id: String, _ type: SuggestType, registryState: String? = nil) -> SuggestItem {
        SuggestItem(youtubeId: id, type: type, title: "Tafsir \(id)", thumbnailUrl: nil,
                    channelTitle: "Alafasy", registryState: registryState)
    }

    private static func page(_ hits: [(String, String)], nextPageToken: String? = nil) -> String {
        let token = nextPageToken.map { "\"\($0)\"" } ?? "null"
        return """
        {"items":[\(hits.map { hit($0.0, $0.1) }.joined(separator: ","))],"nextPageToken":\(token)}
        """
    }

    /// The debounce clock: records what was asked for and returns at once. Three keystrokes still
    /// cost ONE search because each `query` write cancels the pending task synchronously, before
    /// any task body has run — the same shape `ContentListViewModelTests` drives with `noSleep`.
    private func model(_ responses: [HTTPResponse])
    -> (SuggestContentViewModel, ScriptedTransport, SleepRecorder) {
        let transport = ScriptedTransport(responses)
        let sleeps = SleepRecorder()
        let model = SuggestContentViewModel(
            client: YouTubeSearchClient(transport: transport, baseURL: Self.base,
                                        deviceId: DeviceId(value: "dev-123")),
            sleep: { await sleeps.record($0) })
        return (model, transport, sleeps)
    }

    private func items(_ model: SuggestContentViewModel) -> [SuggestItem] {
        if case .results(let items) = model.state { items } else { [] }
    }

    private static func query(_ request: HTTPRequest) -> [String: String] {
        let components = URLComponents(url: request.url, resolvingAgainstBaseURL: false)
        return (components?.percentEncodedQueryItems ?? []).reduce(into: [:]) {
            $0[$1.name] = $1.value?.removingPercentEncoding
        }
    }

    // MARK: - The debounce

    /// 300 ms, `distinctUntilChanged` (`SuggestContentViewModel.kt:39`). Three keystrokes inside
    /// one window are ONE search, and re-typing the same string is none at all.
    @Test func threeKeystrokesCostOneSearchAtThreeHundredMilliseconds() async {
        let (model, transport, sleeps) = self.model([.json(200, Self.page([(Self.video, "VIDEO")]))])

        model.query = "ta"
        model.query = "taf"
        model.query = "tafsir"
        await model.searchTask?.value

        #expect(transport.sent.count == 1, "the first two keystrokes were superseded before they ran")
        #expect(Self.query(transport.sent[0])["q"] == "tafsir", "and the survivor is the LAST one")
        #expect(sleeps.recorded == [.milliseconds(300), .milliseconds(300), .milliseconds(300)],
                "one 300 ms window per keystroke")

        model.query = "tafsir"
        await model.searchTask?.value
        #expect(transport.sent.count == 1, "distinctUntilChanged: the same string is not a new search")
    }

    /// A blank query is `.idle` with NO call (`:41-42`) — including a whitespace-only one, which is
    /// what `isBlank` means and what `isEmpty` would miss.
    @Test func aBlankQueryReturnsToIdleWithNoCall() async {
        let (model, transport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO")]))])

        model.query = "tafsir"
        await model.searchTask?.value
        #expect(items(model).count == 1)

        model.query = "   "
        await model.searchTask?.value
        #expect(model.state == .idle)
        #expect(transport.sent.count == 1, "no request for a blank query")
    }

    // MARK: - The parser -> the wire

    /// `resolveQuery` (`:73-74`): a URL scopes the backend search to its own type and sends the ID;
    /// anything else is a plain `ALL` search for the raw text. A handle resolves to a CHANNEL
    /// search for the handle (`:105`), because a handle is not an id.
    @Test func aPastedUrlSearchesItsOwnTypeAndPlainTextSearchesAll() async {
        let (model, transport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO")])),
                                                .json(200, Self.page([(Self.playlist, "PLAYLIST")])),
                                                .json(200, Self.page([(Self.channel, "CHANNEL")])),
                                                .json(200, Self.page([(Self.channel, "CHANNEL")]))])

        for raw in ["https://youtu.be/\(Self.video)",
                    "https://www.youtube.com/playlist?list=\(Self.playlist)",
                    "https://www.youtube.com/@fitrahtube",
                    "tafsir lessons"] {
            model.query = raw
            await model.searchTask?.value
        }

        #expect(transport.sent.map { Self.query($0)["type"] } == ["VIDEO", "PLAYLIST", "CHANNEL", "ALL"])
        #expect(transport.sent.map { Self.query($0)["q"] }
                == [Self.video, Self.playlist, "@fitrahtube", "tafsir lessons"])
    }

    // MARK: - The chips

    /// `onTypeChange` (`:155-161`) filters what is ALREADY loaded — no second request. The chip is
    /// a view over `allItems`, so switching back to All restores the whole page.
    @Test func theTypeChipsFilterClientSideWithNoSecondRequest() async {
        let (model, transport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO"),
                                                                      (Self.channel, "CHANNEL")]))])
        model.query = "tafsir"
        await model.searchTask?.value
        #expect(items(model).count == 2)

        model.onTypeChange(.channels)
        #expect(items(model).map(\.youtubeId) == [Self.channel])
        #expect(model.activeFilter == .channels)

        model.onTypeChange(.all)
        #expect(items(model).count == 2)
        #expect(transport.sent.count == 1, "a chip never asks the backend")
    }

    /// `:50-60`. The filter carries into a NEW search only when the backend was asked for `ALL`.
    /// A URL-resolved search already scopes to one type, so applying a stale chip on top of it
    /// would hide every result the user just pasted a link for.
    @Test func thePreviousFilterCarriesForwardOnlyWhenTheBackendWasAskedForAll() async {
        let (model, transport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO"),
                                                                      (Self.channel, "CHANNEL")])),
                                                .json(200, Self.page([(Self.video, "VIDEO"),
                                                                      (Self.channel, "CHANNEL")])),
                                                .json(200, Self.page([(Self.video, "VIDEO")]))])
        model.query = "tafsir"
        await model.searchTask?.value
        model.onTypeChange(.channels)

        model.query = "tafsir lessons"
        await model.searchTask?.value
        #expect(model.activeFilter == .channels, "a text search keeps the chip the user set")
        #expect(items(model).map(\.youtubeId) == [Self.channel])

        model.query = "https://youtu.be/\(Self.video)"
        await model.searchTask?.value
        #expect(model.activeFilter == .all, "a URL-resolved search drops the chip rather than hiding its own hit")
        #expect(items(model).map(\.youtubeId) == [Self.video])
        #expect(transport.sent.count == 3)
    }

    // MARK: - Pagination

    /// `loadMore` re-checks query, type and token AFTER the suspend (`:169-175`): a page fetched
    /// against a superseded search must not be appended to the one now on screen.
    @Test func loadMoreDropsAStaleGenerationAfterTheSuspend() async {
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.page([(Self.video, "VIDEO")], nextPageToken: "p2")),
                                           .json(200, Self.page([("stale-1", "VIDEO")])),
                                           .json(200, Self.page([(Self.channel, "CHANNEL")]))],
                                          park: { index in if index == 2 { await gate.block() } })
        let model = SuggestContentViewModel(
            client: YouTubeSearchClient(transport: transport, baseURL: Self.base,
                                        deviceId: DeviceId(value: "dev-123")),
            sleep: noSleep)

        model.query = "tafsir"
        await model.searchTask?.value
        let paging = Task { await model.loadMore() }
        await gate.waitUntilBlocked()

        // A new search lands while page two is still in flight.
        model.query = "other"
        await model.searchTask?.value
        await gate.release()
        _ = await paging.value

        #expect(items(model).map(\.youtubeId) == [Self.channel], "the stale page is dropped, not appended")
    }

    /// Task 26 concern 1: the client sends and decodes `nextPageToken` VERBATIM, so an empty or
    /// repeated token would ask for the same page forever. End-of-list is decided HERE, before
    /// `PaginationGuard` ever sees `hasMore`.
    @Test func anEmptyOrUnchangedNextPageTokenIsTheEndOfTheList() async {
        let (empty, emptyTransport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO")],
                                                                          nextPageToken: ""))])
        empty.query = "tafsir"
        await empty.searchTask?.value
        #expect(!empty.hasMore, "an empty token is exhaustion, not a first page")
        #expect(await empty.loadMore() == false)
        #expect(emptyTransport.sent.count == 1)

        let (repeated, repeatedTransport, _) = self.model([
            .json(200, Self.page([(Self.video, "VIDEO")], nextPageToken: "p2")),
            .json(200, Self.page([(Self.channel, "CHANNEL")], nextPageToken: "p2"))])
        repeated.query = "tafsir"
        await repeated.searchTask?.value
        #expect(await repeated.loadMore())
        #expect(!repeated.hasMore, "the same token twice is exhaustion")
        #expect(await repeated.loadMore() == false)
        #expect(repeatedTransport.sent.count == 2)
    }

    /// CLAUDE.md's pagination rule, phone half (Task 25 fix round / I2): `PaginationGuard`'s guard 1
    /// refuses to autofill on a compact width, so without the row trigger page two is unreachable on
    /// a phone. A whole frame of `.onAppear`s across the tail costs exactly ONE page.
    @Test func aRowNearTheEndPagesOnAPhoneAndAWholeFrameOfThemCostsOnePage() async {
        let first = (0..<6).map { ("id-\($0)", "VIDEO") }
        let (model, transport, _) = self.model([.json(200, Self.page(first, nextPageToken: "p2")),
                                                .json(200, Self.page([(Self.channel, "CHANNEL")]))])
        model.query = "tafsir"
        await model.searchTask?.value

        await model.rowAppeared(at: 0)
        #expect(transport.sent.count == 1, "the head of the list asks for nothing")

        // The last five, all in one frame — spawned before this test yields, exactly as SwiftUI
        // hands a screenful of `.onAppear`s to the MainActor. No `Gate` and no park: whichever task
        // wins the actor sets `isLoadingMore` before its first `await`, so the other four bounce
        // whatever the order (`MySubmissionsViewModelTests`' own I2 row, same shape and same reason
        // — a parked variant HANGS for the full 60 s limit the day the threshold regresses).
        let frame = (1...5).map { index in Task { await model.rowAppeared(at: index) } }
        for task in frame { await task.value }

        #expect(transport.sent.count == 2, "five appearances in one frame are one page")
        #expect(items(model).count == 7)
    }

    /// A failed page keeps what is on screen and latches `paginationError`, which is
    /// `PaginationGuard`'s guard 3 — without it a page that fits the viewport re-fires the same
    /// failing request until the attempt cap bites.
    @Test func aFailedPageKeepsTheRowsAndStopsTheAutofill() async {
        let (model, _, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO")], nextPageToken: "p2")),
                                        .json(503, "")])
        model.query = "tafsir"
        await model.searchTask?.value
        #expect(await model.loadMore())

        #expect(items(model).map(\.youtubeId) == [Self.video], "the rows survive the failed tail")
        #expect(model.paginationError)
        #expect(await model.loadMore() == false, "and nothing retries it on its own")
    }

    // MARK: - The state table

    /// `mapSearchResult` (`:143-146`), plus the two arms Android does not have: a 401 says the
    /// sign-in expired instead of printing a status, and the re-authored `suggest_error_server`
    /// says WHAT rather than WHY for everything else.
    @Test func everyFailureArmMapsToItsOwnMessage() async {
        let cases: [(HTTPResponse, SuggestUiState)] = [
            (.json(403, ""), .error(messageKey: "suggest_error_not_allowed")),
            (.json(429, "{\"retryAfterSeconds\":90}"), .rateLimited(90)),
            (.failing(URLError(.notConnectedToInternet)), .error(messageKey: "suggest_error_network")),
            (.json(401, ""), .error(messageKey: "auth_error_invalid_credential")),
            (.json(502, ""), .error(messageKey: "suggest_error_server")),
            // Task 26 concern 3: a malformed 200 carries status 200, which used to render
            // "Server error 200". WHAT-only copy makes that unreachable by construction.
            (.json(200, "{\"nope\":true"), .error(messageKey: "suggest_error_server"))
        ]
        for (response, expected) in cases {
            let (model, _, _) = self.model([response])
            model.query = "tafsir"
            await model.searchTask?.value
            #expect(model.state == expected)
        }
    }

    /// An empty page is its own arm and remembers the query it asked for — `suggest_empty_results`
    /// is `No results for "%1$@"` and has nothing else to name.
    @Test func anEmptyPageIsItsOwnArmAndRemembersTheQueryItAskedFor() async {
        let (model, _, _) = self.model([.json(200, Self.page([]))])
        model.query = "  tafsir lessons  "
        await model.searchTask?.value

        #expect(model.state == .empty)
        #expect(model.lastQuery == "  tafsir lessons  ", "what the user typed, verbatim")
    }

    /// Ruling C13, the same walk `MainShellRoutingTests` does: every arm resolves to a real view,
    /// the error arm to a real `ErrorStateView` with a retry — and the rate-limited arm to a state
    /// view with NO retry button, because a Retry into a rate limit is an invitation to hammer it.
    ///
    /// Fix round 1 / M2: `.results([])` is REACHABLE — the backend answers videos only and the user
    /// taps the Playlists chip — and it used to send an empty `LazyVStack` to the screen, i.e. the
    /// chips row above a blank pane with no message. An empty slice is an empty RESULT, so it says
    /// what the `.empty` arm says.
    @Test func everyStateArmRendersARealViewAndOnlyTheErrorArmOffersRetry() {
        let screen = SuggestContentScreen()
        #expect(leafTypeName(of: screen.stateView(.idle)) == "EmptyStateView")
        #expect(leafTypeName(of: screen.stateView(.loading)) == "SkeletonListView")
        #expect(leafTypeName(of: screen.stateView(.empty)) == "EmptyStateView")
        #expect(leafTypeName(of: screen.stateView(.rateLimited(90))) == "EmptyStateView")
        #expect(leafTypeName(of: screen.stateView(.error(messageKey: "suggest_error_network"))) == "ErrorStateView")
        #expect(leafTypeName(of: screen.stateView(.results([]))) == "EmptyStateView",
                "a chip that filters everything out is not a blank pane")
        #expect(leafTypeName(of: screen.stateView(.results([Self.item(Self.video, .videos)])))
                    .hasPrefix("LazyVStack"))
    }

    /// `SuggestResultsAdapter.kt:30-35` over Task 25's `SubmissionStatus` (Task 26 concern 4 —
    /// no fifth string table). nil is the WHOLE submittability rule: a row the registry already
    /// knows carries a badge instead of a Submit button, so no affordance is offered that would 409.
    @Test func aKnownRegistryStateBecomesABadgeAndOnlyAnUnknownRowOffersSubmit() {
        #expect(SuggestResultRow.badgeKey(nil) == nil, "not in the registry — submittable")
        #expect(SuggestResultRow.badgeKey("APPROVED") == "suggest_already_in_registry")
        #expect(SuggestResultRow.badgeKey("PENDING") == "suggest_already_pending")
        #expect(SuggestResultRow.badgeKey("REQUEST_CHANGES") == "suggest_already_pending",
                "back in its submitter's queue is still 'already there' to everybody else")
        #expect(SuggestResultRow.badgeKey("REJECTED") == "suggest_already_rejected")
        #expect(SuggestResultRow.badgeKey("SOMETHING_NEW") == "suggest_already_pending",
                "a value this build cannot name is still IN the registry — never a Submit button")
    }

    /// Fix round 1 / M1. A row that was just submitted IS in the registry, so it must stop offering
    /// the `+` that would 409 on a second tap — RULING 28's affordance rule, one tap later. The
    /// screen reports the target the sheet actually sent and the row is stamped in place: no
    /// re-search, and the badge is decided by the same `SubmissionStatus` table every other row uses.
    @Test func aSubmittedRowSwapsItsPlusForThePendingBadgeWithoutAResearch() async {
        let (model, transport, _) = self.model([.json(200, Self.page([(Self.video, "VIDEO"),
                                                                      (Self.channel, "CHANNEL")]))])
        model.query = "tafsir"
        await model.searchTask?.value
        #expect(items(model).allSatisfy { $0.registryState == nil }, "both rows offer Submit")

        // A `SubmitTarget` names a registry COLLECTION as well as an id, so a target whose type
        // this row does not carry is not this row — stamping by id alone would mark a row the
        // submit never touched.
        model.markSubmitted(SubmitTarget(type: .playlists, youtubeId: Self.channel))
        #expect(items(model).allSatisfy { $0.registryState == nil },
                "another collection's target leaves every row alone, id match or not")

        model.markSubmitted(SubmitTarget(type: .videos, youtubeId: Self.video))

        let rows = items(model)
        #expect(rows.first?.registryState == SubmissionStatus.pending.rawValue)
        #expect(SuggestResultRow.badgeKey(rows.first?.registryState) == "suggest_already_pending",
                "the submitted row carries the badge instead of the button that would 409")
        #expect(rows.last?.registryState == nil, "and nothing else on the page moves")
        #expect(transport.sent.count == 1, "stamped in place — never a second search")
    }

    // MARK: - `SubmitContentSheet`

    private func sheet(_ responses: [HTTPResponse], hit: SuggestItem? = nil)
    -> (SubmitContentModel, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        let model = SubmitContentModel(client: ApprovalsClient(transport: transport, baseURL: Self.base,
                                                              deviceId: DeviceId(value: "dev-123")),
                                       hit: hit, locale: Locale(identifier: "en"))
        return (model, transport)
    }

    /// The URL field drives the type. `SubmitContentBottomSheet.kt:56-69`: every keystroke re-parses
    /// and the detected line is the parse's own answer.
    @Test func aParsedUrlPreselectsTheTypeAndShowsItsDetectedLine() {
        let (model, _) = sheet([])
        #expect(model.detectionKey == nil, "nothing is claimed before anything is typed")

        model.url = "https://youtu.be/\(Self.video)"
        #expect(model.detectionKey == "submit_content_detected_video")
        #expect(model.target?.type == .videos)
        #expect(model.target?.youtubeId == Self.video)

        model.url = "https://www.youtube.com/playlist?list=\(Self.playlist)"
        #expect(model.detectionKey == "submit_content_detected_playlist")
        #expect(model.target?.type == .playlists)

        model.url = "https://m.youtube.com/channel/\(Self.channel)"
        #expect(model.detectionKey == "submit_content_detected_channel")
        #expect(model.target?.type == .channels)
    }

    /// An unparseable URL — and a handle, which Android's own sheet also refuses (`:168`, "only
    /// UCxxx channel IDs") because a handle is not an id the registry can key on.
    @Test func anUnparseableUrlSaysSoAndRefusesToSubmit() {
        let (model, transport) = sheet([])
        model.categoryId = "cat-1"

        for raw in ["not a url", "https://notyoutube.com/watch?v=\(Self.video)",
                    "https://www.youtube.com/@fitrahtube"] {
            model.url = raw
            #expect(model.detectionKey == "submit_content_invalid_url", "\(raw)")
            #expect(model.target == nil)
            #expect(!model.canSubmit)
        }
        #expect(transport.sent.isEmpty)
    }

    /// A category is required, exactly as Android's sheet requires one (`:100-101`) — an approved
    /// row with no `categoryIds` is invisible to every public category filter.
    @Test func submitIsDeadUntilBothAUrlAndACategoryAreChosen() {
        let (model, _) = sheet([])
        #expect(!model.canSubmit)
        model.url = "https://youtu.be/\(Self.video)"
        #expect(!model.canSubmit, "a target with no category is not submittable")
        model.categoryId = "cat-1"
        #expect(model.canSubmit)
    }

    /// The dispatcher's ruling: EVERY suggestion enters the approval flow, whatever the caller's
    /// role. `RegistryController.normalizeStatusAndApprovedBy:133-136` defaults an ADMIN's own POST
    /// to `APPROVED` with `approvedBy = self`, so a silent body would publish an uncategorised row
    /// with no review at all.
    @Test func aSuccessfulSubmitSaysSoAndSendsPendingWithItsCategory() async throws {
        let (model, transport) = sheet([.json(201, "")])
        model.url = "https://youtu.be/\(Self.video)"
        model.categoryId = "cat-1"
        model.note = "Great tafsir series"

        let (message, submitted) = await model.submit()

        #expect(message == String(localized: "submit_content_success"))
        #expect(submitted == SubmitTarget(type: .videos, youtubeId: Self.video),
                "the caller is told WHICH row landed, so the screen can stamp it (fix round 1 / M1)")
        let request = try #require(transport.sent.first)
        #expect(request.method == "POST")
        #expect(request.url.path() == "/api/admin/registry/videos", "the HIT's own type is the path")
        // NOT a nested `#require` — that is a recursive macro expansion (Task 25's step 1).
        let postBody = try #require(request.body)
        let body = try #require(try JSONSerialization.jsonObject(with: postBody) as? [String: Any])
        #expect(body["status"] as? String == "PENDING")
        #expect(body["categoryIds"] as? [String] == ["cat-1"])
        #expect(body["youtubeId"] as? String == Self.video)
        #expect(body["submitterNote"] as? String == "Great tafsir series")
    }

    /// The two answers with their own copy. Everything else is `submit_content_error_generic`.
    @Test func aConflictARateLimitAndAnythingElseEachSayTheirOwnThing() async {
        for (response, expected) in [(HTTPResponse.json(409, ""), String(localized: "submit_content_conflict")),
                                     (.json(503, ""), String(localized: "submit_content_error_generic"))] {
            let (model, _) = sheet([response])
            model.url = "https://youtu.be/\(Self.video)"
            model.categoryId = "cat-1"
            let (message, submitted) = await model.submit()
            #expect(message == expected)
            #expect(submitted == nil, "a failed submit stamps nothing")
        }

        // 429: Android renders the wait in whole hours, rounded UP (`:120`).
        let (limited, _) = sheet([.json(429, "{\"retryAfterSeconds\":3601}")])
        limited.url = "https://youtu.be/\(Self.video)"
        limited.categoryId = "cat-1"
        let message = await limited.submit().message
        #expect(message.hasPrefix("Daily submission limit reached"))
        #expect(message.contains("2"), "3601 s rounds up to two hours, never down to one")
    }

    /// Opened from a search result there is no URL to parse and none to render: the hit already
    /// carries its own type and id (owner directive — nothing in this app renders a YouTube URL).
    @Test func aSearchHitSubmitsItsOwnTypeWithNoUrlToParse() async throws {
        let hit = SuggestItem(youtubeId: Self.channel, type: .channels, title: "Alafasy",
                              thumbnailUrl: nil, channelTitle: nil, registryState: nil)
        let (model, transport) = sheet([.json(201, "")], hit: hit)

        #expect(model.detectionKey == "submit_content_detected_channel")
        #expect(model.target?.youtubeId == Self.channel)
        #expect(model.url.isEmpty, "the sheet never puts a YouTube URL on screen")

        model.categoryId = "cat-1"
        #expect(await model.submit().message == String(localized: "submit_content_success"))
        let request = try #require(transport.sent.first)
        #expect(request.url.path() == "/api/admin/registry/channels")
    }

    /// Fix round 1 / I2. `/categories` failing — or the app being launched straight into
    /// Me -> Suggest before any catalog screen ran — left the picker a `Menu` with zero rows, so
    /// `canSubmit` could never become true and the Submit button was permanently disabled with
    /// nothing on screen saying why: RULING 28 inverted. An empty list is a FAILURE the sheet
    /// states, with the retry that re-fetches it.
    @Test func anEmptyCategoryListSaysSoAndOffersARetryInsteadOfADeadPicker() {
        let (model, _) = sheet([])
        let view = SubmitContentSheet(onFinish: { _, _ in })

        #expect(leafTypeName(of: view.categoryField(model, [])) == "ErrorStateView",
                "an empty picker is a failure with a retry, never a silent dead end")
        model.url = "https://youtu.be/\(Self.video)"
        #expect(!model.canSubmit, "and Submit stays dead — now with the reason visible")

        let category = Category(id: "cat-1", name: "Tafsir", slug: "tafsir", parentId: nil)
        #expect(leafTypeName(of: view.categoryField(model, [category])).hasPrefix("LabelledField"),
                "a retry that succeeds puts the picker back")
        model.categoryId = category.id
        #expect(model.canSubmit)
    }
}
