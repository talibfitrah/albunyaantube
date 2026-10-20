import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Owner issue 1(b): `GET /api/v1/titles` swaps the original titles into on-device browse rows; every
/// failure is silent and quiets the client for a minute. Synthetic ids only.
@Suite(.perTest)
struct TitlesClientTests {
    private static let baseURL = URL(string: "https://app.fitrahtube.com/")!
    private static let original = "زبدة النور: الدرس الأول"

    private nonisolated final class RequestLog: @unchecked Sendable {
        private let lock = NSLock()
        private var _requests: [HTTPRequest] = []
        var requests: [HTTPRequest] { lock.withLock { _requests } }
        func append(_ request: HTTPRequest) { lock.withLock { _requests.append(request) } }
    }

    private nonisolated final class TestClock: @unchecked Sendable {
        private let lock = NSLock()
        private var _now = ContinuousClock.now
        var now: ContinuousClock.Instant { lock.withLock { _now } }
        func advance(_ seconds: Int) { lock.withLock { _now += .seconds(seconds) } }
    }

    private struct StubTransport: HTTPTransport {
        var status = 200
        var body = Data("{}".utf8)
        var error: Error?
        var delay: Duration?
        /// A request asking for any of these is answered 503 (one failed chunk).
        var failIds: Set<String> = []
        let log: RequestLog

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            log.append(request)
            if let delay { try await Task.sleep(for: delay) }
            if let error { throw error }
            if !failIds.isDisjoint(with: TitlesClientTests.ids(request)) { return HTTPResponse(status: 503, headers: [:], body: Data()) }
            return HTTPResponse(status: status, headers: [:], body: body)
        }
    }

    private static func lessons(_ count: Int) -> [VideoItem] {
        (0..<count).map { VideoItem(id: String(format: "lesson%05d", $0), title: "L\($0)") }
    }

    private static func waitFor(_ condition: () -> Bool) async throws {
        let deadline = ContinuousClock.now + .seconds(3)
        while !condition(), ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(10)) }
    }

    private static func client(_ transport: StubTransport, clock: TestClock = TestClock(),
                               timeout: Duration = .milliseconds(1500)) -> TitlesClient {
        TitlesClient(transport: transport, baseURL: baseURL, deviceId: DeviceId(value: "device-123"),
                     timeout: timeout, now: { clock.now })
    }

    private static let items = [
        VideoItem(id: "lesson00001", title: "Lesson 1", channelId: "UC1"),
        VideoItem(id: "lesson00002", title: "Lesson 2", channelId: "UC1"),
    ]

    nonisolated private static func ids(_ request: HTTPRequest) -> [String] {
        URLComponents(url: request.url, resolvingAgainstBaseURL: false)?.queryItems?
            .first { $0.name == "ids" }?.value?.split(separator: ",").map(String.init) ?? []
    }

    @Test func knownIdsTakeTheOriginalTitleAndTheRestKeepTheirs() async {
        let body = try! JSONEncoder().encode(["lesson00001": Self.original])
        let out = await Self.client(StubTransport(body: body, log: RequestLog())).retitle(Self.items)
        #expect(out.map(\.title) == [Self.original, "Lesson 2"])
        #expect(out.map(\.id) == Self.items.map(\.id))
        #expect(out[0].channelId == "UC1")
    }

    @Test func asksFiftyDistinctIdsPerGetAndNeverABadOne() async {
        let distinct = Self.lessons(120)
        let items = distinct + distinct.prefix(10) + [VideoItem(id: "PLabc", title: "P"), VideoItem(id: "short", title: "S")]
        let log = RequestLog()
        _ = await Self.client(StubTransport(log: log)).retitle(items)
        #expect(log.requests.map { Self.ids($0).count } == [50, 50, 20])
        #expect(Set(log.requests.flatMap(Self.ids)) == Set(distinct.map(\.id)))
        for request in log.requests {
            #expect(request.method == "GET")
            #expect(request.url.path == "/api/v1/titles")
            #expect(request.headers["Authorization"] == nil)
            #expect(request.headers["X-Device-Id"] == "device-123") // as `PublicHeaders` and `IndexClient` send
        }
    }

    @Test func aFailureKeepsTheListTitlesAndGoesQuietForAMinute() async {
        let log = RequestLog()
        let clock = TestClock()
        let client = Self.client(StubTransport(error: URLError(.notConnectedToInternet), log: log), clock: clock)
        #expect(await client.retitle(Self.items) == Self.items)
        clock.advance(59)
        _ = await client.retitle(Self.items)
        #expect(log.requests.count == 1)
        clock.advance(2)
        _ = await client.retitle(Self.items)
        #expect(log.requests.count == 2)
    }

    @Test func aNon200OrABadBodyIsAFailure() async {
        let titled = try! JSONEncoder().encode(["lesson00001": Self.original])
        for (status, body) in [(400, titled), (503, titled), (200, Data("not json".utf8))] {
            let log = RequestLog()
            let clock = TestClock()
            let client = Self.client(StubTransport(status: status, body: body, log: log), clock: clock)
            #expect(await client.retitle(Self.items) == Self.items, "status \(status)")
            clock.advance(1)
            _ = await client.retitle(Self.items)
            #expect(log.requests.count == 1, "status \(status) did not go quiet")
        }
    }

    @Test(.timeLimit(.minutes(1))) func aSlowServerIsAbandonedAtTheTimeout() async {
        let log = RequestLog()
        let client = Self.client(StubTransport(delay: .seconds(5), log: log), timeout: .milliseconds(100))
        let start = ContinuousClock.now
        #expect(await client.retitle(Self.items) == Self.items)
        #expect(ContinuousClock.now - start < .seconds(1))
        _ = await client.retitle(Self.items)
        #expect(log.requests.count == 1, "a timeout did not go quiet")
    }
    /// Fix round 1, B5: backing out of a screen mid-ask cancels the caller. That is not the server failing, so the
    /// next screen still asks.
    @Test(.timeLimit(.minutes(1))) func aCancelledCallerDoesNotGoQuiet() async throws {
        let log = RequestLog()
        let client = Self.client(StubTransport(delay: .seconds(5), log: log), timeout: .seconds(10))
        let first = Task { await client.retitle(Self.items) }
        try await Self.waitFor { log.requests.count == 1 }
        first.cancel()
        #expect(await first.value == Self.items)
        let second = Task { await client.retitle(Self.items) }
        try await Self.waitFor { log.requests.count == 2 }
        second.cancel()
        #expect(log.requests.count == 2, "a cancelled caller quieted the client")
    }

    /// Fix round 1, B6: 60 ids are a chunk of 50 that answers and a chunk of 10 that fails. The answer is kept, and one
    /// failed chunk of two does not quiet the client.
    @Test func aFailedChunkKeepsTheChunksThatAnswered() async {
        let body = try! JSONEncoder().encode(["lesson00000": Self.original])
        let log = RequestLog()
        let client = Self.client(StubTransport(body: body, failIds: ["lesson00055"], log: log))
        #expect(await client.retitle(Self.lessons(60)).first?.title == Self.original)
        #expect(log.requests.count == 2)
        _ = await client.retitle(Self.lessons(60))
        #expect(log.requests.count == 4, "one failed chunk of two quieted the client")
    }

    /// Fix round 1, B6: three 300 ms chunks fit a 700 ms cap only when asked at once (one after another is 900 ms).
    @Test(.timeLimit(.minutes(1))) func theChunksAreAskedTogetherUnderOneCap() async {
        let body = try! JSONEncoder().encode(["lesson00000": Self.original])
        let client = Self.client(StubTransport(body: body, delay: .milliseconds(300), log: RequestLog()), timeout: .milliseconds(700))
        #expect(await client.retitle(Self.lessons(120)).first?.title == Self.original)
    }

    // MARK: - Through the real LiveBrowseSource (fix round 1, B8: the channel paths)

    /// One InnerTubeKit browse fixture for every browse POST; `/titles` answers the first id it is asked for.
    private nonisolated final class FixtureTransport: HTTPTransport, @unchecked Sendable {
        static let original = "العنوان الأصلي"
        private let fixture: Data

        init(_ name: String) {
            fixture = try! Data(contentsOf: URL(fileURLWithPath: #filePath).deletingLastPathComponent().deletingLastPathComponent()
                .appending(path: "Packages/InnerTubeKit/Tests/InnerTubeKitTests/Fixtures/\(name)"))
        }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            if request.url.host() == "youtubei.googleapis.com" { return HTTPResponse(status: 200, headers: [:], body: fixture) }
            if request.url.path.hasSuffix("api/v1/titles") {
                let first = TitlesClientTests.ids(request).first
                return HTTPResponse(status: 200, headers: [:], body: try JSONEncoder().encode(first.map { [$0: Self.original] } ?? [:]))
            }
            if request.url.path.hasSuffix("index/streams") { return HTTPResponse(status: 200, headers: [:], body: Data()) }
            throw URLError(.badServerResponse) // remote config: the bundled default stands
        }
    }

    private nonisolated final class InMemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
        private let lock = NSLock()
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { lock.withLock { storage[key] } }
        func set(_ key: String, _ value: Data) { lock.withLock { storage[key] = value } }
    }

    private static func liveSource(_ fixture: String) -> LiveBrowseSource {
        let transport = FixtureTransport(fixture)
        let store = InMemoryKeyValueStore()
        return LiveBrowseSource(
            client: BrowseClient(transport: transport,
                                 remoteConfigStore: RemoteConfigStore(transport: transport, keyValueStore: store, url: baseURL),
                                 sessionStore: SessionStore(monotonicClock: SystemClock(), wallClock: SystemClock(), keyValueStore: store),
                                 locale: InnerTubeLocale(hl: "en", gl: "US")),
            atom: AtomFeedFetcher(transport: transport, keyValueStore: store),
            latch: DegradedLatch(store: store),
            index: IndexClient(transport: transport, baseURL: baseURL, deviceId: DeviceId(value: "t")),
            titles: TitlesClient(transport: transport, baseURL: baseURL, deviceId: DeviceId(value: "t")),
            gate: BackendAvailabilityGate(transport: transport, baseURL: baseURL),
            degradedHeader: nil)
    }

    @Test func aChannelsVideosShowTheOriginalTitlesTheServerKnows() async throws {
        let page = try await Self.liveSource("browse-channel-videos-page1.json").channelVideos("UCtestchannel0000000000", continuation: nil)
        #expect(page.items.count > 1)
        #expect(page.items.first?.title == FixtureTransport.original)
        #expect(page.items.dropFirst().allSatisfy { $0.title != FixtureTransport.original })
    }

    /// Stage 6 I-M9: a queue page carries the list's own titles, so the player's deep scan never waits on `/titles` per
    /// page; `retitle` swaps the originals in for the list it is given, keeping its order.
    @Test func aQueuePageIsUntitledUntilRetitled() async throws {
        let transport = FixtureTransport("browse-playlist.json")
        let store = InMemoryKeyValueStore()
        let source = LivePlaylistQueueSource(
            client: BrowseClient(transport: transport,
                                 remoteConfigStore: RemoteConfigStore(transport: transport, keyValueStore: store, url: Self.baseURL),
                                 sessionStore: SessionStore(monotonicClock: SystemClock(), wallClock: SystemClock(), keyValueStore: store),
                                 locale: InnerTubeLocale(hl: "en", gl: "US")),
            titles: TitlesClient(transport: transport, baseURL: Self.baseURL, deviceId: DeviceId(value: "t")))
        let page = try await source.page(playlistId: "PLtest", continuation: nil)
        #expect(page.items.count > 1)
        #expect(page.items.allSatisfy { $0.title != FixtureTransport.original })
        let titled = await source.retitle(page.items)
        #expect(titled.first?.title == FixtureTransport.original)
        #expect(titled.map(\.id) == page.items.map(\.id))
    }

    @Test func aChannelTabShowsTheOriginalTitlesTheServerKnows() async throws {
        let page = try await Self.liveSource("browse-channel-shorts.json").channelTab("UCtestchannel0000000000", tab: .shorts, continuation: nil)
        #expect(page.items.first?.title == FixtureTransport.original)
    }
}
