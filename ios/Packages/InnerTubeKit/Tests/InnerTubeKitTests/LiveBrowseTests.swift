import Foundation
import Testing
@testable import InnerTubeKit

/// The capture harness CF-C1 needs (Plan C Task 1). Not a curl: the request must carry the real
/// WEB context, the real headers and the real (possibly nil) visitorData, or the payload captured
/// is one the client can never receive. `BrowseClient.send` is private, so this assembles the
/// request from its own public ingredients in the same order.
///
/// Gated off by default exactly like `LiveResolveTests` (`.enabled(if:)`, `.tags(.live)`):
///
///     INNERTUBE_LIVE=1 swift test --filter LiveBrowseTests
///
/// Writes the RAW bodies to `INNERTUBE_CAPTURE_DIR` (default: the fixtures directory, under a
/// `.raw.json` suffix so a raw capture is never mistaken for a committed fixture). Trim and
/// redact before committing — see the policy in the plan and `BrowseClientTests`' header.
/// Captured 2026-08-29 against `UCmMcOjsVehVlEOteyrhjI2Q` (Alafasy); see `BrowseClientTests`.
@Suite struct LiveBrowseTests {
    private static let channelId = ProcessInfo.processInfo.environment["INNERTUBE_CHANNEL"] ?? "UCmMcOjsVehVlEOteyrhjI2Q"

    private static var captureDirectory: URL {
        if let dir = ProcessInfo.processInfo.environment["INNERTUBE_CAPTURE_DIR"] {
            return URL(fileURLWithPath: dir, isDirectory: true)
        }
        return URL(fileURLWithPath: #filePath).deletingLastPathComponent().appending(path: "Fixtures")
    }

    /// Page 1 of a tab, then (Task 6, CF-C-3) its continuation with the visitorData page 1 handed
    /// back -- the shape `BrowseClient.send` produces, so the continuation capture is one the client
    /// can actually receive. Returns the raw page-1 body.
    @discardableResult
    private func capture(params: String, to name: String, continuationTo: String? = nil) async throws -> Data {
        let store = RemoteConfigStore(
            transport: URLSessionTransport(), keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.invalid/none")!)  // never fetched -> bundled default
        let context = try #require(await store.current().clients["web"])
        let locale = InnerTubeLocale(hl: "en", gl: "US")
        let request = BrowseRequestBuilder().build(
            browseId: Self.channelId, params: params, continuation: nil,
            context: context, visitorData: nil, locale: locale)
        let response = try await URLSessionTransport().send(request)
        #expect(response.status == 200)
        try response.body.write(to: Self.captureDirectory.appending(path: name))
        guard let continuationTo else { return response.body }
        let json = try #require(try JSONSerialization.jsonObject(with: response.body) as? [String: Any])
        let token = try #require(Self.firstContinuationToken(json), "page 1 carries no continuationItemRenderer")
        let visitor = (json["responseContext"] as? [String: Any])?["visitorData"] as? String
        let next = BrowseRequestBuilder().build(
            browseId: nil, params: nil, continuation: token, context: context, visitorData: visitor, locale: locale)
        let page2 = try await URLSessionTransport().send(next)
        #expect(page2.status == 200)
        try page2.body.write(to: Self.captureDirectory.appending(path: continuationTo))
        return response.body
    }

    /// Depth-first walk for the first `continuationItemRenderer` token under the selected tab's
    /// grid (the header's engagement panel also carries one, so the walk is scoped to `contents`).
    private static func firstContinuationToken(_ json: [String: Any]) -> String? {
        func walk(_ node: Any) -> String? {
            if let dict = node as? [String: Any] {
                if let token = ((dict["continuationItemRenderer"] as? [String: Any])?["continuationEndpoint"] as? [String: Any])
                    .flatMap({ ($0["continuationCommand"] as? [String: Any])?["token"] as? String }) {
                    return token
                }
                for value in dict.values { if let found = walk(value) { return found } }
            } else if let array = node as? [Any] {
                for value in array { if let found = walk(value) { return found } }
            }
            return nil
        }
        return json["contents"].flatMap(walk)
    }

    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelShortsTab() async throws {
        try await capture(params: ChannelTab.shorts.params, to: "browse-channel-shorts.raw.json")
    }

    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelPlaylistsTab() async throws {
        try await capture(params: BrowseClient.playlistsTabParams, to: "browse-channel-playlists.raw.json",
                          continuationTo: "browse-channel-playlists-page2.raw.json")
    }

    /// C T6 fix (Part B): the Videos tab (`richGridRenderer`) page 1 + its first continuation --
    /// the fixture source for `channelVideos` once the `VLUU…` uploads playlist proved to cap at 200.
    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelVideosTab() async throws {
        try await capture(params: ChannelTab.videos.params, to: "browse-channel-videos-tab.raw.json",
                          continuationTo: "browse-channel-videos-tab-page2.raw.json")
    }

    /// CF-C-13: the Live tab as it is today, for an UPCOMING badge check against real data.
    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelLiveTab() async throws {
        try await capture(params: ChannelTab.live.params, to: "browse-channel-live.raw.json")
    }
}
