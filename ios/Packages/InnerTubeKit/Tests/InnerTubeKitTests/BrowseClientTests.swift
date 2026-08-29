import Foundation
import Testing
@testable import InnerTubeKit

/// Fixtures `browse-channel-videos-page1.json` / `-page2.json`, `browse-playlist.json`,
/// `browse-channel-header.json`, `browse-channel-live.json` are LIVE, recorded 2026-08-24 from
/// `youtubei.googleapis.com/youtubei/v1/browse` (WEB context) against a real public channel
/// (`UCmMcOjsVehVlEOteyrhjI2Q`, "Alafasy") and playlist, then trimmed to a handful of items each
/// (dropping unused per-item action-menu JSON and unrelated top-level keys) — every kept field
/// value is real. `browse-botcheck.json` is SYNTHETIC (no live bot-check was reproducible within
/// this task's budget): it models InnerTube's documented `alerts[]` interstitial convention.
/// `browse-channel-shorts.json` / `browse-channel-playlists.json` are LIVE, recorded 2026-08-29
/// from the same channel's Shorts and Playlists tabs (both populated: 49 Shorts, 31 playlists at
/// capture time) via `LiveBrowseTests`, trimmed to 5 items + the continuation item, with every
/// `trackingParams`/`clickTrackingParams` and per-item action-menu JSON stripped.
///
/// Every `responseContext.visitorData` in these fixtures is SYNTHETIC (`CgtGSVhUVVJFXzAwMSiFAA%3D%3D`):
/// a real one is a session identifier for the machine that captured it and must never be committed.
@Suite struct BrowseClientTests {
    private static let syntheticVisitorData = "CgtGSVhUVVJFXzAwMSiFAA%3D%3D"
    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"

    private func fixtureResponse(_ name: String) throws -> HTTPResponse {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return HTTPResponse(status: 200, headers: [:], body: try Data(contentsOf: url))
    }

    private func bodyString(_ request: HTTPRequest) -> String {
        String(data: request.body ?? Data(), encoding: .utf8) ?? ""
    }

    private func makeClient(_ transport: HTTPTransport) -> BrowseClient {
        makeClientAndSession(transport).client
    }

    private func makeClientAndSession(_ transport: HTTPTransport) -> (client: BrowseClient, session: SessionStore) {
        let configStore = RemoteConfigStore(
            transport: NoopTransport(), keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.com/config.json")!)
        let session = SessionStore(
            monotonicClock: ManualClock(), wallClock: ManualClock(), keyValueStore: InMemoryKeyValueStore())
        let client = BrowseClient(
            transport: transport, remoteConfigStore: configStore, sessionStore: session,
            locale: InnerTubeLocale(hl: "en", gl: "US"))
        return (client, session)
    }

    private struct NoopTransport: HTTPTransport {
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            HTTPResponse(status: 200, headers: [:], body: Data())
        }
    }

    // MARK: - a) channelVideos page 1 -> items + nextContinuation

    @Test func channelVideosPage1YieldsItemsAndNextContinuation() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-videos-page1"))
        ])
        let client = makeClient(transport)

        let page = try await client.channelVideos(Self.channelId, continuation: nil)

        #expect(page.items.count == 5)
        #expect(page.nextContinuation != nil)
        let first = try #require(page.items.first)
        #expect(first.id == "R6YoAYNxAcE")
        #expect(!first.title.isEmpty)
        #expect(first.channelName == "Alafasy")
        #expect(first.channelId == Self.channelId)
        #expect(first.viewCountText != nil)
        #expect(first.publishedText != nil)
        #expect(first.thumbnailURL != nil)
    }

    // MARK: - b) feeding the continuation yields page 2

    @Test func channelVideosContinuationYieldsPage2() async throws {
        let transport = FixtureTransport(routes: [
            .init(
                match: { !bodyString($0).contains("\"continuation\"") },
                response: try fixtureResponse("browse-channel-videos-page1")),
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-videos-page2")),
        ])
        let client = makeClient(transport)

        let page1 = try await client.channelVideos(Self.channelId, continuation: nil)
        let token = try #require(page1.nextContinuation)
        let page2 = try await client.channelVideos(Self.channelId, continuation: token)

        #expect(page2.items.count == 5)
        #expect(page2.items.map(\.id) != page1.items.map(\.id))
    }

    // The uploads-playlist (`VLUU…`) browseId is sent on page 1 and omitted (continuation-only
    // body) on page 2 — this is the trick's whole point (channel-detail.md: channel-tab
    // continuations are unreliable past 1-2 pages, the uploads-playlist one is stable).
    @Test func channelVideosPage1UsesUploadsPlaylistBrowseIdAndOmitsItOnContinuation() async throws {
        let transport = RecordingTransport([
            try fixtureResponse("browse-channel-videos-page1"), try fixtureResponse("browse-channel-videos-page2"),
        ])
        let client = makeClient(transport)

        let page1 = try await client.channelVideos(Self.channelId, continuation: nil)
        let token = try #require(page1.nextContinuation)
        _ = try await client.channelVideos(Self.channelId, continuation: token)

        let bodies = transport.capturedBodies
        #expect(bodies[0].contains("\"browseId\":\"VLUU\(Self.channelId.dropFirst(2))\""))
        #expect(!bodies[1].contains("\"browseId\""))
        #expect(bodies[1].contains("\"continuation\""))
    }

    // MARK: - c) playlistItems parses title/uploader/count-bearing fields

    @Test func playlistItemsParsesTitleUploaderAndCount() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-playlist"))
        ])
        let client = makeClient(transport)

        let page = try await client.playlistItems(Self.playlistId, continuation: nil)

        #expect(page.items.count == 5)
        let first = try #require(page.items.first)
        #expect(first.id == "5ZMMARhgvsw")
        #expect(!first.title.isEmpty)
        #expect(first.channelName != nil)
        #expect(first.channelId != nil)
        #expect(first.viewCountText != nil)
    }

    @Test func playlistItemsSendsVLPrefixedBrowseId() async throws {
        let transport = RecordingTransport([try fixtureResponse("browse-playlist")])
        let client = makeClient(transport)

        _ = try await client.playlistItems(Self.playlistId, continuation: nil)

        #expect(transport.capturedBodies[0].contains("\"browseId\":\"VL\(Self.playlistId)\""))
    }

    // MARK: - d) bot-checked browse response surfaces BrowseError.botCheck

    @Test func botCheckedResponseThrowsBrowseErrorBotCheck() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-botcheck"))
        ])
        let client = makeClient(transport)

        await #expect(throws: BrowseError.botCheck) {
            _ = try await client.channelVideos(Self.channelId, continuation: nil)
        }
    }

    // MARK: - e) channelHeader parses id/name/subscriberText/avatar/banner

    @Test func channelHeaderParsesCoreFields() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-header"))
        ])
        let client = makeClient(transport)

        let header = try await client.channelHeader(Self.channelId)

        #expect(header.id == Self.channelId)
        #expect(header.name == "Alafasy")
        #expect(header.subscriberText?.contains("subscriber") == true)
        #expect(header.avatarURL != nil)
        #expect(header.bannerURL != nil)
    }

    // MARK: - f) channelTab(.live) parses via the channel `params` path (distinct from VLUU)

    @Test func channelTabLiveParsesItemsAndBackfillsChannelId() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-live"))
        ])
        let client = makeClient(transport)

        let page = try await client.channelTab(Self.channelId, tab: .live, continuation: nil)

        #expect(page.items.count == 1)
        let first = try #require(page.items.first)
        #expect(first.id == "1qi0W6S4izw")
        // The channel-tab renderer carries no per-item byline (it's implicit); BrowseClient
        // backfills it from the known channel id.
        #expect(first.channelId == Self.channelId)
    }

    @Test func channelTabSendsChannelIdAsBrowseIdWithTabParams() async throws {
        let transport = RecordingTransport([try fixtureResponse("browse-channel-live")])
        let client = makeClient(transport)

        _ = try await client.channelTab(Self.channelId, tab: .live, continuation: nil)

        let body = transport.capturedBodies[0]
        #expect(body.contains("\"browseId\":\"\(Self.channelId)\""))
        #expect(body.contains("\"params\""))
    }

    // MARK: - g) channelTab(.shorts) and channelPlaylists — CF-C1 (Plan C Task 1)

    @Test func shortsTabParsesIntoVideoItemsWithNoDuration() async throws {
        // CF-C1: `channelTab(.shorts)` returned an EMPTY page (BrowseClient.swift ponytail note)
        // because `shortsLockupViewModel` is not the plain `lockupViewModel` the video parser reads.
        // Assert the real ids from the capture, and that the 9:16 grid's fields are all populated.
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-shorts"))
        ])
        let client = makeClient(transport)

        let page = try await client.channelTab(Self.channelId, tab: .shorts, continuation: nil)

        #expect(page.items.count == 5)
        let first = try #require(page.items.first)
        #expect(first.id == "DRGRsBC8bOU")
        #expect(!first.title.isEmpty)
        #expect(first.thumbnailURL != nil)
        #expect(first.viewCountText == "424 views")
        #expect(first.durationSeconds == nil)  // Shorts tiles carry no duration badge
        #expect(first.channelId == Self.channelId)  // backfill, as .live already does
        #expect(page.nextContinuation != nil)
    }

    @Test func playlistsTabParsesIntoPlaylistTilesWithAnItemCount() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-playlists"))
        ])
        let client = makeClient(transport)

        let page = try await client.channelPlaylists(Self.channelId, continuation: nil)

        #expect(page.items.count == 5)
        let first = try #require(page.items.first)
        #expect(first.id == "PL2hoGhz2jBSrgZ1tWp0_HVjkrkdd-Bo8f")
        #expect(!first.title.isEmpty)
        #expect(first.thumbnailURL != nil)
        #expect(first.itemCountText == "99 videos")  // the badge is a count, not a duration
        #expect(page.nextContinuation != nil)
    }

    @Test func channelPlaylistsSendsChannelIdAsBrowseIdWithPlaylistsParams() async throws {
        let transport = RecordingTransport([try fixtureResponse("browse-channel-playlists")])
        let client = makeClient(transport)

        _ = try await client.channelPlaylists(Self.channelId, continuation: nil)

        let body = transport.capturedBodies[0]
        #expect(body.contains("\"browseId\":\"\(Self.channelId)\""))
        #expect(body.contains("\"params\":\"EglwbGF5bGlzdHPyBgQKAkIA\""))
    }

    // MARK: - h) WEB visitorData adoption and stale-token rotation (reconciliation note 3, C3)

    @Test func aBrowseResponseCarryingVisitorDataIsAdopted() async throws {
        // Nothing wrote `.web` visitorData before this task -- `setVisitorData` is called only by
        // StreamResolver, for visionos/android -- so every browse request has gone out tokenless,
        // which is exactly what Plan A's "the first tokenless call is always bot-checked" finding
        // predicts will keep happening.
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-channel-header"))
        ])
        let (client, session) = makeClientAndSession(transport)

        _ = try await client.channelHeader(Self.channelId)

        #expect(await session.visitorData(for: .web) == Self.syntheticVisitorData)
    }

    @Test func aBootstrapBotCheckStillAdoptsItsTokenAndDoesNotRotateItAway() async throws {
        // THE ordering test (C3). Adoption happens on EVERY response that carries a token --
        // including the interstitial, which is the whole point of Plan A's finding -- and
        // `rotate(.web)` CLEARS the family. So rotating on a bootstrap bot-check would throw away
        // the very token that makes the next call succeed, and the client would bootstrap forever.
        // No token was attached here, so nothing is stale.
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-botcheck"))
        ])
        let (client, session) = makeClientAndSession(transport)

        await #expect(throws: BrowseError.botCheck) { _ = try await client.channelVideos(Self.channelId, continuation: nil) }

        #expect(await session.visitorData(for: .web) == Self.syntheticVisitorData)  // SURVIVES
        #expect(await session.cooldownRemaining(now: .now) == nil)  // CF-C2: never escalate
    }

    @Test func aBotCheckWithATokenAlreadyAttachedRotatesItAsStale() async throws {
        // The other half: we sent a token and were bot-checked anyway, so the token is burnt.
        // rotate() clears it (throttled to 1/10 min by SessionStore itself) and the next call
        // re-bootstraps. The interstitial's own token is adopted first, then rotate clears the
        // family -- the net effect is "no token", the correct state for a session YouTube rejected.
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-botcheck"))
        ])
        let (client, session) = makeClientAndSession(transport)
        await session.setVisitorData("STALE", for: .web)

        await #expect(throws: BrowseError.botCheck) { _ = try await client.channelVideos(Self.channelId, continuation: nil) }

        #expect(await session.visitorData(for: .web) == nil)
        #expect(await session.cooldownRemaining(now: .now) == nil)  // CF-C2
    }

    @Test func theSecondPageSendsTheAdoptedVisitorDataAsAHeader() async throws {
        // Page 1's fixture carries the synthetic token; the continuation call must send it back.
        let transport = RecordingTransport([
            try fixtureResponse("browse-channel-playlists"), try fixtureResponse("browse-channel-playlists"),
        ])
        let client = makeClient(transport)

        let page1 = try await client.channelPlaylists(Self.channelId, continuation: nil)
        _ = try await client.channelPlaylists(Self.channelId, continuation: try #require(page1.nextContinuation))

        let requests = transport.recorded
        #expect(requests[0].headers["X-Goog-Visitor-Id"] == nil)
        #expect(requests[1].headers["X-Goog-Visitor-Id"] == Self.syntheticVisitorData)
    }
}
