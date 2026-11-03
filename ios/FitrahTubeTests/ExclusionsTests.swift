import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Owner bug (2026-11-03): an admin's "exclude from this channel/playlist" never reached the app, which builds
/// those lists on the phone from YouTube. Jar #15 serves `excludedVideoIds`/`excludedPlaylistIds` on the public
/// channel and playlist routes; every list built from a channel or playlist must drop them. Each case asserts an
/// excluded id is ABSENT, through the real `LiveBrowseSource`/`BrowseClient` over InnerTubeKit's own fixtures.
@Suite(.perTest)
struct ExclusionsTests {
    static let base = URL(string: "https://app.fitrahtube.com/")!

    /// The backend's channel/playlist GETs (per path, settable mid-test), `/titles` (no originals), the index push,
    /// the Atom feed, and youtubei (`firstPage` without a continuation in the body, `nextPage` with one).
    nonisolated final class Backend: HTTPTransport, @unchecked Sendable {
        enum Answer { case lists(videos: [String], playlists: [String]), status(Int), throwing }
        private let lock = NSLock()
        private var answers: [String: Answer] = [:]
        private var counts: [String: Int] = [:]
        private var methods: [String] = []
        var firstPage: Data?
        var nextPage: Data?
        var atom: Data?
        var delay: Duration?

        func answer(_ path: String, _ answer: Answer) { lock.withLock { answers[path] = answer } }
        func requests(_ path: String) -> Int { lock.withLock { counts[path] ?? 0 } }
        var backendMethods: [String] { lock.withLock { methods } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let path = request.url.path
            if path.hasPrefix("/api/v1/channels/") || path.hasPrefix("/api/v1/playlists/") {
                let answer = lock.withLock {
                    counts[path, default: 0] += 1
                    methods.append(request.method)
                    return answers[path] ?? .status(404)
                }
                if let delay { try await Task.sleep(for: delay) }
                switch answer {
                case .throwing: throw URLError(.notConnectedToInternet)
                case .status(let s): return HTTPResponse(status: s, headers: [:], body: Data())
                case .lists(let videos, let playlists):
                    let body = try JSONSerialization.data(withJSONObject: [
                        "youtubeId": String(path.split(separator: "/").last!), "name": "N",
                        "excludedVideoIds": videos, "excludedPlaylistIds": playlists])
                    return HTTPResponse(status: 200, headers: [:], body: body)
                }
            }
            if path.hasSuffix("api/v1/titles") { return HTTPResponse(status: 200, headers: [:], body: Data("{}".utf8)) }
            if path.hasSuffix("index/streams") { return HTTPResponse(status: 202, headers: [:], body: Data()) }
            if path.hasSuffix("feeds/videos.xml"), let atom { return HTTPResponse(status: 200, headers: [:], body: atom) }
            if request.url.host() == "youtubei.googleapis.com" {
                let isNext = request.body.map { String(decoding: $0, as: UTF8.self).contains("\"continuation\"") } ?? false
                if let page = isNext ? nextPage : firstPage { return HTTPResponse(status: 200, headers: [:], body: page) }
            }
            throw URLError(.badServerResponse)
        }
    }

    private nonisolated final class MemoryStore: KeyValueStore, @unchecked Sendable {
        private let lock = NSLock()
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { lock.withLock { storage[key] } }
        func set(_ key: String, _ value: Data) { lock.withLock { storage[key] = value } }
    }

    static func fixture(_ name: String) -> Data {
        let url = URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
            .appending(path: "Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/\(name)")
        return try! Data(contentsOf: url)
    }

    private nonisolated final class Clock: @unchecked Sendable {
        private let lock = NSLock()
        private var value = Date(timeIntervalSince1970: 1_000_000)
        var now: Date { lock.withLock { value } }
        func advance(_ seconds: TimeInterval) { lock.withLock { value += seconds } }
    }

    private static func gate(_ backend: Backend, clock: Clock = Clock()) -> BackendAvailabilityGate {
        BackendAvailabilityGate(transport: backend, baseURL: base, now: { clock.now })
    }

    private static func client(_ backend: Backend, _ store: MemoryStore) -> BrowseClient {
        BrowseClient(transport: backend,
                     remoteConfigStore: RemoteConfigStore(transport: backend, keyValueStore: store, url: base),
                     sessionStore: SessionStore(monotonicClock: SystemClock(), wallClock: SystemClock(), keyValueStore: store),
                     locale: InnerTubeLocale(hl: "en", gl: "US"))
    }

    private static func source(_ backend: Backend, degraded: Bool = false) -> LiveBrowseSource {
        let store = MemoryStore()
        if degraded { DegradedLatch(store: store).until = Date().addingTimeInterval(3600) }
        return LiveBrowseSource(
            client: client(backend, store),
            atom: AtomFeedFetcher(transport: backend, keyValueStore: store),
            latch: DegradedLatch(store: store),
            index: IndexClient(transport: backend, baseURL: base, deviceId: DeviceId(value: "t")),
            titles: TitlesClient(transport: backend, baseURL: base, deviceId: DeviceId(value: "t")),
            gate: gate(backend),
            degradedHeader: nil)
    }

    // MARK: - Choke point 1: the fetch (BackendAvailabilityGate)

    @Test func theChannelRouteIsAGETWhoseListsAreParsed() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA", "vidB"], playlists: ["PL9"]))
        let lists = await Self.gate(backend).exclusions(channelId: "UC1", fresh: true)
        #expect(lists == Exclusions(videoIds: ["vidA", "vidB"], playlistIds: ["PL9"]))
        #expect(backend.backendMethods == ["GET"])
    }

    @Test func thePlaylistRouteIsAGETWhoseListIsParsed() async {
        let backend = Backend()
        backend.answer("/api/v1/playlists/PL1", .lists(videos: ["vidA"], playlists: []))
        #expect(await Self.gate(backend).exclusions(playlistId: "PL1", fresh: true) == Exclusions(videoIds: ["vidA"]))
    }

    /// Owner scope 2026-11-03: a PERSONAL channel or playlist answers its grantee only, so the GET is signed.
    @Test func theRequestCarriesTheBearerWhenSignedIn() async throws {
        struct SignedIn: AuthTokenProviding {
            func idToken(forceRefresh: Bool) async -> BearerToken? { BearerToken(value: "tok-abc", identity: "uid-A") }
        }
        let base = ScriptedTransport([.json(200, #"{"youtubeId":"UC1","excludedVideoIds":["vidA"],"excludedPlaylistIds":[]}"#)])
        let signed = AuthorizedTransport(base: base, apiHost: "app.fitrahtube.com", tokens: SignedIn(), onStatusEvent: { _, _ in })
        let gate = BackendAvailabilityGate(transport: signed, baseURL: Self.base)
        #expect(await gate.exclusions(channelId: "UC1", fresh: true)?.videoIds == ["vidA"])
        let request = try #require(base.sent.first)
        #expect(request.headers["Authorization"] == "Bearer tok-abc")
        #expect(request.url.path == "/api/v1/channels/UC1")
    }

    @Test func a410StillBlocks() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .status(410))
        backend.answer("/api/v1/playlists/PL1", .status(410))
        let gate = Self.gate(backend)
        #expect(await gate.exclusions(channelId: "UC1", fresh: true) == nil)
        #expect(await gate.exclusions(playlistId: "PL1", fresh: true) == nil)
    }

    @Test func a404IsAnUnregisteredSourceWithNothingExcluded() async {
        let lists = await Self.gate(Backend()).exclusions(playlistId: "PLchannelOwn", fresh: true)
        #expect(lists == Exclusions(registered: false))
    }

    @Test func aFailedFetchFailsOpenAndIsNotKeptAsTheAnswer() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .throwing)
        let clock = Clock()
        let gate = Self.gate(backend, clock: clock)
        // Open, nothing known: `registered: false`, so a channel's own playlist still asks its channels.
        #expect(await gate.exclusions(channelId: "UC1", fresh: true) == Exclusions(registered: false))
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA"], playlists: []))
        #expect(await gate.exclusions(channelId: "UC1", fresh: false)?.videoIds == [])   // inside the 30 s hold
        #expect(backend.requests("/api/v1/channels/UC1") == 1)
        clock.advance(31)   // past the failure's short hold: a later page asks again
        #expect(await gate.exclusions(channelId: "UC1", fresh: false)?.videoIds == ["vidA"])
    }

    @Test func aChannelsOwnPlaylistKeepsItsChannelsExclusionsWhenItsOwnFetchFails() async throws {
        let backend = Backend()
        backend.answer("/api/v1/playlists/PLown", .throwing)
        backend.answer("/api/v1/channels/UCowner", .lists(videos: ["vidA"], playlists: []))
        let gate = Self.gate(backend)
        let lists = try #require(await gate.exclusions(playlistId: "PLown", fresh: true))
        let items = [VideoItem(id: "vidA", title: "A", channelId: "UCowner"), VideoItem(id: "vidB", title: "B", channelId: "UCowner")]
        #expect(await gate.visible(items, in: lists, fresh: true).map(\.id) == ["vidB"])
    }

    @Test func aFailureAfterAGoodAnswerKeepsTheGoodAnswer() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA"], playlists: []))
        let gate = Self.gate(backend)
        _ = await gate.exclusions(channelId: "UC1", fresh: true)
        backend.answer("/api/v1/channels/UC1", .status(503))
        #expect(await gate.exclusions(channelId: "UC1", fresh: true)?.videoIds == ["vidA"])
    }

    @Test func aLaterPageReusesTheAnswerAndTheNextOpenAsksAgain() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA"], playlists: []))
        let clock = Clock()
        let gate = Self.gate(backend, clock: clock)
        _ = await gate.exclusions(channelId: "UC1", fresh: true)
        _ = await gate.exclusions(channelId: "UC1", fresh: false)
        #expect(backend.requests("/api/v1/channels/UC1") == 1)
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA", "vidB"], playlists: []))
        #expect(await gate.exclusions(channelId: "UC1", fresh: true)?.videoIds == ["vidA", "vidB"])
        clock.advance(301)  // a memo older than five minutes is asked again even by a later page
        _ = await gate.exclusions(channelId: "UC1", fresh: false)
        #expect(backend.requests("/api/v1/channels/UC1") == 3)
    }

    @Test func theHeaderAndTheVideosTabShareOneFetch() async {
        let backend = Backend()
        backend.delay = .milliseconds(500)
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA"], playlists: []))
        let gate = Self.gate(backend)
        async let header = gate.exclusions(channelId: "UC1", fresh: true)
        async let videos = gate.exclusions(channelId: "UC1", fresh: true)
        #expect(await header == videos)
        #expect(backend.requests("/api/v1/channels/UC1") == 1)
    }

    /// The same, in production order: `ChannelDetailViewModel.load()` starts the header and the Videos tab together.
    @Test func aChannelOpenSendsOneExclusionFetch() async throws {
        let backend = Backend()
        backend.delay = .milliseconds(500)
        backend.firstPage = Self.fixture("browse-channel-videos-page1.json")
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["R6YoAYNxAcE"], playlists: []))
        let source = Self.source(backend)
        async let header = try? source.channelHeader("UC1")
        async let videos = source.channelVideos("UC1", continuation: nil)
        _ = await header
        #expect(!(try await videos).items.map(\.id).contains("R6YoAYNxAcE"))
        #expect(backend.requests("/api/v1/channels/UC1") == 1)
    }

    // MARK: - Choke point 2: LiveBrowseSource

    @Test func anExcludedVideoLeavesTheVideosTabOnTheNextOpen() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-channel-videos-page1.json")
        let source = Self.source(backend)
        let before = try await source.channelVideos("UC1", continuation: nil).items.map(\.id)
        let excluded = try #require(before.first)
        backend.answer("/api/v1/channels/UC1", .lists(videos: [excluded], playlists: []))
        let after = try await source.channelVideos("UC1", continuation: nil).items.map(\.id)
        #expect(!after.contains(excluded))
        #expect(after.count == before.count - 1)
    }

    @Test func anExcludedVideoLeavesTheDegradedAtomFeed() async throws {
        let backend = Backend()
        backend.atom = Self.fixture("atom-channel.xml")
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["R6YoAYNxAcE"], playlists: []))
        let ids = try await Self.source(backend, degraded: true).channelVideos("UC1", continuation: nil).items.map(\.id)
        #expect(!ids.isEmpty)
        #expect(!ids.contains("R6YoAYNxAcE"))
    }

    @Test func anExcludedShortLeavesTheShortsTab() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-channel-shorts.json")
        let source = Self.source(backend)
        let excluded = try #require(try await source.channelTab("UC1", tab: .shorts, continuation: nil).items.first?.id)
        backend.answer("/api/v1/channels/UC1", .lists(videos: [excluded], playlists: []))
        let after = try await source.channelTab("UC1", tab: .shorts, continuation: nil).items.map(\.id)
        #expect(!after.isEmpty)
        #expect(!after.contains(excluded))
    }

    @Test func anExcludedPlaylistLeavesThePlaylistsTab() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-channel-playlists.json")
        let source = Self.source(backend)
        let excluded = try #require(try await source.channelPlaylists("UC1", continuation: nil).items.first?.id)
        backend.answer("/api/v1/channels/UC1", .lists(videos: [], playlists: [excluded]))
        let after = try await source.channelPlaylists("UC1", continuation: nil).items.map(\.id)
        #expect(!after.isEmpty)
        #expect(!after.contains(excluded))
    }

    @Test func anExcludedVideoLeavesARegisteredPlaylist() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        backend.answer("/api/v1/playlists/PL1", .lists(videos: [], playlists: []))
        let source = Self.source(backend)
        let excluded = try #require(try await source.playlistItems("PL1", continuation: nil).items.first?.id)
        backend.answer("/api/v1/playlists/PL1", .lists(videos: [excluded], playlists: []))
        let after = try await source.playlistItems("PL1", continuation: nil).items.map(\.id)
        #expect(!after.isEmpty)
        #expect(!after.contains(excluded))
    }

    /// Choke point 5: a channel's own playlist is not in the registry (404) and inherits its channel's exclusions.
    @Test func aChannelsOwnPlaylistInheritsTheChannelsExclusions() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        let source = Self.source(backend)
        let first = try #require(try await source.playlistItems("PLown", continuation: nil).items.first)
        let channelId = try #require(first.channelId)
        backend.answer("/api/v1/channels/\(channelId)", .lists(videos: [first.id], playlists: []))
        let after = try await source.playlistItems("PLown", continuation: nil).items.map(\.id)
        #expect(!after.isEmpty)
        #expect(!after.contains(first.id))
    }

    /// Choke point 6: a page the exclusions empty is skipped, never returned as an empty page with a cursor
    /// (a first load would show the empty state; an append would stall).
    @Test func aPageFilteredToNothingLeadsToTheNextPage() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-channel-videos-page1.json")
        backend.nextPage = Self.fixture("browse-channel-videos-page2.json")
        let source = Self.source(backend)
        let page1 = try await source.channelVideos("UC1", continuation: nil)
        try #require(page1.nextContinuation != nil)
        backend.answer("/api/v1/channels/UC1", .lists(videos: page1.items.map(\.id), playlists: []))
        let shown = try await source.channelVideos("UC1", continuation: nil)
        #expect(!shown.items.isEmpty)
        #expect(Set(shown.items.map(\.id)).isDisjoint(with: page1.items.map(\.id)))
    }

    // MARK: - Choke point 3: the player queue

    @Test func anExcludedVideoLeavesThePlayerQueue() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        backend.answer("/api/v1/playlists/PL1", .lists(videos: [], playlists: []))
        let store = MemoryStore()
        let gate = Self.gate(backend)
        let queue = LivePlaylistQueueSource(client: Self.client(backend, store),
                                            titles: TitlesClient(transport: backend, baseURL: Self.base, deviceId: DeviceId(value: "t")),
                                            gate: gate)
        let excluded = try #require(try await queue.page(playlistId: "PL1", continuation: nil).items.first?.id)
        backend.answer("/api/v1/playlists/PL1", .lists(videos: [excluded], playlists: []))
        _ = await gate.exclusions(playlistId: "PL1", fresh: true)   // the playlist screen's open
        let after = try await queue.page(playlistId: "PL1", continuation: nil).items.map(\.id)
        #expect(!after.isEmpty)
        #expect(!after.contains(excluded))

        // Cold memo (the queue before any playlist screen, e.g. a deep link): it fetches the lists itself.
        let cold = LivePlaylistQueueSource(client: Self.client(backend, MemoryStore()),
                                           titles: TitlesClient(transport: backend, baseURL: Self.base, deviceId: DeviceId(value: "t")),
                                           gate: Self.gate(backend))
        #expect(!(try await cold.page(playlistId: "PL1", continuation: nil)).items.map(\.id).contains(excluded))
    }

    // MARK: - Freshness: newly approved content after a foreground

    @Test func aReturnAfterFiveIdleMinutesReloadsTheCatalog() {
        let now = Date(timeIntervalSince1970: 10_000)
        #expect(FitrahTubeApp.isCatalogReloadDue(backgroundedAt: now.addingTimeInterval(-300), now: now))
        #expect(!FitrahTubeApp.isCatalogReloadDue(backgroundedAt: now.addingTimeInterval(-299), now: now))
        #expect(!FitrahTubeApp.isCatalogReloadDue(backgroundedAt: nil, now: now))
    }
}
