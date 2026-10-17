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
        private var _now = Date(timeIntervalSince1970: 1_000_000)
        var now: Date { lock.withLock { _now } }
        func advance(_ seconds: TimeInterval) { lock.withLock { _now += seconds } }
    }

    private struct StubTransport: HTTPTransport {
        var status = 200
        var body = Data("{}".utf8)
        var error: Error?
        var delay: Duration?
        let log: RequestLog

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            log.append(request)
            if let delay { try await Task.sleep(for: delay) }
            if let error { throw error }
            return HTTPResponse(status: status, headers: [:], body: body)
        }
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

    private static func ids(_ request: HTTPRequest) -> [String] {
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
        let distinct = (0..<120).map { VideoItem(id: String(format: "lesson%05d", $0), title: "L\($0)") }
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
}
