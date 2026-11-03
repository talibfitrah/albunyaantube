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
        enum Answer { case lists(videos: [String], playlists: [String]), status(Int), body(String), throwing }
        private let lock = NSLock()
        private var answers: [String: Answer] = [:]
        private var counts: [String: Int] = [:]
        private var methods: [String] = []
        var firstPage: Data?
        var nextPage: Data?
        var atom: Data?
        var delay: Duration?

        private var inFlight = 0
        private var peakInFlight = 0

        func answer(_ path: String, _ answer: Answer) { lock.withLock { answers[path] = answer } }
        func requests(_ path: String) -> Int { lock.withLock { counts[path] ?? 0 } }
        var backendMethods: [String] { lock.withLock { methods } }
        /// The most channel/playlist GETs that were in flight at once.
        var peak: Int { lock.withLock { peakInFlight } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let path = request.url.path
            if path.hasPrefix("/api/v1/channels/") || path.hasPrefix("/api/v1/playlists/") {
                let answer = lock.withLock {
                    counts[path, default: 0] += 1
                    methods.append(request.method)
                    inFlight += 1
                    peakInFlight = max(peakInFlight, inFlight)
                    return answers[path] ?? .status(404)
                }
                defer { lock.withLock { inFlight -= 1 } }
                if let delay { try await Task.sleep(for: delay) }
                switch answer {
                case .throwing: throw URLError(.notConnectedToInternet)
                case .status(let s): return HTTPResponse(status: s, headers: [:], body: Data())
                case .body(let text): return HTTPResponse(status: 200, headers: [:], body: Data(text.utf8))
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

    private static func gate(_ backend: Backend, clock: Clock = Clock(),
                             owner: @escaping @Sendable () async -> String? = { nil }) -> BackendAvailabilityGate {
        BackendAvailabilityGate(transport: backend, baseURL: base, now: { clock.now }, owner: owner)
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

    @Test func a200WithoutTheListsIsRegisteredWithNothingExcludedAndGarbageFailsOpen() async {
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .body(#"{"youtubeId":"UC1"}"#))
        backend.answer("/api/v1/channels/UC2", .body("not json"))
        let gate = Self.gate(backend)
        #expect(await gate.exclusions(channelId: "UC1", fresh: true) == Exclusions())
        #expect(await gate.exclusions(channelId: "UC2", fresh: true) == Exclusions(registered: false))
    }

    /// A grantee's 503 (the backend could not check the grant) fails open and is not kept: the next open asks again.
    @Test func a503FailsOpenAndTheNextOpenAsksAgain() async {
        let backend = Backend()
        backend.answer("/api/v1/playlists/PLmine", .status(503))
        let gate = Self.gate(backend)
        #expect(await gate.exclusions(playlistId: "PLmine", fresh: true) == Exclusions(registered: false))
        backend.answer("/api/v1/playlists/PLmine", .lists(videos: ["vidA"], playlists: []))
        #expect(await gate.exclusions(playlistId: "PLmine", fresh: true)?.videoIds == ["vidA"])
    }

    @Test func aPageListEndsAtAnEmptyLastPageOrARepeatedCursor() async throws {
        let ended = try await Self.walkEmpty(from: "c0") { _, n in n < 3 ? "c\(n)" : nil }
        #expect(ended.asked == ["c0", "c1", "c2"])
        #expect(ended.page.items.isEmpty && ended.page.nextContinuation == nil)
        let repeated = try await Self.walkEmpty(from: nil) { _, _ in "same" }
        #expect(repeated.asked == [nil, "same"])
        #expect(repeated.page.nextContinuation == nil)   // an empty page with a cursor would have the list ask again forever
        let cycle = try await Self.walkEmpty(from: nil) { cursor, _ in cursor == "A" ? "B" : "A" }
        #expect(cycle.asked == [nil, "A", "B"])
        let endless = try await Self.walkEmpty(from: nil) { _, n in "c\(n)" }
        #expect(endless.asked.count == 21)   // the first page plus at most 20 skipped
        #expect(endless.page.nextContinuation == nil)
    }

    /// `skippingEmpty` over pages that are all empty; `next` names each page's cursor from the cursor asked and
    /// the number of pages asked so far.
    private static func walkEmpty(from start: String?, next: @escaping @Sendable (String?, Int) -> String?)
        async throws -> (asked: [String?], page: BrowsePage<VideoItem>) {
        nonisolated final class Asked: @unchecked Sendable { let lock = NSLock(); var all: [String?] = [] }
        let asked = Asked()
        let page = try await BrowsePage<VideoItem>.skippingEmpty(from: start) { @Sendable cursor in
            let n = asked.lock.withLock { asked.all.append(cursor); return asked.all.count }
            return BrowsePage(items: [], nextContinuation: next(cursor, n))
        }
        return (asked.lock.withLock { asked.all }, page)
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
        #expect(await gate.visible(items, in: lists, playlistId: "PLown", fresh: true)?.map(\.id) == ["vidB"])
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

    /// A pulled (410) playlist queues nothing, and a later page of a list pulled mid-scroll ends it.
    @Test func a410PlaylistQueuesNothingAndEndsALaterPage() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        backend.nextPage = Self.fixture("browse-playlist.json")
        backend.answer("/api/v1/playlists/PL1", .status(410))
        let queue = LivePlaylistQueueSource(client: Self.client(backend, MemoryStore()),
                                            titles: TitlesClient(transport: backend, baseURL: Self.base, deviceId: DeviceId(value: "t")),
                                            gate: Self.gate(backend))
        let queued = try await queue.page(playlistId: "PL1", continuation: nil)
        #expect(queued.items.isEmpty && queued.continuation == nil)
        let later = try await Self.source(backend).playlistItems("PL1", continuation: "next")
        #expect(later.items.isEmpty && later.nextContinuation == nil)
    }

    // MARK: - Bounds, accounts and 410s (review 2026-11-03)

    /// A mixed-uploader playlist the backend does not list asks its channels a few at a time and only so many per
    /// page, the channels with the most items first.
    @Test func aMixedPlaylistAsksABoundedNumberOfChannelsAFewAtATime() async throws {
        let backend = Backend()
        backend.delay = .milliseconds(20)
        backend.answer("/api/v1/channels/UCmain", .lists(videos: ["main-1"], playlists: []))
        backend.answer("/api/v1/channels/UCgone", .status(410))
        let items = (0..<30).map { VideoItem(id: "one-\($0)", title: "T", channelId: "UCone\($0)") }
            + (0..<3).map { VideoItem(id: "main-\($0)", title: "T", channelId: "UCmain") }
            + (0..<2).map { VideoItem(id: "gone-\($0)", title: "T", channelId: "UCgone") }
        let gate = Self.gate(backend)

        let shown = try #require(await gate.visible(items, in: Exclusions(registered: false), playlistId: "PLmixed", fresh: true))

        let asked = (["UCmain", "UCgone"] + (0..<30).map { "UCone\($0)" }).map { backend.requests("/api/v1/channels/\($0)") }
        #expect(asked.reduce(0, +) <= BackendAvailabilityGate.maxChannelAsks)
        #expect(backend.peak <= BackendAvailabilityGate.maxConcurrentAsks)
        let ids = shown.map(\.id)
        #expect(!ids.contains("main-1"))                          // the channels with the most items were asked
        #expect(!ids.contains("gone-0") && !ids.contains("gone-1"))  // a 410 channel's items are gone
        #expect(ids.contains("one-0") && ids.contains("one-29"))      // asked (404) or past the cap: still shown
    }

    /// Each account has its own answers: a PERSONAL item's lists never carry over to the next account or a guest.
    @Test func anotherAccountNeverReadsTheLastAccountsAnswers() async {
        nonisolated final class Owner: @unchecked Sendable {
            let lock = NSLock(); var uid: String? = "uid-A"; var switchTo: String?
            /// The signed-in uid; a pending `switchTo` signs in right after this read (mid-fetch, for the ask).
            var value: String? {
                lock.withLock {
                    defer { if let next = switchTo { uid = next; switchTo = nil } }
                    return uid
                }
            }
            func set(_ new: String?) { lock.withLock { uid = new } }
        }
        let owner = Owner()
        let backend = Backend()
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidA"], playlists: []))
        let gate = Self.gate(backend, owner: { owner.value })
        #expect(await gate.exclusions(channelId: "UC1", fresh: true)?.videoIds == ["vidA"])

        owner.set("uid-B")
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidB"], playlists: []))
        #expect(await gate.exclusions(channelId: "UC1", fresh: false)?.videoIds == ["vidB"])

        owner.set(nil)   // signed out: the unsigned GET reads a PERSONAL item as missing
        backend.answer("/api/v1/channels/UC1", .status(404))
        #expect(await gate.exclusions(channelId: "UC1", fresh: false) == Exclusions(registered: false))

        // The account switches while a fetch is in flight: its answer, signed for the new account, is not kept
        // under the old one.
        owner.set("uid-C")
        owner.lock.withLock { owner.switchTo = "uid-D" }
        backend.answer("/api/v1/channels/UC1", .lists(videos: ["vidD"], playlists: []))
        _ = await gate.exclusions(channelId: "UC1", fresh: true)
        owner.set("uid-C")
        #expect(await gate.exclusions(channelId: "UC1", fresh: false) == Exclusions(registered: false))
    }

    /// A 410 means gone on every channel list: the first page shows it, a later page ends the list.
    @Test func a410ChannelIsGoneOnEveryTab() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-channel-videos-page1.json")
        backend.nextPage = Self.fixture("browse-channel-videos-page2.json")
        backend.answer("/api/v1/channels/UC1", .status(410))
        let source = Self.source(backend)
        await #expect(throws: BrowseSourceError.unavailable) { try await source.channelVideos("UC1", continuation: nil) }
        let later = try await source.channelVideos("UC1", continuation: "next")
        #expect(later.items.isEmpty && later.nextContinuation == nil)
        await #expect(throws: BrowseSourceError.unavailable) { try await source.channelTab("UC1", tab: .shorts, continuation: nil) }
        let shorts = try await source.channelTab("UC1", tab: .shorts, continuation: "next")
        #expect(shorts.items.isEmpty && shorts.nextContinuation == nil)

        backend.firstPage = Self.fixture("browse-channel-playlists.json")
        backend.nextPage = Self.fixture("browse-channel-playlists-page2.json")
        await #expect(throws: BrowseSourceError.unavailable) { try await source.channelPlaylists("UC1", continuation: nil) }
        let playlists = try await source.channelPlaylists("UC1", continuation: "next")
        #expect(playlists.items.isEmpty && playlists.nextContinuation == nil)

        backend.atom = Self.fixture("atom-channel.xml")
        await #expect(throws: BrowseSourceError.unavailable) {
            try await Self.source(backend, degraded: true).channelVideos("UC1", continuation: nil)
        }
    }

    /// A playlist the backend does not list, excluded from its own channel, is gone when a deep link opens it, and
    /// the player queues nothing from it.
    @Test func aPlaylistExcludedFromItsChannelIsGoneFromADeepLink() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        backend.nextPage = Self.fixture("browse-playlist.json")
        let owner = try #require(try await Self.source(backend).playlistItems("PLown", continuation: nil).items.first?.channelId)
        backend.answer("/api/v1/channels/\(owner)", .lists(videos: [], playlists: ["PLown"]))

        let source = Self.source(backend)
        await #expect(throws: BrowseSourceError.unavailable) { try await source.playlistItems("PLown", continuation: nil) }
        let later = try await source.playlistItems("PLown", continuation: "next")
        #expect(later.items.isEmpty && later.nextContinuation == nil)
        let queue = LivePlaylistQueueSource(client: Self.client(backend, MemoryStore()),
                                            titles: TitlesClient(transport: backend, baseURL: Self.base, deviceId: DeviceId(value: "t")),
                                            gate: Self.gate(backend))
        let queued = try await queue.page(playlistId: "PLown", continuation: nil)
        #expect(queued.items.isEmpty && queued.continuation == nil)
    }

    /// A playlist whose every item comes from a 410 channel is gone too, not an empty list walked page by page.
    @Test func aPlaylistOfOnlyPulledChannelsIsGone() async throws {
        let backend = Backend()
        backend.firstPage = Self.fixture("browse-playlist.json")
        backend.nextPage = Self.fixture("browse-playlist.json")
        let channels = Set(try await Self.source(backend).playlistItems("PLown", continuation: nil).items.compactMap(\.channelId))
        try #require(!channels.isEmpty && channels.count <= BackendAvailabilityGate.maxChannelAsks)
        for channel in channels { backend.answer("/api/v1/channels/\(channel)", .status(410)) }

        let source = Self.source(backend)
        await #expect(throws: BrowseSourceError.unavailable) { try await source.playlistItems("PLown", continuation: nil) }
    }

    // MARK: - Freshness: newly approved content after a foreground

    @Test func aReturnAfterFiveIdleMinutesReloadsTheCatalog() {
        let now = Date(timeIntervalSince1970: 10_000)
        #expect(FitrahTubeApp.isCatalogReloadDue(backgroundedAt: now.addingTimeInterval(-300), now: now))
        #expect(!FitrahTubeApp.isCatalogReloadDue(backgroundedAt: now.addingTimeInterval(-299), now: now))
        #expect(!FitrahTubeApp.isCatalogReloadDue(backgroundedAt: nil, now: now))
    }
}
