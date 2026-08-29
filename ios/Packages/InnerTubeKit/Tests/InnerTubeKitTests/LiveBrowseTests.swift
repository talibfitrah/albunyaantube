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

    private func capture(params: String, to name: String) async throws {
        let store = RemoteConfigStore(
            transport: URLSessionTransport(), keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.invalid/none")!)  // never fetched -> bundled default
        let context = try #require(await store.current().clients["web"])
        let request = BrowseRequestBuilder().build(
            browseId: Self.channelId, params: params, continuation: nil,
            context: context, visitorData: nil, locale: InnerTubeLocale(hl: "en", gl: "US"))
        let response = try await URLSessionTransport().send(request)
        #expect(response.status == 200)
        try response.body.write(to: Self.captureDirectory.appending(path: name))
    }

    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelShortsTab() async throws {
        try await capture(params: ChannelTab.shorts.params, to: "browse-channel-shorts.raw.json")
    }

    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func captureChannelPlaylistsTab() async throws {
        try await capture(params: BrowseClient.playlistsTabParams, to: "browse-channel-playlists.raw.json")
    }
}
