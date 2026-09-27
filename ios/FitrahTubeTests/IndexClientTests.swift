import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Plan C Task 2, reconciliation note 6: the hand-written `POST /api/v1/index/streams` push.
@Suite(.perTest)
struct IndexClientTests {
    private static let baseURL = URL(string: "https://app.fitrahtube.com/")!
    private static let deviceId = DeviceId(value: "device-123")

    private nonisolated final class RequestLog: @unchecked Sendable {
        private let lock = NSLock()
        private var _requests: [HTTPRequest] = []
        var requests: [HTTPRequest] { lock.withLock { _requests } }
        func append(_ request: HTTPRequest) { lock.withLock { _requests.append(request) } }
    }

    private struct StubTransport: HTTPTransport {
        var status = 200
        var error: Error?
        let log: RequestLog

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            log.append(request)
            if let error { throw error }
            return HTTPResponse(status: status, headers: [:], body: Data())
        }
    }

    private static func videos(_ count: Int) -> [VideoItem] {
        (0..<count).map { VideoItem(id: "v\($0)", title: "Lecture \($0)", channelId: "UC1", durationSeconds: 60) }
    }

    private static func client(_ transport: StubTransport) -> IndexClient {
        IndexClient(transport: transport, baseURL: baseURL, deviceId: deviceId)
    }

    private static func body(_ request: HTTPRequest) throws -> [String: Any] {
        let data = try #require(request.body)
        return try #require(JSONSerialization.jsonObject(with: data) as? [String: Any])
    }

    @Test func pushBatchesAtFiftyItems() async throws {
        // IndexController.java:67-70 silently truncates above 50 while dto/IndexStreamsRequest.java:12
        // rejects above 60 outright -- 50 is the only size that is neither truncated nor 400'd.
        let log = RequestLog()
        await Self.client(StubTransport(log: log)).push(sourceType: .channel, sourceId: "UC1", items: Self.videos(120))
        let sizes = try log.requests.map { try (Self.body($0)["items"] as? [Any])?.count }
        #expect(sizes == [50, 50, 20])
        let first = try Self.body(log.requests[0])
        #expect(first["sourceType"] as? String == "CHANNEL")
        #expect(first["sourceId"] as? String == "UC1")
        let item = try #require((first["items"] as? [[String: Any]])?.first)
        #expect(item["id"] as? String == "v0")
        #expect(item["name"] as? String == "Lecture 0")
        #expect(item["channelId"] as? String == "UC1")
        #expect(item["duration"] as? Int == 60)
        #expect(item["streamType"] as? String == "VIDEO")
    }

    @Test func pushSendsTheDeviceIdHeader() async throws {
        let log = RequestLog()
        await Self.client(StubTransport(log: log)).push(sourceType: .playlist, sourceId: "PL1", items: Self.videos(1))
        let request = try #require(log.requests.first)
        #expect(request.method == "POST")
        #expect(request.url.path == "/api/v1/index/streams")
        #expect(request.headers["X-Device-Id"] == "device-123")
        #expect(request.headers["Content-Type"] == "application/json")
    }

    @Test func a429IsSwallowedNotRetried() async {
        // IndexController.java:74-77 returns 429 for a byte-identical repeat within 30 s. Android logs
        // and drops (IndexRepository.kt:23); a retry would just re-trip the same dedupe key.
        let log = RequestLog()
        await Self.client(StubTransport(status: 429, log: log)).push(sourceType: .channel, sourceId: "UC1", items: Self.videos(3))
        #expect(log.requests.count == 1)
    }

    @Test func pushNeverThrowsAndNeverBlocksTheCaller() async {
        let log = RequestLog()
        await Self.client(StubTransport(error: URLError(.notConnectedToInternet), log: log))
            .push(sourceType: .channel, sourceId: "UC1", items: Self.videos(60))
        // Both batches attempted, neither failure propagated -- `push` is not `throws`.
        #expect(log.requests.count == 2)
    }

    @Test func anEmptyItemListSendsNothing() async {
        // IndexRepository.kt:18
        let log = RequestLog()
        await Self.client(StubTransport(log: log)).push(sourceType: .channel, sourceId: "UC1", items: [])
        #expect(log.requests.isEmpty)
    }
}
