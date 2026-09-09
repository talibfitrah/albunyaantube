import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Ruling F1's FIFTH hand-written client, pinned the way the other four are: against canned bodies,
/// never against a live host. **Every assertion here is about the BACKEND's
/// `GET /api/admin/youtube/search` (`YouTubeSearchController.java:26-27,51-56`) — nothing in this
/// file, and nothing in the client, addresses youtube.com.**
///
/// The shape comes off the Java, not off prose: `YouTubeSearchResponse` is
/// `record(List<SearchHit> items, String nextPageToken)` and `SearchHit` is
/// `record(youtubeId, name, url, thumbnailUrl, secondary, alreadyKnown, knownStatus, contentType)` —
/// so the wire says `name`/`secondary`/`knownStatus`/`contentType` where the app says
/// `title`/`channelTitle`/`registryState`/`type`. Android reads the identical field names
/// (`SearchHitDto.kt:8-16`).
@Suite(.perTest)
struct YouTubeSearchClientTests {

    private static let apiHost = "api.fitrah.test"
    private static let base = URL(string: "https://api.fitrah.test/")!

    private func client(_ responses: [HTTPResponse]) -> (YouTubeSearchClient, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (YouTubeSearchClient(transport: transport, baseURL: Self.base,
                                    deviceId: DeviceId(value: "dev-123")),
                transport)
    }

    /// One hit, with every field the app decodes present. Approved fixture ids only.
    private static func hit(_ youtubeId: String, contentType: String,
                            knownStatus: String? = nil) -> String {
        let status = knownStatus.map { "\"\($0)\"" } ?? "null"
        return """
        {"youtubeId":"\(youtubeId)","name":"Tafsir lesson",
         "url":"https://www.youtube.com/watch?v=\(youtubeId)",
         "thumbnailUrl":"https://img.test/t.jpg","secondary":"Mishary Alafasy",
         "alreadyKnown":\(knownStatus == nil ? "false" : "true"),"knownStatus":\(status),
         "contentType":"\(contentType)"}
        """
    }

    private static func page(_ hits: [String], nextPageToken: String? = nil) -> String {
        let token = nextPageToken.map { "\"\($0)\"" } ?? "null"
        return "{\"items\":[\(hits.joined(separator: ","))],\"nextPageToken\":\(token)}"
    }

    private static func query(_ request: HTTPRequest) -> [String: String] {
        let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
    }

    // MARK: - The shape

    /// The F1 pin. Four wire names the app renames, the registry state the backend already knows,
    /// and `nextPageToken` straight off the envelope.
    @Test func aCannedPageDecodesIntoSuggestItems() async throws {
        let (client, transport) = self.client([
            .json(200, Self.page([Self.hit("UCmMcOjsVehVlEOteyrhjI2Q", contentType: "CHANNEL"),
                                  Self.hit("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", contentType: "PLAYLIST"),
                                  Self.hit("xc7keR2piUM", contentType: "VIDEO", knownStatus: "PENDING")],
                                 nextPageToken: "tok-2"))
        ])

        let page = try await client.search(q: "tafsir", type: .all, pageToken: nil)

        #expect(transport.sent.first?.url.path() == "/api/admin/youtube/search")
        #expect(transport.sent.first?.method == "GET")
        #expect(page.items.map(\.id) == ["UCmMcOjsVehVlEOteyrhjI2Q",
                                         "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", "xc7keR2piUM"])
        #expect(page.items.map(\.type) == [.channels, .playlists, .videos])
        // The four renames, each of which a generated client would have got wrong.
        #expect(page.items.first?.title == "Tafsir lesson")            // wire `name`
        #expect(page.items.first?.channelTitle == "Mishary Alafasy")   // wire `secondary`
        #expect(page.items.first?.thumbnailUrl == "https://img.test/t.jpg")
        #expect(page.items.first?.registryState == nil)                // wire `knownStatus`, absent
        #expect(page.items.last?.registryState == "PENDING")
        #expect(page.nextPageToken == "tok-2")
    }

    /// Exactly three params, and `pageToken` is ABSENT — not empty — on the first page. `.all` is a
    /// real filter value the controller parses (`YouTubeContentType.ALL`), never an omission.
    @Test func theQueryCarriesQTypeAndPageTokenAndOmitsThePageTokenWhenNil() async throws {
        let (client, transport) = self.client([.json(200, Self.page([])), .json(200, Self.page([]))])

        _ = try await client.search(q: "tafsir", type: .all, pageToken: nil)
        #expect(Self.query(transport.sent[0]) == ["q": "tafsir", "type": "ALL"])

        _ = try await client.search(q: "tafsir", type: .channels, pageToken: "opaque/token+with=chars")
        #expect(Self.query(transport.sent[1]) ==
                ["q": "tafsir", "type": "CHANNEL", "pageToken": "opaque/token+with=chars"])
        // Opaque: the token round-trips through percent-encoding byte for byte.
        #expect(transport.sent[1].url.absoluteString.contains("pageToken=opaque/token%2Bwith%3Dchars"))
    }

    /// One row this build cannot name must not cost the page. `"ALL"` is dropped too: it is a QUERY
    /// filter value, never a hit's own type, and Task 27 puts a hit's type in the registry POST path
    /// — so a guessed type would submit to another collection.
    @Test func aRowWithAnUnrecognisedTypeIsDroppedRatherThanLosingThePage() async throws {
        let (client, _) = self.client([
            .json(200, Self.page([Self.hit("UCmMcOjsVehVlEOteyrhjI2Q", contentType: "CHANNEL"),
                                  Self.hit("xc7keR2piUM", contentType: "SHORT"),
                                  Self.hit("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", contentType: "ALL")],
                                 nextPageToken: "tok-2"))
        ])

        let page = try await client.search(q: "tafsir", type: .all, pageToken: nil)
        #expect(page.items.map(\.id) == ["UCmMcOjsVehVlEOteyrhjI2Q"])
        #expect(page.nextPageToken == "tok-2", "the page survives its bad rows")
    }

    // MARK: - The status table

    /// The endpoint is `@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`, so a 403 here is the ROLE
    /// GATE answering — a real verdict with its own string — not a network fault and not the account
    /// lifecycle envelope `AuthorizedTransport` handles.
    @Test func theRoleGateIs403AndNotANetworkFault() async throws {
        let (client, _) = self.client([.json(403, "")])
        await #expect(throws: SuggestError.forbidden) {
            try await client.search(q: "tafsir", type: .all, pageToken: nil)
        }
    }

    /// `GlobalExceptionHandler.handleYouTubeSearchRateLimited` sends the seconds in the
    /// `Retry-After` HEADER only, so the header leg is the one this endpoint actually exercises; the
    /// body leg and the 60 s floor come from the one shared table (`ApiErrorEnvelope`).
    @Test func a429CarriesItsRetryAfterSecondsFromTheBodyThenTheHeaderThenSixty() async throws {
        let (client, _) = self.client([.json(429, #"{"retryAfterSeconds":90}"#),
                                       .json(429, "", headers: ["Retry-After": "30"]),
                                       .json(429, "")])
        for expected in [90, 30, 60] {
            await #expect(throws: SuggestError.rateLimited(retryAfterSeconds: expected)) {
                try await client.search(q: "tafsir", type: .all, pageToken: nil)
            }
        }
    }

    /// Everything else is `.server`, CARRYING its status — the string is `suggest_error_server %1$@`,
    /// so the number is the message. A 200 that is not a page at all takes the same arm rather than
    /// rendering as an empty result: the empty result is a real answer this screen has a word for.
    @Test func anyOtherNonSuccessIsTheServerArmCarryingItsStatus() async throws {
        let (client, _) = self.client([.json(500, ""), .json(502, ""), .json(200, "not json at all")])
        for expected in [500, 502, 200] {
            await #expect(throws: SuggestError.server(status: expected)) {
                try await client.search(q: "tafsir", type: .all, pageToken: nil)
            }
        }
    }

    @Test func aTransportThrowIsTheNetworkArm() async throws {
        let (client, _) = self.client([.failing(URLError(.notConnectedToInternet))])
        await #expect(throws: SuggestError.network) {
            try await client.search(q: "tafsir", type: .all, pageToken: nil)
        }
    }

    // MARK: - No truncation

    /// `q` is `@NotBlank @Size(max = 200)` and `pageToken` `@Size(max = 2048)`
    /// (`YouTubeSearchController.java:52-54`). The client sends BOTH verbatim: an over-long query is
    /// real user input whose rejection the user should see as the server's 400, never as a silent
    /// trim that searched for something the user did not type. (Task 22's cursor-id validation is
    /// not the precedent — an invalid id is a *guaranteed* 400 the client can avoid spending a page
    /// on; this one is a judgement about the user's own text.)
    @Test func neitherTheQueryNorThePageTokenIsTruncated() async throws {
        let (client, transport) = self.client([.json(400, "")])
        let longQuery = String(repeating: "ص", count: 201)
        let longToken = String(repeating: "t", count: 2049)

        await #expect(throws: SuggestError.server(status: 400)) {
            try await client.search(q: longQuery, type: .videos, pageToken: longToken)
        }
        #expect(Self.query(transport.sent[0])["q"]?.count == 201)
        #expect(Self.query(transport.sent[0])["q"] == longQuery)
        #expect(Self.query(transport.sent[0])["pageToken"] == longToken)
    }

    // MARK: - Headers

    /// `X-Device-Id` from the client, `Authorization` from `AuthorizedTransport` — which is the
    /// whole point of the seam: this client mints no token of its own, and the Bearer is required
    /// because the path is an admin one. The transport under test is the REAL one, so the assertion
    /// is that the pair actually composes, not that a fake was configured to say so.
    @Test func everyRequestCarriesTheDeviceIdAndTheBearerFromTheTransport() async throws {
        let base = ScriptedTransport([.json(200, Self.page([]))])
        let authorized = AuthorizedTransport(base: base, apiHost: Self.apiHost,
                                             tokens: FixedToken(), onStatusEvent: { _ in })
        let client = YouTubeSearchClient(transport: authorized, baseURL: Self.base,
                                         deviceId: DeviceId(value: "dev-123"))

        _ = try await client.search(q: "tafsir", type: .all, pageToken: nil)

        #expect(base.sent.count == 1)
        #expect(base.sent.first?.headers["X-Device-Id"] == "dev-123")
        #expect(base.sent.first?.headers["Authorization"] == "Bearer tok-abc")
        // A GET declares no content type, and carries no body.
        #expect(base.sent.first?.headers["Content-Type"] == nil)
        #expect(base.sent.first?.body == nil)
    }

    private struct FixedToken: AuthTokenProviding {
        func idToken(forceRefresh: Bool) async -> BearerToken? {
            BearerToken(value: "tok-abc", identity: "uid-A")
        }
    }
}
