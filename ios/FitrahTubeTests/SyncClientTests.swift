import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Phase 4 Task 22, ruling F1's shape pin: `SyncClient` is hand-written over the shared
/// `HTTPTransport`, so the wire shape has no generated schema behind it and THIS file is the
/// contract. What it holds: the six query names (`subs`, never `subscriptions`), the id validator
/// that mirrors `SyncController.isValidCursorId` so an id that is a guaranteed 400 never leaves the
/// device, the PUT/DELETE paths (which DO spell `subscriptions`), the archive echo, and the raw
/// status `SyncDecisions.push` classifies.
@Suite(.perTest)
struct SyncClientTests {

    // MARK: - Fixtures (approved ids only)

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let deviceId = "dev-123"
    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
    private static let videoId = "xc7keR2piUM"

    private static let emptyPull = """
    {"subscriptions":{"items":[]},"playlists":{"items":[]},"favorites":{"items":[]}}
    """

    private func client(_ responses: [HTTPResponse]) -> (SyncClient, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (SyncClient(transport: transport, baseURL: Self.base,
                           deviceId: DeviceId(value: Self.deviceId)), transport)
    }

    /// The query as a dictionary, so an assertion names the parameter rather than a string offset.
    private static func query(_ request: HTTPRequest?) throws -> [String: String] {
        let url = try #require(request?.url)
        let components = try #require(URLComponents(url: url, resolvingAgainstBaseURL: false))
        return Dictionary(uniqueKeysWithValues: (components.queryItems ?? []).map { ($0.name, $0.value ?? "") })
    }

    // MARK: - GET /api/account/sync — the query shape

    /// `SyncController.java:41-64` takes `subs`/`subs_id`, NOT `subscriptions`/`subscriptions_id`.
    /// Spelling the rawValue here is a silent no-op sync: the server defaults the missing `subs` to
    /// 0 and re-sends every subscription on every pull, forever.
    @Test func theGetCarriesTheSixQueryNamesAndSubsIsNotSubscriptions() async throws {
        let (client, transport) = self.client([.json(200, Self.emptyPull)])
        _ = try await client.pull(cursors: ["subscriptions": 10, "playlists": 20, "favorites": 30],
                                  ids: ["subscriptions": "a1", "playlists": "b2", "favorites": "c3"])
        let request = try #require(transport.sent.first)
        #expect(request.method == "GET")
        #expect(request.url.path() == "/api/account/sync")
        #expect(request.body == nil)
        #expect(try Self.query(request) == ["subs": "10", "playlists": "20", "favorites": "30",
                                            "subs_id": "a1", "playlists_id": "b2",
                                            "favorites_id": "c3"])
    }

    /// A nil id is OMITTED, never sent empty: `subs_id=` would reach `startAfter(ts, "")`. A missing
    /// cursor is the server's own default, 0 — "never synced".
    @Test func anIdParameterWhoseValueIsNilIsOmittedAndAMissingCursorIsZero() async throws {
        let (client, transport) = self.client([.json(200, Self.emptyPull)])
        _ = try await client.pull(cursors: ["playlists": 7], ids: ["subscriptions": nil])
        #expect(try Self.query(transport.sent.first) == ["subs": "0", "playlists": "7",
                                                         "favorites": "0"])
    }

    /// The client hands back what the server sent, both halves of every cursor included — the pair
    /// is what makes a page break inside one millisecond resumable.
    @Test func aThreePageBodyDecodesWithBothHalvesOfEveryCursor() async throws {
        let (client, _) = self.client([.json(200, """
            {"subscriptions":{"items":[{"entityId":"\(Self.channelId)","deleted":false,
               "updatedAt":1700000000500,"channelUrl":"https://www.youtube.com/channel/\(Self.channelId)",
               "name":"Alafasy","avatarUrl":null,"subscribedAt":1690000000000}],
              "nextCursor":1700000000500,"nextCursorId":"\(Self.channelId)"},
             "playlists":{"items":[{"entityId":"\(Self.playlistId)","deleted":false,
               "updatedAt":1700000000600,"playlistUrl":"https://www.youtube.com/playlist?list=\(Self.playlistId)",
               "name":"Juz Amma","thumbnailUrl":null,"uploaderName":null,"savedAt":1690000000000}],
              "nextCursor":1700000000600,"nextCursorId":"\(Self.playlistId)"},
             "favorites":{"items":[{"entityId":"\(Self.videoId)","deleted":true,
               "updatedAt":1700000000700,"title":"Lecture","channelName":"Alafasy",
               "thumbnailUrl":null,"durationSeconds":754,"addedAt":1690000000000}],
              "nextCursor":null,"nextCursorId":null}}
            """)])
        let response = try await client.pull(cursors: [:], ids: [:])
        #expect(response.subscriptions.items.map(\.entityId) == [Self.channelId])
        #expect(response.subscriptions.nextCursor == 1_700_000_000_500)
        #expect(response.subscriptions.nextCursorId == Self.channelId)
        #expect(response.playlists.items.map(\.name) == ["Juz Amma"])
        #expect(response.playlists.nextCursor == 1_700_000_000_600)
        #expect(response.playlists.nextCursorId == Self.playlistId)
        #expect(response.favorites.items.first?.deleted == true)
        #expect(response.favorites.nextCursor == nil)
        #expect(response.favorites.nextCursorId == nil)
    }

    // MARK: - The cursor-id validator (`SyncController.java:66-82`, mirrored)

    /// Every shape the server answers 400 to, dropped BEFORE the request — the page is then
    /// re-fetched from the timestamp alone rather than spent on an error the client could see
    /// coming. The 1502-byte / 751-character entry is the one that separates a UTF-8 BYTE count
    /// from a character count; `id.count > 1500` passes every other row here.
    @Test func everyCursorIdShapeTheServerWouldRejectIsDroppedBeforeTheRequest() async throws {
        let rejected = [String(repeating: "a", count: 1501),   // 1501 bytes
                        String(repeating: "é", count: 751),    // 751 chars, 1502 bytes
                        "ab/cd",                               // Firestore path separator
                        "ab\u{0001}cd", "ab\u{007F}cd",        // control chars, incl. DEL
                        ".", "..",                             // reserved
                        "__name__", "__x__", "__"]             // the __reserved__ form
        for id in rejected {
            let (client, transport) = self.client([.json(200, Self.emptyPull)])
            _ = try await client.pull(cursors: ["subscriptions": 5], ids: ["subscriptions": id])
            let query = try Self.query(transport.sent.first)
            #expect(query["subs_id"] == nil, "\(id.prefix(12)) reached the server")
            #expect(query["subs"] == "5", "the timestamp half survives the id being dropped")
        }
    }

    /// The other edge: the validator must not be a blanket refusal, or every pull silently loses
    /// its tiebreaker. 1500 bytes is inclusive, and a lone leading or trailing `__` is a legal id.
    @Test func theValidatorKeepsTheIdsTheServerAccepts() async throws {
        let accepted = [String(repeating: "a", count: 1500),   // exactly 1500 bytes
                        String(repeating: "é", count: 750),    // 750 chars, exactly 1500 bytes
                        "__leading", "trailing__", ".x", "a.b", "a b", Self.channelId]
        for id in accepted {
            let (client, transport) = self.client([.json(200, Self.emptyPull)])
            _ = try await client.pull(cursors: [:], ids: ["favorites": id])
            #expect(try Self.query(transport.sent.first)["favorites_id"] == id,
                    "\(id.prefix(12)) was dropped")
        }
    }

    /// Explicit status semantics (ruling F1): a non-200 pull is not "an empty page", it is an
    /// error carrying the status the caller decides on.
    @Test func aNonSuccessPullThrowsItsStatus() async throws {
        let (client, _) = self.client([.json(401, #"{"error":"unauthorized"}"#)])
        await #expect(throws: SyncClientError.pullStatus(401)) {
            _ = try await client.pull(cursors: [:], ids: [:])
        }
    }

    // MARK: - PUT / DELETE

    /// The PUT path spells the rawValue — `subscriptions`, never the query name `subs` — and the
    /// body is the codec's bytes untouched: `SyncCodec` is the ONE place a wire field name is
    /// written, and a client that re-encoded would be a second one.
    @Test func thePutUsesThePathSegmentAndSendsTheCodecBytesVerbatim() async throws {
        let channel = SubscribedChannel(
            channelId: Self.channelId, title: "Alafasy", avatarUrl: nil,
            followedAt: Date(timeIntervalSince1970: 1_690_000_000),
            channelUrl: "https://www.youtube.com/channel/\(Self.channelId)")
        let body = try SyncCodec.body(for: channel)
        let (client, transport) = self.client([.json(200, #"{"deleted":false,"updatedAt":1}"#)])
        _ = try await client.put(.subscriptions, id: Self.channelId, body: body)
        let request = try #require(transport.sent.first)
        #expect(request.method == "PUT")
        #expect(request.url.path() == "/api/account/subscriptions/\(Self.channelId)")
        #expect(request.body == body)
        #expect(request.headers["Content-Type"] == "application/json")
    }

    /// SYNC-ECHO-01: `deleted: true` in a PUT's answer means the server's projection knows a parent
    /// was archived. Task 23 tombstones the row on it instead of merely clearing dirty, so it has
    /// to survive the client rather than be thrown away with the rest of the echo body.
    @Test func aPutAnsweringDeletedTrueSurfacesAsAnArchiveEcho() async throws {
        let (client, _) = self.client([.json(200, """
            {"entityId":"\(Self.channelId)","deleted":true,"updatedAt":123,
             "channelUrl":"https://www.youtube.com/channel/\(Self.channelId)","name":"Alafasy",
             "avatarUrl":null,"subscribedAt":1690000000000}
            """)])
        let (status, echo) = try await client.put(.subscriptions, id: Self.channelId,
                                                  body: Data("{}".utf8))
        #expect(status == 200)
        #expect(echo?.deleted == true)
        #expect(echo?.updatedAt == 123)
        #expect(SyncDecisions.push(status: status, hasBody: echo != nil) == .ok)
    }

    /// R-final7 P0's other half: a 2xx with no echo is a `.transientFailure`, and the client is
    /// what has to report "no body" rather than invent one — a synthesised echo would clear dirty
    /// against a write nothing confirmed.
    @Test func aPutWithNoDecodableBodySurfacesANilEcho() async throws {
        let (client, _) = self.client([.json(200, "")])
        let (status, echo) = try await client.put(.playlists, id: Self.playlistId, body: Data("{}".utf8))
        #expect(status == 200)
        #expect(echo == nil)
        #expect(SyncDecisions.push(status: status, hasBody: echo != nil) == .transientFailure)
    }

    /// The DELETE hands back the RAW status: 404 is `.ok` (an idempotent tombstone) and only
    /// `SyncDecisions.push` knows that, so the client must not collapse it into a throw. And it
    /// hands back the echo (Part B gate, Cubic round 1 P1): the server's tombstone time is what
    /// `clearDirty` stamps, so a later re-add is not wiped by the pull of that very tombstone.
    @Test func theDeleteReturnsItsRawStatusAndItsEchoForTheClassifier() async throws {
        let (client, transport) = self.client([.json(404, ""), .json(200, #"{"deleted":true,"updatedAt":7000}"#)])
        let (status, echo) = try await client.delete(.favorites, id: Self.videoId)
        #expect(status == 404)
        #expect(echo == nil)
        #expect(SyncDecisions.push(status: status, hasBody: false) == .ok)
        let (okStatus, okEcho) = try await client.delete(.favorites, id: Self.videoId)
        #expect(okStatus == 200)
        #expect(okEcho?.updatedAt == 7_000)
        let request = try #require(transport.sent.first)
        #expect(request.method == "DELETE")
        #expect(request.url.path() == "/api/account/favorites/\(Self.videoId)")
        #expect(request.body == nil)
        #expect(request.headers["Content-Type"] == nil)
    }

    /// Both write verbs, all three types, against the PATH spelling. `subs` here would 404 every
    /// subscription push while the pull kept working — the failure mode the two names exist to make
    /// visible.
    @Test func thePutAndDeletePathsCoverAllThreeTypes() async throws {
        for type in SyncEntityType.allCases {
            let (client, transport) = self.client([.json(200, "{}"), .json(200, "{}")])
            _ = try await client.put(type, id: "id-1", body: Data("{}".utf8))
            _ = try await client.delete(type, id: "id-1")
            #expect(transport.sent.map { $0.url.path() }
                    == ["/api/account/\(type.rawValue)/id-1", "/api/account/\(type.rawValue)/id-1"])
        }
        #expect(SyncEntityType.subscriptions.path == "subscriptions")
    }

    // MARK: - Headers

    /// `X-Device-Id` on every verb (`NetworkModule.kt`), and `Content-Type` only where there is a
    /// body to type.
    @Test func everyRequestCarriesTheDeviceIdHeader() async throws {
        let (client, transport) = self.client([.json(200, Self.emptyPull), .json(200, "{}"),
                                               .json(204, "")])
        _ = try await client.pull(cursors: [:], ids: [:])
        _ = try await client.put(.playlists, id: Self.playlistId, body: Data("{}".utf8))
        _ = try await client.delete(.favorites, id: Self.videoId)
        #expect(transport.sent.count == 3)
        for request in transport.sent {
            #expect(request.headers["X-Device-Id"] == Self.deviceId)
        }
        #expect(transport.sent.map { $0.headers["Content-Type"] } == [nil, "application/json", nil])
    }
}
