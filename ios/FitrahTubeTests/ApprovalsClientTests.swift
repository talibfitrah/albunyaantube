import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Ruling F1 again: `ApprovalsClient` is hand-written because the generated client cannot express
/// this endpoint's two shape traps, so this file IS those traps, pinned against canned bodies.
///
///  1. the page's array key is `data`, not `items` (`CursorPageDto.java:23`, `ApprovalDtos.kt:30-32`);
///  2. `submittedAt` is a Firestore `Timestamp` OBJECT (`PendingApprovalDto.java:41` declares
///     `com.google.cloud.Timestamp`, which Jackson serialises as the bean `{seconds,nanos}`).
@Suite(.perTest)
struct ApprovalsClientTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private func client(_ responses: [HTTPResponse]) -> (ApprovalsClient, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (ApprovalsClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-123")),
                transport)
    }

    /// One row, with every field the app decodes present.
    private static func row(id: String, type: String = "VIDEO", status: String = "PENDING",
                            submittedAt: String = #"{"seconds":1756800000,"nanos":0}"#) -> String {
        """
        {"id":"\(id)","type":"\(type)","entityId":"xc7keR2piUM","title":"Lecture","category":"Quran",
         "submittedAt":\(submittedAt),"submittedBy":"u1","status":"\(status)",
         "submitterNote":"why","thumbnailUrl":"https://img.test/t.jpg","youtubeId":"xc7keR2piUM"}
        """
    }

    private static func page(_ rows: [String], key: String = "data", nextCursor: String? = nil) -> String {
        let cursor = nextCursor.map { "\"\($0)\"" } ?? "null"
        return """
        {"\(key)":[\(rows.joined(separator: ","))],
         "pageInfo":{"nextCursor":\(cursor),"hasNext":\(nextCursor == nil ? "false" : "true")}}
        """
    }

    // MARK: - Trap 1: the array key is `data`

    /// The whole reason this client is hand-written. A `data`-keyed page decodes its rows; the same
    /// page keyed `items` decodes to EMPTY rather than throwing — so if the backend ever renames the
    /// key, the screen goes blank and THIS test says which shape shipped, instead of a decode error
    /// that names neither.
    @Test func theArrayKeyIsDataAndAnItemsKeyedPageDecodesToNothing() async throws {
        let (client, _) = self.client([.json(200, Self.page([Self.row(id: "s1")])),
                                       .json(200, Self.page([Self.row(id: "s1")], key: "items"))])

        let real = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(real.items.map(\.id) == ["s1"])
        #expect(real.items.first?.title == "Lecture")
        #expect(real.items.first?.thumbnailUrl == "https://img.test/t.jpg")
        #expect(real.items.first?.submitterNote == "why")
        #expect(real.items.first?.type == .videos)

        let wrongKey = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(wrongKey.items.isEmpty, "an `items`-keyed page is the shape trap, not an error")
    }

    @Test func nextCursorComesFromPageInfo() async throws {
        let (client, _) = self.client([.json(200, Self.page([Self.row(id: "s1")], nextCursor: "cur-2")),
                                       .json(200, Self.page([Self.row(id: "s2")]))])
        #expect(try await client.mySubmissions(status: nil, cursor: nil, limit: 50).nextCursor == "cur-2")
        #expect(try await client.mySubmissions(status: nil, cursor: nil, limit: 50).nextCursor == nil)
    }

    // MARK: - Trap 2: the Firestore Timestamp

    /// Three shapes, one field. The OBJECT is what the backend actually sends; the plain number and
    /// the null are Android's own tolerance (`ApprovalDtos.kt:48-77`), mirrored so the two clients
    /// cannot read the same hypothetical wire value differently.
    ///
    /// A plain number is EPOCH MILLISECONDS, exactly as Android reads it — its object branch
    /// returns `seconds * 1000 + nanos / 1_000_000` and the row formats that value as millis. So
    /// `1756800000` as an object is 2025-09-02 and the SAME digits as a bare number are
    /// 1970-01-21: the contrast is the convention, and guessing by magnitude instead would be a
    /// heuristic neither client could agree on.
    @Test func submittedAtDecodesFromTheTimestampObjectAPlainNumberOrNull() async throws {
        let (client, _) = self.client([
            .json(200, Self.page([Self.row(id: "obj", submittedAt: #"{"seconds":1756800000,"nanos":500000000}"#)])),
            .json(200, Self.page([Self.row(id: "num", submittedAt: "1756800000")])),
            .json(200, Self.page([Self.row(id: "nil", submittedAt: "null")])),
            .json(200, Self.page([Self.row(id: "junk", submittedAt: #""not-a-time""#)])),
        ])

        let object = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(object.items.first?.submittedAt == Date(timeIntervalSince1970: 1_756_800_000.5))

        let number = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(number.items.first?.submittedAt == Date(timeIntervalSince1970: 1_756_800))

        let missing = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(missing.items.count == 1)
        #expect(missing.items.first?.submittedAt == nil)

        // Android skips a value it cannot read rather than failing the page; so does this.
        let junk = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(junk.items.first?.submittedAt == nil)
        #expect(junk.items.count == 1, "one unreadable field must not cost the whole page")
    }

    // MARK: - Status

    /// FOUR values, not the OpenAPI query enum's three (`api-specification.yaml:1929-1933` lists
    /// PENDING/APPROVED/REJECTED; `PendingApprovalDto` carries REQUEST_CHANGES too). An unknown one
    /// reads as `.pending` — the safe arm: a row whose status this build cannot name is still
    /// awaiting something, and `.pending` is also the only arm that keeps the row manageable, which
    /// the server then adjudicates with a 409 if it disagrees.
    @Test func theFourKnownStatusesRoundTripAndAnUnknownOneReadsAsPending() async throws {
        let rows = ["PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES", "SOME_LATER_STATUS"]
            .enumerated().map { Self.row(id: "s\($0.offset)", status: $0.element) }
        let (client, _) = self.client([.json(200, Self.page(rows))])

        let page = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(page.items.map(\.status) == [.pending, .approved, .rejected, .requestChanges, .pending])
        #expect(SubmissionStatus.allCases.map(\.rawValue)
            == ["PENDING", "APPROVED", "REJECTED", "REQUEST_CHANGES"])
        #expect(SubmissionStatus.fromWire(nil) == .pending)
        // `RegistryController.SUBMITTER_OWNED_STATUSES` — the only two the submitter may still edit.
        #expect(SubmissionStatus.allCases.filter(\.isManageable) == [.pending, .requestChanges])
    }

    /// A row whose `type` this build cannot name is DROPPED, not guessed at: every submitter action
    /// puts that type in the PATH (`api/admin/registry/{type}/{id}/submission`), so rendering the
    /// row under a guessed type offers a Delete that would address another collection's document.
    @Test func aRowWithAnUnrecognisedTypeIsDroppedRatherThanGuessedAt() async throws {
        let (client, _) = self.client([.json(200, Self.page([Self.row(id: "s1", type: "CHANNEL"),
                                                             Self.row(id: "s2", type: "SHORT"),
                                                             Self.row(id: "s3", type: "PLAYLIST")]))])
        let page = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        #expect(page.items.map(\.id) == ["s1", "s3"])
        #expect(page.items.map(\.type) == [.channels, .playlists])
    }

    // MARK: - The admin's review note (fix round 1 / I3)

    /// `reviewNotes` is the ADMIN's note back to the submitter, and the brief's field list omitted
    /// it — so a "Changes requested" row said which VERDICT it got and never which changes.
    /// It is on the wire (`PendingApprovalDto.java:84`, populated by
    /// `ApprovalService.enrichWithStatusFields:847-853`) and Android renders it for exactly one
    /// status (`MySubmissionAdapter.kt:107`).
    ///
    /// Two halves, both pinned: the DECODE (the field arrives on every row that has one) and the
    /// GATE (`status == REQUEST_CHANGES && !reviewNotes.isNullOrBlank()`). The gate matters on the
    /// approved row: it still carries the note that bounced it once, and rendering that under a
    /// green "Approved" pill would read as a fresh objection.
    @Test func theAdminsReviewNoteDecodesAndOnlyShowsOnARequestChangesRow() async throws {
        func row(_ id: String, _ status: String, _ notes: String) -> String {
            """
            {"id":"\(id)","type":"VIDEO","title":"Lecture","status":"\(status)",
             "submitterNote":"why","reviewNotes":\(notes)}
            """
        }
        let (client, _) = self.client([.json(200, Self.page([
            row("s1", "REQUEST_CHANGES", #""Please add Arabic subtitles before resubmitting.""#),
            row("s2", "APPROVED", #""an earlier bounce""#),
            row("s3", "REQUEST_CHANGES", #""   ""#),
            row("s4", "REQUEST_CHANGES", "null")
        ]))])

        let page = try await client.mySubmissions(status: nil, cursor: nil, limit: 100)

        #expect(page.items.map(\.reviewNotes)
            == ["Please add Arabic subtitles before resubmitting.", "an earlier bounce", "   ", nil])
        #expect(page.items.map(\.reviewNoteToShow)
            == ["Please add Arabic subtitles before resubmitting.", nil, nil, nil])
    }

    // MARK: - Request shapes

    @Test func theListQueryCarriesStatusCursorAndLimit() async throws {
        let (client, transport) = self.client([.json(200, Self.page([])), .json(200, Self.page([]))])

        _ = try await client.mySubmissions(status: "PENDING", cursor: "cur-2", limit: 25)
        let withAll = try #require(transport.sent.first?.url)
        #expect(withAll.path() == "/api/admin/approvals/my-submissions")
        #expect(withAll.query() == "status=PENDING&cursor=cur-2&limit=25")

        // Android never paginates it and asks for every status (`MySubmissionsRepository.kt:26`);
        // nil means "omit", never "send an empty value the backend would 400 on".
        _ = try await client.mySubmissions(status: nil, cursor: nil, limit: 100)
        #expect(transport.sent.last?.url.query() == "limit=100")
    }

    @Test func updateNotePatchesTheSubmitterNotePathForTheRowsType() async throws {
        let (client, transport) = self.client([.json(204, ""), .json(204, "")])

        try await client.updateNote(type: .videos, id: "abc", note: "please review")
        let patch = try #require(transport.sent.first)
        #expect(patch.method == "PATCH")
        #expect(patch.url.path() == "/api/admin/registry/videos/abc/submitter-note")
        let patchBody = try #require(patch.body)
        let body = try #require(try JSONSerialization.jsonObject(with: patchBody) as? [String: String])
        #expect(body == ["submitterNote": "please review"])

        try await client.updateNote(type: .channels, id: "xyz", note: "")
        #expect(transport.sent.last?.url.path() == "/api/admin/registry/channels/xyz/submitter-note")
    }

    @Test func deleteAddressesTheSubmissionLeafAndSubmitPostsToTheTypesCollection() async throws {
        let (client, transport) = self.client([.json(204, ""), .json(201, "{}")])

        try await client.deleteSubmission(type: .playlists, id: "PL-1")
        #expect(transport.sent.first?.method == "DELETE")
        #expect(transport.sent.first?.url.path() == "/api/admin/registry/playlists/PL-1/submission")

        try await client.submit(type: .channels, youtubeId: "UCmMcOjsVehVlEOteyrhjI2Q", note: "good")
        let post = try #require(transport.sent.last)
        #expect(post.method == "POST")
        #expect(post.url.path() == "/api/admin/registry/channels")
        let postBody = try #require(post.body)
        let body = try #require(try JSONSerialization.jsonObject(with: postBody) as? [String: String])
        #expect(body == ["youtubeId": "UCmMcOjsVehVlEOteyrhjI2Q", "submitterNote": "good"])
    }

    /// `X-Device-Id` on every request, exactly as `AccountClient`/`SyncClient` send it. The BEARER
    /// is not this client's to add — `AuthorizedTransport` is the transport every one of these is
    /// constructed over (`AppContainer.approvals`) and `AuthorizedTransportTests` owns that pin;
    /// a client that minted its own token would be the second token source ruling F12 forbids.
    @Test func everyRequestCarriesTheDeviceIdAndNothingMintsItsOwnToken() async throws {
        let (client, transport) = self.client([.json(200, Self.page([])), .json(204, ""), .json(204, "")])

        _ = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        try await client.updateNote(type: .videos, id: "a", note: "n")
        try await client.deleteSubmission(type: .videos, id: "a")

        #expect(transport.sent.count == 3)
        #expect(transport.sent.allSatisfy { $0.headers["X-Device-Id"] == "dev-123" })
        #expect(transport.sent.allSatisfy { $0.headers["Authorization"] == nil })
        // A body-carrying request declares its type; a GET/DELETE does not.
        #expect(transport.sent.map { $0.headers["Content-Type"] } == [nil, "application/json", nil])
    }

    // MARK: - Status table

    /// 409 is the ONE status the screen branches on: `RegistryController` answers it when the row
    /// left `{PENDING, REQUEST_CHANGES}` between the sheet opening and the write
    /// (`:416-418,455-457`). 429 carries its seconds the way Task 7's rate-limit shape does.
    @Test func aConflictIsCarriedAndA429CarriesItsRetryAfterSeconds() async throws {
        let (client, _) = self.client([
            .json(409, ""),
            .json(429, #"{"retryAfterSeconds":90}"#),
            .json(429, "", headers: ["Retry-After": "30"]),
            .json(429, ""),
            .json(403, ""),
            .failing(URLError(.notConnectedToInternet)),
        ])

        await #expect(throws: AccountError.conflict) {
            try await client.deleteSubmission(type: .videos, id: "a")
        }
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 90)) {
            try await client.submit(type: .videos, youtubeId: "xc7keR2piUM", note: nil)
        }
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 30)) {
            try await client.submit(type: .videos, youtubeId: "xc7keR2piUM", note: nil)
        }
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 60)) {
            try await client.submit(type: .videos, youtubeId: "xc7keR2piUM", note: nil)
        }
        // "Not your submission" — nothing the user can act on, so it is not its own case.
        await #expect(throws: AccountError.unknown(status: 403)) {
            try await client.updateNote(type: .videos, id: "a", note: "n")
        }
        await #expect(throws: AccountError.network) {
            _ = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        }
    }

    /// A 200 whose body is not a page at all is a failure, not an empty list — an empty list is a
    /// real answer this screen renders as its empty state, and the two must not look alike.
    @Test func aMalformedPageBodyFailsRatherThanRenderingAsEmpty() async throws {
        let (client, _) = self.client([.json(200, "<html>gateway</html>")])
        await #expect(throws: AccountError.unknown(status: 200)) {
            _ = try await client.mySubmissions(status: nil, cursor: nil, limit: 50)
        }
    }
}
