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

    // MARK: - publishedAt (Phase 4 Task 14: the Me feed sorts on an instant, not on prose)

    @Test func fixtureEntriesCarryThePublishedInstantAsADate() async throws {
        let transport = RecordingTransport([HTTPResponse(status: 200, headers: [:], body: try fixtureBody())])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let items = try await fetcher.latest(Self.channelId)

        let expected = try #require(ISO8601DateFormatter().date(from: "2026-08-22T15:00:06+00:00"))
        #expect(items[0].publishedAt == expected)
        // Every entry in the live fixture carries a `<published>`, so none may degrade to nil.
        #expect(items.allSatisfy { $0.publishedAt != nil })
    }

    @Test func publishedTextStillHumanizesAfterTheDateParse() async throws {
        let transport = RecordingTransport([HTTPResponse(status: 200, headers: [:], body: try fixtureBody())])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let items = try await fetcher.latest(Self.channelId)

        // The date-taking overload must produce exactly what the raw-string entry point does --
        // parsing once is a refactor, not a behaviour change (RULING 48 still renders this verbatim).
        #expect(items[0].publishedText == AtomFeedFetcher.humanizePublished("2026-08-22T15:00:06+00:00"))
        #expect(items.allSatisfy { $0.publishedText != nil })
        #expect(items.allSatisfy { $0.publishedText?.contains("+00:00") != true })
    }

    @Test func notModifiedReplayKeepsPublishedAt() async throws {
        let transport = RecordingTransport([
            HTTPResponse(status: 200, headers: ["ETag": "abc123"], body: try fixtureBody()),
            HTTPResponse(status: 304, headers: [:], body: Data()),
        ])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let first = try await fetcher.latest(Self.channelId)
        let second = try await fetcher.latest(Self.channelId)

        // The load-bearing one: `CachedItem` persists a fixed field list, so a `publishedAt` left out
        // of it would empty the Me feed on every replay -- and the real endpoint sends no validators,
        // which makes that a heisenbug rather than a visible break.
        let expected = try #require(ISO8601DateFormatter().date(from: "2026-08-22T15:00:06+00:00"))
        #expect(second.map(\.publishedAt) == first.map(\.publishedAt))
        #expect(second[0].publishedAt == expected)
    }

    // MARK: - cached(_:) (the no-network reader the Me feed renders between refreshes)

    @Test func cachedReturnsTheStoredListWithNoTransportSend() async throws {
        let transport = RecordingTransport([HTTPResponse(status: 200, headers: [:], body: try fixtureBody())])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let fetched = try await fetcher.latest(Self.channelId)
        let cached = await fetcher.cached(Self.channelId)

        #expect(cached == fetched)
        #expect(transport.callCount == 1)
    }

    @Test func cachedIsEmptyForAChannelNeverFetched() async {
        let transport = RecordingTransport([HTTPResponse(status: 500, headers: [:], body: Data())])
        let fetcher = AtomFeedFetcher(transport: transport, keyValueStore: InMemoryKeyValueStore())

        let cached = await fetcher.cached("UCneverFetchedChannelId")

        #expect(cached.isEmpty)
        #expect(transport.callCount == 0)
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
