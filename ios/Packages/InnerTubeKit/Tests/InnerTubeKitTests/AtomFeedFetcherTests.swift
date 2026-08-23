import Foundation
import Testing
@testable import InnerTubeKit

/// `atom-channel.xml` is LIVE, recorded 2026-08-23 via
/// `curl "https://www.youtube.com/feeds/videos.xml?channel_id=UCmMcOjsVehVlEOteyrhjI2Q"`
/// (same public channel BrowseClientTests uses) — real 15-entry Atom feed, unmodified.
@Suite struct AtomFeedFetcherTests {
    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"

    /// Records every request and replays responses in call order — mirrors
    /// `StreamResolverTests.ScriptedTransport` / `BrowseClientTests.RecordingTransport`.
    private final class RecordingTransport: HTTPTransport, @unchecked Sendable {
        // Sendable: all mutable state is guarded by `lock`.
        private let lock = NSLock()
        private let responses: [HTTPResponse]
        private var index = 0
        private var requests: [HTTPRequest] = []

        init(_ responses: [HTTPResponse]) { self.responses = responses }

        var capturedRequests: [HTTPRequest] { lock.withLock { requests } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            lock.withLock {
                requests.append(request)
                let response = responses[min(index, responses.count - 1)]
                index += 1
                return response
            }
        }
    }

    private func fixtureBody() throws -> Data {
        let url = try #require(Bundle.module.url(forResource: "atom-channel", withExtension: "xml", subdirectory: "Fixtures"))
        return try Data(contentsOf: url)
    }

    @Test func parsesFixtureInto15VideoItems() async throws {
        let transport = RecordingTransport([HTTPResponse(status: 200, headers: [:], body: try fixtureBody())])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let items = try await fetcher.latest(Self.channelId)

        #expect(items.count == 15)
        #expect(items[0].id == "R6YoAYNxAcE")
        #expect(items[0].title.hasPrefix("معلقة زهير"))
        #expect(items[0].publishedText == "2026-08-22T15:00:06+00:00")
    }

    @Test func notModifiedReturnsPreviouslyParsedList() async throws {
        let transport = RecordingTransport([
            HTTPResponse(status: 200, headers: ["ETag": "abc123"], body: try fixtureBody()),
            HTTPResponse(status: 304, headers: [:], body: Data()),
        ])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let first = try await fetcher.latest(Self.channelId)
        let second = try await fetcher.latest(Self.channelId)

        #expect(second == first)
        #expect(second.count == 15)
    }

    @Test func sendsIfNoneMatchWhenETagStored() async throws {
        let transport = RecordingTransport([
            HTTPResponse(status: 200, headers: ["ETag": "abc123"], body: try fixtureBody()),
            HTTPResponse(status: 304, headers: [:], body: Data()),
        ])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        _ = try await fetcher.latest(Self.channelId)
        _ = try await fetcher.latest(Self.channelId)

        let requests = transport.capturedRequests
        #expect(requests.count == 2)
        #expect(requests[0].headers["If-None-Match"] == nil)
        #expect(requests[1].headers["If-None-Match"] == "abc123")
    }
}
