import Foundation
import Testing
@testable import InnerTubeKit

/// Fixtures `browse-playlist.json`, `browse-channel-header.json`, `browse-channel-live.json` are
/// LIVE, recorded 2026-08-24 from `youtubei.googleapis.com/youtubei/v1/browse` (WEB context)
/// against a real public channel (`UCmMcOjsVehVlEOteyrhjI2Q`, "Alafasy") and playlist, then
/// trimmed to a handful of items each (dropping unused per-item action-menu JSON and unrelated
/// top-level keys) — every kept field value is real. `browse-channel-videos-page1.json` /
/// `-page2.json` are LIVE, recorded 2026-08-30 (C T6 fix, Part B) from the same channel's Videos
/// tab (`richGridRenderer`, 30/page) and its first continuation, trimmed to 5 items + the
/// continuation item; they replaced the `VLUU…` uploads-playlist captures once that path proved
/// to stop at 200 rows (100 + 100, then no continuation) on a 3.1K-video channel. `browse-botcheck.json` is SYNTHETIC (no live bot-check was reproducible within
/// this task's budget): it models InnerTube's documented `alerts[]` interstitial convention.
/// `browse-channel-shorts.json` / `browse-channel-playlists.json` are LIVE, recorded 2026-08-29
/// from the same channel's Shorts and Playlists tabs (both populated: 49 Shorts, 31 playlists at
/// capture time) via `LiveBrowseTests`, trimmed to 5 items + the continuation item, with every
/// `trackingParams`/`clickTrackingParams` and per-item action-menu JSON stripped.
/// `browse-channel-playlists-page2.json` is LIVE, recorded 2026-08-30 (Task 6, CF-C-3): the Playlists
/// tab's first continuation, trimmed the same way.
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
        // A channel's own tab carries no byline: the id is backfilled, the name is the header's.
        #expect(first.channelName == nil)
        #expect(first.channelId == Self.channelId)
        #expect(first.durationSeconds == 11 * 60 + 8)
        #expect(first.viewCountText == "66K views")
        #expect(first.publishedText == "7 days ago")
        #expect(first.thumbnailURL != nil)
        #expect(first.badge == nil)
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

    // C T6 fix (Part B): the Videos TAB (channel id + its `params`), not the `VLUU…` uploads
    // playlist -- live on 2026-08-30 the playlist returned 100 + 100 rows and then no continuation
    // on a 3.1K-video channel, while the tab's `richGridRenderer` continuation kept paging 30/page
    // past 300. Page 2 is a continuation-only body, like every other tab.
    @Test func channelVideosSendsChannelIdWithVideosTabParamsAndOmitsBothOnContinuation() async throws {
        let transport = RecordingTransport([
            try fixtureResponse("browse-channel-videos-page1"), try fixtureResponse("browse-channel-videos-page2"),
        ])
        let client = makeClient(transport)

        let page1 = try await client.channelVideos(Self.channelId, continuation: nil)
        let token = try #require(page1.nextContinuation)
        _ = try await client.channelVideos(Self.channelId, continuation: token)

        let bodies = transport.capturedBodies
        #expect(bodies[0].contains("\"browseId\":\"\(Self.channelId)\""))
        #expect(bodies[0].contains("\"params\":\"\(ChannelTab.videos.params)\""))
        #expect(!bodies[0].contains("VLUU"))
        #expect(!bodies[1].contains("\"browseId\""))
        #expect(!bodies[1].contains("\"params\""))
        #expect(bodies[1].contains("\"continuation\""))
    }

    // The end of a channel is a continuation page with no continuation item -- nil, not a token
    // to a fourth empty call.
    @Test func channelVideosLastPageHasNoContinuation() async throws {
        let url = try #require(Bundle.module.url(forResource: "browse-channel-videos-page2", withExtension: "json", subdirectory: "Fixtures"))
        var json = try #require(try JSONSerialization.jsonObject(with: Data(contentsOf: url)) as? [String: Any])
        var actions = try #require(json["onResponseReceivedActions"] as? [[String: Any]])
        var append = try #require(actions[0]["appendContinuationItemsAction"] as? [String: Any])
        let items = try #require(append["continuationItems"] as? [[String: Any]])
        append["continuationItems"] = items.filter { $0["continuationItemRenderer"] == nil }
        actions[0]["appendContinuationItemsAction"] = append
        json["onResponseReceivedActions"] = actions
        let body = try JSONSerialization.data(withJSONObject: json)
        let client = makeClient(FixtureTransport(routes: [.init(match: { _ in true }, response: HTTPResponse(status: 200, headers: [:], body: body))]))

        let page = try await client.channelVideos(Self.channelId, continuation: "last")

        #expect(page.items.count == 5)
        #expect(page.nextContinuation == nil)
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

    /// Cubic r3 #5: a non-JSON 429/403 body used to fail `JSONSerialization` -> `.malformed`, so
    /// the degraded fallback (keyed on `.botCheck`) never engaged. Live interstitials arrive as
    /// 200 + `alerts[]`; a non-200 browse block is unobserved live -- this guard is defensive.
    @Test func http429ResponseThrowsBrowseErrorBotCheck() async throws {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true },
                  response: HTTPResponse(status: 429, headers: [:], body: Data("Too Many Requests".utf8)))
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

    // MARK: - f2) VideoItem.badge (C T5 fix I2: LIVE vs UPCOMING is the badge, not `durationSeconds == nil`)

    /// The live capture holds one FINISHED stream (badge "3:53:36"); the in-progress and scheduled
    /// variants swap that one badge for YouTube's own `THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE` / "UPCOMING"
    /// (`browse-channel-live.json` is otherwise untouched). CF-C-13: confirm both against a live channel.
    private func liveFixture(badge: (text: String, style: String)?) throws -> HTTPResponse {
        var body = try #require(String(data: fixtureResponse("browse-channel-live").body, encoding: .utf8))
        let recorded = "\"text\": \"3:53:36\", \"badgeStyle\": \"THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT\""
        #expect(body.contains(recorded))
        if let badge {
            body = body.replacingOccurrences(of: recorded, with: "\"text\": \"\(badge.text)\", \"badgeStyle\": \"\(badge.style)\"")
        }
        return HTTPResponse(status: 200, headers: [:], body: Data(body.utf8))
    }

    private func firstLiveItem(_ response: HTTPResponse) async throws -> VideoItem {
        let client = makeClient(FixtureTransport(routes: [.init(match: { _ in true }, response: response)]))
        return try #require(try await client.channelTab(Self.channelId, tab: .live, continuation: nil).items.first)
    }

    @Test func finishedStreamHasDurationAndNoBadge() async throws {
        let item = try await firstLiveItem(try liveFixture(badge: nil))
        #expect(item.durationSeconds == 3 * 3600 + 53 * 60 + 36)
        #expect(item.badge == nil)
    }

    @Test func liveBadgeStyleParsesAsLive() async throws {
        let item = try await firstLiveItem(try liveFixture(badge: ("LIVE", "THUMBNAIL_OVERLAY_BADGE_STYLE_LIVE")))
        #expect(item.badge == .live)
        #expect(item.durationSeconds == nil)
    }

    @Test func upcomingBadgeParsesAsUpcomingNotLive() async throws {
        let item = try await firstLiveItem(try liveFixture(badge: ("UPCOMING", "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT")))
        #expect(item.badge == .upcoming)
        #expect(item.durationSeconds == nil)
    }

    // MARK: - f3) badge parse rejects overflow / over-long timestamps (Cubic finding: `Int(pow(60, n))` trapped)

    @Test(arguments: [
        "1:1:1:1",                       // 4 groups: no such timestamp shape
        "1:1:1:1:1:1:1:1:1:1:1:1",       // 12 groups: 60^11 > Int.max
        "9223372036854775807:00",        // Int.max group: `* 60` overflows
    ])
    func malformedDurationBadgeYieldsNilDuration(text: String) async throws {
        let item = try await firstLiveItem(try liveFixture(badge: (text, "THUMBNAIL_OVERLAY_BADGE_STYLE_DEFAULT")))
        #expect(item.durationSeconds == nil)
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

    /// CF-C-3 / CF-C-15 (Plan C Task 6, captured 2026-08-30): the Playlists tab's continuation is
    /// a plain `appendContinuationItemsAction` of `lockupViewModel`s plus the next
    /// `continuationItemRenderer` -- the same append shape as the video tabs.
    @Test func playlistsTabContinuationYieldsPage2() async throws {
        let transport = RecordingTransport([
            try fixtureResponse("browse-channel-playlists"), try fixtureResponse("browse-channel-playlists-page2"),
        ])
        let client = makeClient(transport)

        let page1 = try await client.channelPlaylists(Self.channelId, continuation: nil)
        let page2 = try await client.channelPlaylists(Self.channelId, continuation: try #require(page1.nextContinuation))

        #expect(page2.items.count == 5)
        let first = try #require(page2.items.first)
        #expect(first.id == "PL2hoGhz2jBSodBamILCK9GlWqDfjK8Wyz")
        #expect(first.itemCountText == "7 videos")
        #expect(page2.nextContinuation != nil)
        let body = transport.capturedBodies[1]
        #expect(body.contains("\"continuation\":\"\(try #require(page1.nextContinuation))\""))
        #expect(!body.contains("browseId"))
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

    @Test func aBotCheckWithATokenAlreadyAttachedAdoptsTheInterstitialsTokenWithoutRotating() async throws {
        // Cubic #8 / CF-CL-2: the rotate-on-stale path is DELETED. It wiped the visitorData the same
        // interstitial just handed over and burnt the 10-minute rotation slot -- and it never fired
        // in ~14 live launches (cold path). Adoption is unconditional: the interstitial's own token
        // replaces the stale one, and the next call retries with it.
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: try fixtureResponse("browse-botcheck"))
        ])
        let (client, session) = makeClientAndSession(transport)
        await session.setVisitorData("STALE", for: .web)

        await #expect(throws: BrowseError.botCheck) { _ = try await client.channelVideos(Self.channelId, continuation: nil) }

        #expect(await session.visitorData(for: .web) == Self.syntheticVisitorData)  // adopted, not rotated away
        #expect(await session.cooldownRemaining(now: .now) == nil)  // CF-C2
    }

    @Test func aBotCheckInterstitialIsDetectedByAlertTypeNotByEnglishCopy() async throws {
        // Cubic #7 (locale probe 2026-08-31): the interstitial's alert text is localized -- an
        // Arabic-locale session never contains "confirm you're not a bot". The discriminator is the
        // structured `alerts[].alertWithButtonRenderer.type == "ERROR"`, which is locale-independent.
        let body = try fixtureResponse("browse-botcheck").body
        var json = try #require(try JSONSerialization.jsonObject(with: body) as? [String: Any])
        json["alerts"] = [["alertWithButtonRenderer": [
            "type": "ERROR",
            "text": ["simpleText": "يُرجى تسجيل الدخول للتأكد من أنك لست روبوتًا"],
        ]]]
        let localized = try JSONSerialization.data(withJSONObject: json)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: HTTPResponse(status: 200, headers: [:], body: localized))
        ])
        let client = makeClient(transport)

        await #expect(throws: BrowseError.botCheck) { _ = try await client.channelVideos(Self.channelId, continuation: nil) }
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
