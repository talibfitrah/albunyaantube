import Foundation
import Testing
@testable import InnerTubeKit

/// `atom-channel.xml` is LIVE, recorded 2026-08-23 via
/// `curl "https://www.youtube.com/feeds/videos.xml?channel_id=UCmMcOjsVehVlEOteyrhjI2Q"`
/// (same public channel BrowseClientTests uses) — real 15-entry Atom feed, unmodified.
@Suite struct AtomFeedFetcherTests {
    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"

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
        // gstack R4: the raw ISO 8601 `<published>` value must NOT pass through verbatim -- degraded
        // rows render `publishedText` directly, so it has to be the humanized relative form the
        // normal browse path carries. Oracle: the same humanizer over the fixture's raw value.
        #expect(items[0].publishedText == AtomFeedFetcher.humanizePublished("2026-08-22T15:00:06+00:00"))
        #expect(items[0].publishedText?.contains("2026-08-22T") != true)
        #expect(items[0].thumbnailURL == URL(string: "https://i3.ytimg.com/vi/R6YoAYNxAcE/hqdefault.jpg"))
    }

    @Test func humanizePublishedTurnsISO8601IntoALocalizedRelativeString() throws {
        let now = try #require(ISO8601DateFormatter().date(from: "2026-08-24T15:00:06+00:00"))
        let text = AtomFeedFetcher.humanizePublished(
            "2026-08-22T15:00:06+00:00", now: now, locale: Locale(identifier: "en_US"))
        #expect(text == "2 days ago")
    }

    @Test func humanizePublishedFallsBackToNilOnAnUnparseableDate() {
        // nil beats a raw unparseable string on a row subtitle.
        #expect(AtomFeedFetcher.humanizePublished("not-a-date") == nil)
        #expect(AtomFeedFetcher.humanizePublished(nil) == nil)
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

        let requests = transport.recorded
        #expect(requests.count == 2)
        #expect(requests[0].headers["If-None-Match"] == nil)
        #expect(requests[1].headers["If-None-Match"] == "abc123")
    }
}
