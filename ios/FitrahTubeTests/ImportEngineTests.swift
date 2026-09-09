import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

/// Task 28: the YouTube-import ENGINE — the authorizer seam, the three paginators, `ImportClient`
/// and `ImportPipeline`. **No Firebase, no Google SDK, no network anywhere in this file**: every
/// request is answered by `ScriptedTransport` and every clock is injected.
///
/// Two hosts are involved and they must never be confused. `YouTubeImportSource` addresses
/// `https://www.googleapis.com/youtube/v3/` with the user's OAuth bearer; `ImportClient` addresses
/// the FitrahTube backend with the device id and NO bearer of its own (`AuthorizedTransport` is
/// what signs it in production). The bearer never reaches the backend — pinned below.
///
/// Fixture ids are the approved three only (video `xc7keR2piUM`, channel
/// `UCmMcOjsVehVlEOteyrhjI2Q`, playlist `PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc`) plus synthetic
/// `UCimport…`/`PLimport…`/`vid…` tokens.

// MARK: - Shared fixtures

private enum Fixture {
    static let channel = "UCmMcOjsVehVlEOteyrhjI2Q"
    static let playlist = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
    static let video = "xc7keR2piUM"
    static let token = "ya29.fake-access-token"

    static func thumbnails(medium: String? = "https://img.test/m.jpg",
                           default def: String? = "https://img.test/d.jpg") -> String {
        var parts: [String] = []
        if let def { parts.append("\"default\":{\"url\":\"\(def)\"}") }
        if let medium { parts.append("\"medium\":{\"url\":\"\(medium)\"}") }
        return "{\(parts.joined(separator: ","))}"
    }

    /// `item.id` here is the SUBSCRIPTION id — the trap the mapping test exists for.
    static func subscriptionsPage(_ channelIds: [String], subscriptionId: String = "sub-row-1",
                                  nextPageToken: String? = nil) -> String {
        let items = channelIds.map {
            """
            {"id":"\(subscriptionId)","snippet":{"title":"Alafasy","resourceId":{"channelId":"\($0)"},
             "thumbnails":\(thumbnails())}}
            """
        }
        return page(items, nextPageToken)
    }

    static func playlistsPage(_ ids: [String], nextPageToken: String? = nil) -> String {
        let items = ids.map {
            "{\"id\":\"\($0)\",\"snippet\":{\"title\":\"Tafsir series\",\"thumbnails\":\(thumbnails(medium: nil))}}"
        }
        return page(items, nextPageToken)
    }

    static func videosPage(_ ids: [String], channelId: String = channel, nextPageToken: String? = nil) -> String {
        let items = ids.map {
            """
            {"id":"\($0)","snippet":{"title":"Lecture 1","channelId":"\(channelId)",
             "thumbnails":\(thumbnails())}}
            """
        }
        return page(items, nextPageToken)
    }

    private static func page(_ items: [String], _ nextPageToken: String?) -> String {
        let token = nextPageToken.map { "\"\($0)\"" } ?? "null"
        return "{\"items\":[\(items.joined(separator: ","))],\"nextPageToken\":\(token)}"
    }

    static func query(_ request: HTTPRequest) -> [String: String] {
        let items = URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems ?? []
        return Dictionary(items.map { ($0.name, $0.value ?? "") }, uniquingKeysWith: { first, _ in first })
    }

    /// One `results` envelope for `POST api/account/import/resolve`.
    static func resolveBody(_ rows: [String]) -> String { "{\"results\":[\(rows.joined(separator: ","))]}" }

    static func result(_ youtubeId: String, _ type: String, _ disposition: String,
                       content: String? = nil) -> String {
        """
        {"youtubeId":"\(youtubeId)","type":"\(type)","disposition":"\(disposition)",
         "content":\(content ?? "null")}
        """
    }
}

// MARK: - The authorizer seam

@Suite(.perTest)
struct YouTubeAuthorizerTests {

    /// One literal, in one place. A typo here is a consent screen that grants nothing and three
    /// paginators that 403 — and the mistake would be invisible until a device run.
    @Test func theScopeIsTheReadonlyYouTubeScopeAndNothingWider() {
        #expect(GoogleYouTubeAuthorizer.scope == "https://www.googleapis.com/auth/youtube.readonly")
        #expect(FakeYouTubeAuthorizer.scope == GoogleYouTubeAuthorizer.scope)
        // Never the write scope, never the force-ssl scope.
        #expect(GoogleYouTubeAuthorizer.scope.hasSuffix(".readonly"))
    }

    /// Ruling F11's shape, one provider over: an UNAVAILABLE authorizer fails rather than handing
    /// back a token, so "an unavailable provider is never asked" is a property a caller's test can
    /// actually break.
    @Test func anUnavailableAuthorizerFailsInsteadOfHandingBackAToken() async {
        let authorizer = FakeYouTubeAuthorizer(hasCurrentUser: false)
        await #expect(throws: YouTubeAuthorizerError.unavailable) { try await authorizer.authorize() }
        #expect(authorizer.authorizeCount == 1)
    }

    /// Review C1. `GIDSignIn.currentUser` is nil on EVERY cold launch — the SDK's initializer
    /// reads the bundle configuration and migrates keychain state but assigns `_currentUser`
    /// nowhere (`GIDSignIn.m:510-540`); the only two assignments are the interactive sign-in
    /// completion (`:936`) and a restore. Availability that reads `currentUser` alone therefore
    /// goes false at the next launch and the Import affordance disappears for every returning
    /// Google user, silently. The keychain-backed half is `hasPreviousSignIn` (`GIDSignIn.h:116`,
    /// `GIDSignIn.m:213-219`), and the RELAUNCH state — no current user, a previous sign-in — must
    /// be available AND must authorize.
    ///
    /// Tier 3 boundary: this pins the CONTRACT the Import screen consumes. The SDK half of
    /// `GoogleYouTubeAuthorizer` is NOT pinned by any hermetic test and cannot be.
    @Test func aRelaunchedSessionWithNoCurrentUserIsStillAvailableAndAuthorizes() async throws {
        let relaunched = FakeYouTubeAuthorizer(token: Fixture.token, hasCurrentUser: false,
                                               hasPreviousSignIn: true)
        #expect(relaunched.isAvailable)
        #expect(try await relaunched.authorize() == Fixture.token)

        // Neither fact: no Google grant to extend, so the affordance stays HIDDEN (RULING 28) —
        // which is what keeps an Apple or email/password account from being offered an import.
        let signedOut = FakeYouTubeAuthorizer(hasCurrentUser: false, hasPreviousSignIn: false)
        #expect(signedOut.isAvailable == false)
        await #expect(throws: YouTubeAuthorizerError.unavailable) { try await signedOut.authorize() }
    }

    /// F9: `forget()` drops the in-memory token so the next import asks again. It must NEVER be
    /// `disconnect()`, which revokes every granted scope and signs the Google user out of the app.
    @Test func forgetDropsTheTokenSoTheNextImportAsksAgain() async throws {
        let authorizer = FakeYouTubeAuthorizer(token: Fixture.token)
        #expect(try await authorizer.authorize() == Fixture.token)
        #expect(try await authorizer.authorize() == Fixture.token)
        #expect(authorizer.authorizeCount == 2)
        authorizer.forget()
        #expect(authorizer.forgetCount == 1)
        #expect(authorizer.heldToken == nil)
    }
}

// MARK: - The three paginators

@Suite(.perTest)
struct YouTubeImportSourceTests {

    private func source(_ responses: [HTTPResponse]) -> (YouTubeImportSource, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (YouTubeImportSource(transport: transport), transport)
    }

    /// The three mappings, each of which a generated client would have got wrong. Subscriptions
    /// take `snippet.resourceId.channelId` — NOT `item.id`, which is the *subscription* id;
    /// playlists take `item.id`; liked videos take `item.id` with `snippet.channelId`.
    @Test func theThreeMappingsTakeTheFieldsAndroidTakesNotTheObviousOnes() async {
        let (source, _) = self.source([
            .json(200, Fixture.subscriptionsPage([Fixture.channel], subscriptionId: "sub-row-1")),
            .json(200, Fixture.playlistsPage([Fixture.playlist])),
            .json(200, Fixture.videosPage([Fixture.video]))
        ])

        let (candidates, failed) = await source.fetchAll(accessToken: Fixture.token)

        #expect(failed.isEmpty)
        #expect(candidates.map(\.youtubeId) == [Fixture.channel, Fixture.playlist, Fixture.video])
        #expect(candidates.map(\.type) == [.channel, .playlist, .video])
        // The subscription row's own id is never the candidate's id.
        #expect(candidates.contains { $0.youtubeId == "sub-row-1" } == false)
        #expect(candidates[0].title == "Alafasy")
        #expect(candidates[0].channelId == nil)
        #expect(candidates[1].channelId == nil)
        // A liked video carries its uploader's channel id; the other two do not.
        #expect(candidates[2].channelId == Fixture.channel)
        // medium first, then default (`YtThumbnails.bestUrl`).
        #expect(candidates[0].thumbnailUrl == "https://img.test/m.jpg")
        #expect(candidates[1].thumbnailUrl == "https://img.test/d.jpg")
        #expect(candidates[0].id == Fixture.channel)
    }

    /// The paths and the query, verbatim — and **no request carries a `key=` parameter**. The
    /// OAuth token is the only credential (`YouTubeImportApi.kt:16-18`); an API key in a shipped
    /// binary is a key anyone can lift.
    @Test func theRequestPathsAndQueriesAreTheThreeEndpointsAndNoRequestCarriesAnApiKey() async {
        let (source, transport) = self.source([
            .json(200, Fixture.subscriptionsPage([Fixture.channel])),
            .json(200, Fixture.playlistsPage([Fixture.playlist])),
            .json(200, Fixture.videosPage([Fixture.video]))
        ])

        _ = await source.fetchAll(accessToken: Fixture.token)

        #expect(transport.sent.count == 3)
        #expect(transport.sent.map(\.method) == ["GET", "GET", "GET"])
        #expect(transport.sent.map { $0.url.path() }
                == ["/youtube/v3/subscriptions", "/youtube/v3/playlists", "/youtube/v3/videos"])
        #expect(Fixture.query(transport.sent[0]) == ["part": "snippet", "mine": "true", "maxResults": "50"])
        #expect(Fixture.query(transport.sent[1]) == ["part": "snippet", "mine": "true", "maxResults": "50"])
        #expect(Fixture.query(transport.sent[2]) == ["part": "snippet", "myRating": "like", "maxResults": "50"])
        for request in transport.sent {
            #expect(Fixture.query(request)["key"] == nil)
            #expect(request.url.absoluteString.contains("key=") == false)
            #expect(request.url.host() == "www.googleapis.com")
        }
    }

    /// The bearer goes on every YouTube request and NOWHERE else — no device id, no cookie, and
    /// (below, in `ImportClientTests`) never on a backend request.
    @Test func everyYouTubeRequestCarriesTheBearerAndNothingElse() async {
        let (source, transport) = self.source([
            .json(200, Fixture.subscriptionsPage([])),
            .json(200, Fixture.playlistsPage([])),
            .json(200, Fixture.videosPage([]))
        ])

        _ = await source.fetchAll(accessToken: Fixture.token)

        for request in transport.sent {
            #expect(request.headers["Authorization"] == "Bearer \(Fixture.token)")
            #expect(request.headers["X-Device-Id"] == nil)
            #expect(request.body == nil)
        }
    }

    /// The first page carries no `pageToken` at all; every later one carries the server's own.
    @Test func theFirstPageOmitsThePageTokenAndLaterPagesCarryTheServersOwn() async {
        let (source, transport) = self.source([
            .json(200, Fixture.subscriptionsPage([Fixture.channel], nextPageToken: "p2")),
            .json(200, Fixture.subscriptionsPage(["UCimport001"])),
            .json(200, Fixture.playlistsPage([])),
            .json(200, Fixture.videosPage([]))
        ])

        let (candidates, failed) = await source.fetchAll(accessToken: Fixture.token)

        #expect(failed.isEmpty)
        #expect(candidates.map(\.youtubeId) == [Fixture.channel, "UCimport001"])
        #expect(Fixture.query(transport.sent[0])["pageToken"] == nil)
        #expect(Fixture.query(transport.sent[1])["pageToken"] == "p2")
    }

    /// F12: 40 pages is the hard cap. Every one of the forty pages — the LAST included — still
    /// carries a fresh `nextPageToken`, so only the cap can be what stopped it. Break the cap and
    /// request 41 eats the playlists response, which is what the `sent[40]` and `failed` assertions
    /// below turn into a failure rather than a silent extra round trip.
    @Test func aPaginatorStopsAtFortyPages() async {
        var responses = (0..<40).map {
            HTTPResponse.json(200, Fixture.subscriptionsPage(["UCimport\(String(format: "%03d", $0))"],
                                                             nextPageToken: "p\($0 + 1)"))
        }
        responses.append(.json(200, Fixture.playlistsPage([])))
        responses.append(.json(200, Fixture.videosPage([])))
        let (source, transport) = self.source(responses)

        let (candidates, failed) = await source.fetchAll(accessToken: Fixture.token)

        #expect(YouTubeImportSource.maxPages == 40)
        #expect(candidates.count == 40)
        #expect(failed.isEmpty)                        // truncation is not a failure
        #expect(transport.sent.count == 42)            // 40 subscriptions + 1 playlists + 1 videos
        #expect(transport.sent[40].url.path() == "/youtube/v3/playlists")
    }

    /// A server REPEATING a token is stopped by the seen-token set, not by the page cap — the cap
    /// would still be 38 requests away, and each one would re-fetch the same page.
    @Test func aServerRepeatingAPageTokenIsStoppedByTheSeenTokenSetNotThePageCap() async {
        let (source, transport) = self.source([
            .json(200, Fixture.subscriptionsPage([Fixture.channel], nextPageToken: "loop")),
            .json(200, Fixture.subscriptionsPage(["UCimport001"], nextPageToken: "loop")),
            .json(200, Fixture.playlistsPage([])),
            .json(200, Fixture.videosPage([]))
        ])

        let (candidates, _) = await source.fetchAll(accessToken: Fixture.token)

        #expect(candidates.map(\.youtubeId) == [Fixture.channel, "UCimport001"])
        // Two subscription requests, THEN the next type — not 40.
        #expect(transport.sent.count == 4)
        #expect(transport.sent[2].url.path() == "/youtube/v3/playlists")
    }

    /// Three INDEPENDENT paginators: a 403 on `playlists` (the scope granted, the resource not)
    /// must not suppress the other two (`YouTubeImportRemoteSource.kt:36-66`).
    @Test func aFailureOnPlaylistsLeavesSubscriptionsAndLikedVideosIntact() async {
        let (source, _) = self.source([
            .json(200, Fixture.subscriptionsPage([Fixture.channel])),
            .json(403, "{\"error\":{\"code\":403}}"),
            .json(200, Fixture.videosPage([Fixture.video]))
        ])

        let (candidates, failed) = await source.fetchAll(accessToken: Fixture.token)

        #expect(failed == [.playlist])
        #expect(candidates.map(\.youtubeId) == [Fixture.channel, Fixture.video])
    }

    /// A transport throw and an unreadable 200 land in the same place — that type failed, the
    /// others still answer. An empty list is a real answer and must not look like a failure.
    @Test func aTransportThrowAndAMalformedPageBothFailOnlyTheirOwnType() async {
        let (source, _) = self.source([
            .failing(URLError(.notConnectedToInternet)),
            .json(200, "{\"items\":[{\"id\":\"PL1\"}]}"),        // no snippet
            .json(200, Fixture.videosPage([Fixture.video]))
        ])

        let (candidates, failed) = await source.fetchAll(accessToken: Fixture.token)

        #expect(failed == [.channel, .playlist])
        #expect(candidates.map(\.youtubeId) == [Fixture.video])
    }

    /// Cancellation stops the fetch where it stands rather than spending the remaining two types'
    /// round trips on a screen the user has already left.
    @Test func aCancelledFetchStopsWithoutStartingTheNextType() async {
        let gate = Gate()
        let transport = ScriptedTransport([
            .json(200, Fixture.subscriptionsPage([Fixture.channel])),
            .json(200, Fixture.playlistsPage([Fixture.playlist])),
            .json(200, Fixture.videosPage([Fixture.video]))
        ], park: { index in if index == 1 { await gate.block() } })
        let source = YouTubeImportSource(transport: transport)

        let task = Task { await source.fetchAll(accessToken: Fixture.token) }
        await gate.waitUntilBlocked()
        task.cancel()
        await gate.release()
        let (candidates, failed) = await task.value

        // The first page's items survive; nothing else was even requested.
        #expect(candidates.map(\.youtubeId) == [Fixture.channel])
        #expect(failed.isEmpty)
        #expect(transport.sent.count == 1)
    }
}

// MARK: - `ImportClient`

@Suite(.perTest)
struct ImportClientTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private func client(_ responses: [HTTPResponse]) -> (ImportClient, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (ImportClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-123")),
                transport)
    }

    private static let candidates = [
        ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "Alafasy",
                        thumbnailUrl: "https://img.test/m.jpg", channelId: nil),
        ImportCandidate(type: .video, youtubeId: Fixture.video, title: "Lecture 1",
                        thumbnailUrl: nil, channelId: Fixture.channel)
    ]

    /// The request body is `{"items":[…]}` with `ImportItem`'s five fields, and the response's
    /// `results` decode with their disposition and the canonical `content` an APPROVED row carries.
    @Test func theBodyIsAnItemsArrayAndTheResultsDecodeWithTheirDisposition() async throws {
        let (client, transport) = self.client([
            .json(200, Fixture.resolveBody([
                Fixture.result(Fixture.channel, "CHANNEL", "APPROVED",
                               content: """
                               {"id":"\(Fixture.channel)","type":"CHANNEL","name":"Mishary Alafasy",
                                "thumbnailUrl":"https://cdn.test/a.jpg"}
                               """),
                Fixture.result(Fixture.video, "VIDEO", "PENDING")
            ]))
        ])

        let results = try await client.resolve(Self.candidates)

        let request = try #require(transport.sent.first)
        #expect(request.method == "POST")
        #expect(request.url.path() == "/api/account/import/resolve")
        let body = try #require(request.body)
        let json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        let items = try #require(json["items"] as? [[String: Any]])
        #expect(items.count == 2)
        #expect(items[0]["type"] as? String == "CHANNEL")
        #expect(items[0]["youtubeId"] as? String == Fixture.channel)
        #expect(items[0]["title"] as? String == "Alafasy")
        #expect(items[0]["thumbnailUrl"] as? String == "https://img.test/m.jpg")
        #expect(items[1]["channelId"] as? String == Fixture.channel)

        #expect(results.map(\.youtubeId) == [Fixture.channel, Fixture.video])
        #expect(results.map(\.type) == [.channel, .video])
        #expect(results.map(\.disposition) == [.approved, .pending])
        // A CHANNEL's canonical label arrives as `name`, not `title` (`ContentItemMapper.java:28`).
        #expect(results[0].content?.name == "Mishary Alafasy")
        #expect(results[0].content?.thumbnailUrl == "https://cdn.test/a.jpg")
        #expect(results[1].content == nil)
    }

    /// **The bearer never touches the backend.** This client carries the device id and, in
    /// production, whatever `AuthorizedTransport` puts on — never the YouTube OAuth token, which is
    /// a credential for a different host and is not the backend's business.
    @Test func theBackendRequestCarriesTheDeviceIdAndNoYouTubeBearerAnywhere() async throws {
        let (client, transport) = self.client([.json(200, Fixture.resolveBody([]))])

        _ = try await client.resolve(Self.candidates)

        let request = try #require(transport.sent.first)
        #expect(request.headers["X-Device-Id"] == "dev-123")
        #expect(request.headers["Content-Type"] == "application/json")
        #expect(request.headers["Authorization"] == nil)
        let body = String(decoding: try #require(request.body), as: UTF8.self)
        #expect(body.contains(Fixture.token) == false)
        #expect(body.lowercased().contains("bearer") == false)
        #expect(request.url.host() == "api.fitrah.test")
    }

    /// An unknown disposition writes nothing and counts as rejectedOrError, never as approved —
    /// and a row whose `type` this build cannot name is DROPPED, because the pipeline routes the
    /// write by that type (Task 25 deviation 3, one endpoint over).
    @Test func anUnknownDispositionReadsAsErrorAndAnUnknownTypeIsDropped() async throws {
        let (client, _) = self.client([
            .json(200, Fixture.resolveBody([
                Fixture.result(Fixture.channel, "CHANNEL", "QUARANTINED"),
                Fixture.result(Fixture.playlist, "SHORT", "APPROVED"),
                Fixture.result(Fixture.video, "VIDEO", "REJECTED")
            ]))
        ])

        let results = try await client.resolve(Self.candidates)

        #expect(results.map(\.youtubeId) == [Fixture.channel, Fixture.video])
        #expect(results.map(\.disposition) == [.error, .rejected])
        #expect(Disposition.fromWire(nil) == .error)
        #expect(Disposition.fromWire("APPROVED") == .approved)
    }

    /// 429 is the per-user daily item budget (`SubmissionRateLimiter.IMPORT_DAILY_ITEM_BUDGET`);
    /// this handler sends the seconds in the `Retry-After` HEADER
    /// (`GlobalExceptionHandler.java:310`), so that leg is the one it really exercises.
    @Test func aFourTwentyNineCarriesItsRetryAfterSecondsAndOtherStatusesAreGeneric() async {
        let (rateLimited, _) = self.client([
            .json(429, "{\"message\":\"Daily import limit reached. Try again later.\",\"remaining\":0}",
                  headers: ["Retry-After": "3600"])
        ])
        await #expect(throws: AccountError.rateLimited(retryAfterSeconds: 3600)) {
            try await rateLimited.resolve(Self.candidates)
        }

        let (server, _) = self.client([.json(500, "{}")])
        await #expect(throws: AccountError.unknown(status: 500)) { try await server.resolve(Self.candidates) }

        let (offline, _) = self.client([.failing(URLError(.notConnectedToInternet))])
        await #expect(throws: AccountError.network) { try await offline.resolve(Self.candidates) }
    }

    /// A 200 that is not a results envelope FAILS. Decoding it to "no results" would silently turn
    /// a broken deploy into a clean import that wrote nothing.
    @Test func aMalformedBodyFailsRatherThanDecodingToNoResults() async {
        let (client, _) = self.client([.json(200, "{\"data\":[]}")])
        await #expect(throws: AccountError.unknown(status: 200)) { try await client.resolve(Self.candidates) }
    }
}

// MARK: - `ImportPipeline`

@MainActor private final class ProgressLog {
    struct Entry: Equatable { var phase: ImportPhase; var done: Int; var total: Int }
    private(set) var entries: [Entry] = []
    func record(_ phase: ImportPhase, _ done: Int, _ total: Int) {
        entries.append(Entry(phase: phase, done: done, total: total))
    }
    var last: Entry? { entries.last }
}

@MainActor private final class DirtyLog {
    private(set) var uids: [String] = []
    func record(_ uid: String) { uids.append(uid) }
}

@Suite(.perTest)
@MainActor
struct ImportPipelineTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let now = Date(timeIntervalSince1970: 1_756_800_000)

    private struct Rig {
        var pipeline: ImportPipeline
        var transport: ScriptedTransport
        var container: ModelContainer
        var favorites: SwiftDataFavoritesStore
        var subscriptions: SwiftDataSubscriptionsStore
        var playlists: SwiftDataSavedPlaylistsStore
        var dirty: DirtyLog
    }

    private func rig(_ responses: [HTTPResponse],
                     park: (@Sendable (Int) async -> Void)? = nil,
                     uid: String = "uid-1") throws -> Rig {
        let container = try ModelContainer(
            for: FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self,
            configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let dirty = DirtyLog()
        let onDirty: (String) -> Void = { dirty.record($0) }
        let favorites = SwiftDataFavoritesStore(modelContainer: container, onDirty: onDirty)
        let subscriptions = SwiftDataSubscriptionsStore(modelContainer: container, onDirty: onDirty)
        let playlists = SwiftDataSavedPlaylistsStore(modelContainer: container, onDirty: onDirty)
        for store in [favorites as any UserScoped, subscriptions, playlists] { store.currentUserId = uid }
        let transport = ScriptedTransport(responses, park: park)
        let client = ImportClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-123"))
        return Rig(pipeline: ImportPipeline(client: client, favorites: favorites, subscriptions: subscriptions,
                                            playlists: playlists, now: { Self.now }),
                   transport: transport, container: container, favorites: favorites, subscriptions: subscriptions,
                   playlists: playlists, dirty: dirty)
    }

    private static func channels(_ count: Int, from: Int = 0) -> [ImportCandidate] {
        (from..<(from + count)).map {
            ImportCandidate(type: .channel, youtubeId: "UCimport\(String(format: "%04d", $0))",
                            title: "Channel \($0)", thumbnailUrl: nil, channelId: nil)
        }
    }

    private func channelRow(_ rig: Rig, _ id: String) throws -> SubscribedChannel {
        let context = ModelContext(rig.container)
        let rows = try context.fetch(FetchDescriptor<SubscribedChannel>(
            predicate: #Predicate { $0.channelId == id }))
        return try #require(rows.first)
    }

    // MARK: Dedupe

    /// The dedupe is deleted-AGNOSTIC and status-AGNOSTIC (`channelExistsAny`): a soft-deleted row
    /// and an AWAITING row both count as present, and neither is ever sent to the backend.
    @Test func alreadyPresentRowsAreNeverSentToTheBackendWhateverStateTheyAreIn() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.playlist, "PLAYLIST", "APPROVED")
        ]))])
        // 1. a tombstoned subscription (subscribe, then unsubscribe)
        try rig.subscriptions.toggle(id: Fixture.channel, name: "Alafasy", avatarURL: nil)
        try rig.subscriptions.toggle(id: Fixture.channel, name: nil, avatarURL: nil)
        #expect(rig.subscriptions.isSubscribed(Fixture.channel) == false)
        // 2. an AWAITING favorite, hidden from `items` but present
        try rig.favorites.importVideo(id: Fixture.video, title: "Lecture", channelName: "",
                                      thumbnailUrl: nil, durationSeconds: 0,
                                      approvalStatus: "AWAITING", at: Self.now)
        #expect(rig.favorites.items.isEmpty)

        let candidates = [
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "Alafasy",
                            thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .video, youtubeId: Fixture.video, title: "Lecture",
                            thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "Tafsir",
                            thumbnailUrl: nil, channelId: nil)
        ]
        let log = ProgressLog()
        let summary = await rig.pipeline.run(candidates, progress: log.record)

        #expect(summary.alreadyPresent == 2)
        #expect(summary.skipped == 2)
        #expect(summary.added == 1)
        // ONE request, carrying ONE item: the two present rows never left the device.
        #expect(rig.transport.sent.count == 1)
        let body = String(decoding: try #require(rig.transport.sent[0].body), as: UTF8.self)
        #expect(body.contains(Fixture.playlist))
        #expect(body.contains(Fixture.channel) == false)
        #expect(body.contains(Fixture.video) == false)
        // `total` counts the FRESH candidates only.
        #expect(log.entries.first?.total == 1)
    }

    // MARK: Chunking + the 429 stop

    /// `ImportClient.batchSize` is the server's own `@Size(max = 200)`, so 401 candidates are
    /// 200 + 200 + 1 — never one oversized request the backend 400s.
    @Test func fourHundredAndOneCandidatesChunkIntoTwoHundredTwoHundredAndOne() async throws {
        let candidates = Self.channels(401)
        let rig = try rig((0..<3).map { chunk in
            let ids = candidates[(chunk * 200)..<min((chunk + 1) * 200, 401)]
            return .json(200, Fixture.resolveBody(ids.map { Fixture.result($0.youtubeId, "CHANNEL", "REJECTED") }))
        })

        let log = ProgressLog()
        let summary = await rig.pipeline.run(candidates, progress: log.record)

        #expect(ImportClient.batchSize == 200)
        #expect(rig.transport.sent.count == 3)
        let counts = try rig.transport.sent.map { request -> Int in
            let json = try JSONSerialization.jsonObject(with: try #require(request.body)) as? [String: Any]
            return (json?["items"] as? [[String: Any]])?.count ?? -1
        }
        #expect(counts == [200, 200, 1])
        #expect(summary.skipped == 401)
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 401, total: 401))
    }

    /// F10: a 429 BREAKS the loop. The chunks already written persist — and dedupe on the retry,
    /// which is what makes "retry the whole selection" safe rather than duplicating everything.
    @Test func aFourTwentyNineBreaksTheLoopAndTheWrittenChunksPersistAndDedupeOnRetry() async throws {
        // THREE chunks (200 / 200 / 1), so "break" and "carry on with the next chunk" are
        // distinguishable: the 429 lands on chunk 2 and chunk 3 must never be requested.
        let candidates = Self.channels(401)
        let approved: (ArraySlice<ImportCandidate>) -> String = {
            Fixture.resolveBody($0.map { Fixture.result($0.youtubeId, "CHANNEL", "APPROVED") })
        }
        let rig = try rig([
            .json(200, approved(candidates.prefix(200))),
            .json(429, "{\"message\":\"Daily import limit reached. Try again later.\",\"remaining\":0}",
                  headers: ["Retry-After": "3600"]),
            // the retry, once the window has reset: 201 candidates are still fresh -> 200 + 1
            .json(200, approved(candidates[200..<400])),
            .json(200, approved(candidates[400...]))
        ])

        let log = ProgressLog()
        let first = await rig.pipeline.run(candidates, progress: log.record)

        #expect(first.rateLimited)
        #expect(first.added == 200)
        #expect(rig.subscriptions.items.count == 200)
        #expect(rig.transport.sent.count == 2)      // chunk 3 was never asked for
        // The DONE emission carries the ACTUAL processed count — a truncated run is not painted
        // as complete (cubic-P3).
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 200, total: 401))

        let second = await rig.pipeline.run(candidates, progress: { _, _, _ in })
        #expect(second.rateLimited == false)
        #expect(second.alreadyPresent == 200)
        #expect(second.added == 201)
        #expect(rig.subscriptions.items.count == 401)
        #expect(rig.transport.sent.count == 4)
        let retry = try #require(rig.transport.sent.last?.body)
        let json = try JSONSerialization.jsonObject(with: retry) as? [String: Any]
        #expect((json?["items"] as? [[String: Any]])?.count == 1)
    }

    // MARK: The write matrix

    /// APPROVED prefers the CANONICAL metadata over the candidate's, and a channel's canonical
    /// label is `name` — `ContentItemMapper.fromChannel` never sets `title`, so reading `title`
    /// alone would silently keep YouTube's own copy of every imported channel's name.
    @Test func anApprovedResultPrefersTheCanonicalMetadataIncludingAChannelsName() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "APPROVED", content: """
            {"id":"\(Fixture.channel)","type":"CHANNEL","name":"Mishary Rashid Alafasy",
             "thumbnailUrl":"https://cdn.test/curated.jpg"}
            """),
            Fixture.result(Fixture.playlist, "PLAYLIST", "APPROVED", content: """
            {"id":"\(Fixture.playlist)","type":"PLAYLIST","title":"Curated Tafsir",
             "thumbnailUrl":"https://cdn.test/pl.jpg"}
            """),
            Fixture.result(Fixture.video, "VIDEO", "APPROVED", content: """
            {"id":"\(Fixture.video)","type":"VIDEO","title":"Curated Lecture",
             "thumbnailUrl":"https://cdn.test/v.jpg","channelTitle":"Mishary Alafasy",
             "durationSeconds":930}
            """)
        ]))])

        let summary = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "yt name",
                            thumbnailUrl: "https://img.test/yt.jpg", channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "yt playlist",
                            thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .video, youtubeId: Fixture.video, title: "yt video",
                            thumbnailUrl: nil, channelId: Fixture.channel)
        ], progress: { _, _, _ in })

        #expect(summary.added == 3)
        #expect(summary.sentForReview == 0)
        let channel = try #require(rig.subscriptions.items.first)
        #expect(channel.title == "Mishary Rashid Alafasy")
        #expect(channel.avatarUrl == "https://cdn.test/curated.jpg")
        #expect(channel.approvalStatus == "APPROVED")
        #expect(channel.source == "USER_IMPORT")
        #expect(channel.importedAt == Self.now)
        #expect(channel.dirty)
        let playlist = try #require(rig.playlists.items.first)
        #expect(playlist.title == "Curated Tafsir")
        #expect(playlist.thumbnailUrl == "https://cdn.test/pl.jpg")
        let video = try #require(rig.favorites.items.first)
        #expect(video.title == "Curated Lecture")
        #expect(video.channelName == "Mishary Alafasy")
        #expect(video.durationSeconds == 930)
        #expect(video.source == "USER_IMPORT")
    }

    /// PENDING writes AWAITING with the CANDIDATE's metadata (the backend returns no content for
    /// it), so the row is present and `isSubscribed`/`isFavorite` true, but hidden from `items`
    /// until an admin approves it.
    @Test func aPendingResultWritesAwaitingWithTheCandidatesOwnMetadata() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "PENDING"),
            Fixture.result(Fixture.playlist, "PLAYLIST", "PENDING")
        ]))])

        let summary = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "Alafasy",
                            thumbnailUrl: "https://img.test/a.jpg", channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "Tafsir series",
                            thumbnailUrl: nil, channelId: nil)
        ], progress: { _, _, _ in })

        #expect(summary.added == 0)
        #expect(summary.sentForReview == 2)
        #expect(summary.skipped == 0)
        // Hidden from the lists, present to the toggles.
        #expect(rig.subscriptions.items.isEmpty)
        #expect(rig.playlists.items.isEmpty)
        #expect(rig.subscriptions.isSubscribed(Fixture.channel))
        #expect(rig.playlists.isSaved(Fixture.playlist))
        let row = try channelRow(rig, Fixture.channel)
        #expect(row.approvalStatus == "AWAITING")
        #expect(row.title == "Alafasy")
        #expect(row.avatarUrl == "https://img.test/a.jpg")
        #expect(row.source == "USER_IMPORT")
        #expect(row.importedAt == Self.now)
    }

    /// REJECTED, ERROR and a disposition this build cannot name all write NOTHING. `sentForReview`
    /// counts only rows actually WRITTEN — a PENDING for a youtubeId absent from the chunk is
    /// skipped, so the count must skip it too or the summary over-reports.
    @Test func rejectedErrorAndUnknownWriteNothingAndSentForReviewCountsOnlyWrittenRows() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "REJECTED"),
            Fixture.result(Fixture.playlist, "PLAYLIST", "ERROR"),
            Fixture.result(Fixture.video, "VIDEO", "QUARANTINED"),
            // a youtubeId the request never carried
            Fixture.result("UCimport9999", "CHANNEL", "PENDING")
        ]))])

        let summary = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "c", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "p", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .video, youtubeId: Fixture.video, title: "v", thumbnailUrl: nil, channelId: nil)
        ], progress: { _, _, _ in })

        #expect(summary.added == 0)
        #expect(summary.sentForReview == 0)
        #expect(summary.skipped == 4)
        #expect(rig.subscriptions.items.isEmpty)
        #expect(rig.playlists.items.isEmpty)
        #expect(rig.favorites.items.isEmpty)
        #expect(rig.subscriptions.isSubscribed(Fixture.channel) == false)
        #expect(rig.favorites.isFavorite(Fixture.video) == false)
    }

    /// The canonical URLs are STORED DATA the sync wire requires — never a navigable affordance.
    /// A PENDING video's `channelName` is deliberately `""`, NEVER the `UC…` id: the candidate
    /// carries an id, and an id rendered where a name belongs is worse than a blank.
    @Test func theCanonicalUrlsAreStoredAndAPendingVideosChannelNameIsBlankNeverTheChannelId() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "PENDING"),
            Fixture.result(Fixture.playlist, "PLAYLIST", "PENDING"),
            Fixture.result(Fixture.video, "VIDEO", "PENDING")
        ]))])

        _ = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "c", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "p", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .video, youtubeId: Fixture.video, title: "v", thumbnailUrl: nil,
                            channelId: Fixture.channel)
        ], progress: { _, _, _ in })

        #expect(try channelRow(rig, Fixture.channel).channelUrl
                == "https://www.youtube.com/channel/\(Fixture.channel)")
        let context = ModelContext(rig.container)
        let playlistId = Fixture.playlist
        let playlistRow = try #require(try context.fetch(FetchDescriptor<SavedPlaylist>(
            predicate: #Predicate { $0.playlistId == playlistId })).first)
        #expect(playlistRow.playlistUrl == "https://www.youtube.com/playlist?list=\(Fixture.playlist)")
        #expect(playlistRow.uploaderName == nil)
        let videoId = Fixture.video
        let videoRow = try #require(try context.fetch(FetchDescriptor<FavoriteVideo>(
            predicate: #Predicate { $0.videoId == videoId })).first)
        #expect(videoRow.channelName == "")
        #expect(videoRow.channelName != Fixture.channel)
        #expect(videoRow.durationSeconds == 0)
    }

    // MARK: The cap bypass (CF-A-11)

    /// The 30-channel cap is bypassed for the IMPORT path only. A manual subscribe at 30 still
    /// throws `.capReached` — pinned in both directions, because a bypass that leaked into the
    /// subscribe button would silently delete RULING 27.
    @Test func theCapIsBypassedForImportButAManualSubscribeAtThirtyStillThrows() async throws {
        let candidates = Self.channels(35)
        let rig = try rig([.json(200, Fixture.resolveBody(
            candidates.map { Fixture.result($0.youtubeId, "CHANNEL", "APPROVED") }))])

        let summary = await rig.pipeline.run(candidates, progress: { _, _, _ in })

        #expect(SwiftDataSubscriptionsStore.cap == 30)
        #expect(summary.added == 35)
        #expect(rig.subscriptions.items.count == 35)
        // …and the ordinary path is unchanged: the cap counts APPROVED rows, and there are 35.
        #expect(throws: SubscriptionsError.capReached) {
            try rig.subscriptions.toggle(id: Fixture.channel, name: "Alafasy", avatarURL: nil)
        }
        #expect(rig.subscriptions.isSubscribed(Fixture.channel) == false)
    }

    // MARK: Sync + cancellation

    /// Every imported row is DIRTY and goes through the store, so `AppContainer.pushDirtySoon`
    /// coalesces them exactly as a manual toggle's write does. A raw `ModelContext` write would
    /// have left the whole import invisible to the sync manager.
    @Test func everyImportedRowIsDirtyAndPushesThroughTheStore() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "APPROVED"),
            Fixture.result(Fixture.playlist, "PLAYLIST", "PENDING"),
            Fixture.result(Fixture.video, "VIDEO", "APPROVED")
        ]))], uid: "uid-7")

        _ = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "c", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "p", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .video, youtubeId: Fixture.video, title: "v", thumbnailUrl: nil, channelId: nil)
        ], progress: { _, _, _ in })

        #expect(rig.dirty.uids == ["uid-7", "uid-7", "uid-7"])
        #expect(try channelRow(rig, Fixture.channel).dirty)
        #expect(rig.subscriptions.items.first?.userId == "uid-7")
    }

    /// Cancellable at every await, and NO half-imported state: the chunk that was in flight when
    /// the cancel landed is never written, while the chunks already committed stay (and dedupe on
    /// a retry, exactly as the 429 stop does).
    @Test func aCancelMidChunkWritesNothingFromThatChunkAndLeavesTheEarlierOnesIntact() async throws {
        let candidates = Self.channels(201)
        let gate = Gate()
        let rig = try rig([
            .json(200, Fixture.resolveBody(candidates.prefix(200).map {
                Fixture.result($0.youtubeId, "CHANNEL", "APPROVED")
            })),
            .json(200, Fixture.resolveBody([Fixture.result(candidates[200].youtubeId, "CHANNEL", "APPROVED")]))
        ], park: { index in if index == 2 { await gate.block() } })

        let log = ProgressLog()
        let pipeline = rig.pipeline
        let task = Task { await pipeline.run(candidates, progress: log.record) }
        await gate.waitUntilBlocked()
        task.cancel()
        await gate.release()
        let summary = await task.value

        #expect(summary.added == 200)
        #expect(rig.subscriptions.items.count == 200)
        #expect(rig.subscriptions.isSubscribed(candidates[200].youtubeId) == false)
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 200, total: 201))
        #expect(summary.rateLimited == false)
    }

    /// The phases in order, with the actual counts. `progress` is never called with the total as a
    /// stand-in for the processed count.
    @Test func theProgressPhasesRunResolvingThenWritingThenDoneWithRealCounts() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "APPROVED"),
            Fixture.result(Fixture.playlist, "PLAYLIST", "APPROVED")
        ]))])

        let log = ProgressLog()
        _ = await rig.pipeline.run([
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "c", thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "p", thumbnailUrl: nil, channelId: nil)
        ], progress: log.record)

        #expect(log.entries.first == ProgressLog.Entry(phase: .resolving, done: 0, total: 2))
        #expect(log.entries.contains(ProgressLog.Entry(phase: .writing, done: 0, total: 2)))
        #expect(log.entries.contains(ProgressLog.Entry(phase: .writing, done: 1, total: 2)))
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 2, total: 2))
        #expect(log.entries.map(\.phase).contains(.done))
    }

    /// An empty selection is a real answer: no request, no write, and a DONE emission at zero.
    @Test func anEmptySelectionSendsNothingAndReportsAnEmptySummary() async throws {
        let rig = try rig([])
        let log = ProgressLog()

        let summary = await rig.pipeline.run([], progress: log.record)

        #expect(rig.transport.sent.isEmpty)
        // `processed == 0 == total`: an empty selection is COMPLETE, not partial.
        #expect(summary == ImportSummary(added: 0, sentForReview: 0, skipped: 0, alreadyPresent: 0,
                                         processed: 0, rateLimited: false))
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 0, total: 0))
    }

    /// A non-429 failure mid-run BREAKS the loop the same way, but does NOT claim the rate limit.
    ///
    /// Review I1: what says "this run was partial" is `summary.processed`, short of the candidates
    /// asked for. It used to live ONLY in the transient DONE progress emission, so a screen that
    /// keeps the summary and drops the last callback — the obvious implementation — would tell a
    /// user whose connection died after chunk 1 "200 added" and nothing else.
    @Test func aNetworkFailureMidRunStopsWithoutClaimingTheRateLimit() async throws {
        let candidates = Self.channels(201)
        let rig = try rig([
            .json(200, Fixture.resolveBody(candidates.prefix(200).map {
                Fixture.result($0.youtubeId, "CHANNEL", "APPROVED")
            })),
            .failing(URLError(.notConnectedToInternet))
        ])

        let log = ProgressLog()
        let summary = await rig.pipeline.run(candidates, progress: log.record)

        #expect(summary.added == 200)
        #expect(summary.rateLimited == false)
        #expect(summary.processed == 200)
        #expect(summary.processed < candidates.count)
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 200, total: 201))
    }

    /// Review M3. `ImportClient` DROPS a result row whose `type` this build cannot name
    /// (`ImportClient.swift:84,118-119`), so a chunk can come back with fewer results than it had
    /// candidates. `processed` counts CANDIDATES: counting results would make this fully successful
    /// run read as partial and defeat the one signal I1 added it for.
    @Test func aRowDroppedForAnUnnameableTypeStillCountsAsProcessed() async throws {
        let rig = try rig([.json(200, Fixture.resolveBody([
            Fixture.result(Fixture.channel, "CHANNEL", "APPROVED"),
            // A type no `CandidateType` names: the client drops the row before the pipeline sees it.
            Fixture.result(Fixture.playlist, "SHORT", "APPROVED")
        ]))])
        let candidates = [
            ImportCandidate(type: .channel, youtubeId: Fixture.channel, title: "Alafasy",
                            thumbnailUrl: nil, channelId: nil),
            ImportCandidate(type: .playlist, youtubeId: Fixture.playlist, title: "Tafsir",
                            thumbnailUrl: nil, channelId: nil)
        ]

        let log = ProgressLog()
        let summary = await rig.pipeline.run(candidates, progress: log.record)

        #expect(summary.added == 1)
        #expect(rig.transport.sent.count == 1)
        // Both candidates were processed; the run was NOT cut off.
        #expect(summary.processed == candidates.count)
        #expect(log.last == ProgressLog.Entry(phase: .done, done: 2, total: 2))
    }
}
