import Foundation
import Testing
@testable import InnerTubeKit

extension Tag {
    @Tag static var live: Self
}

/// Manual "does the real ladder still work" check (`ios-app-plan.md` §6.2/§6.4) — hits live
/// YouTube through a real `URLSessionTransport`, no fixtures, no mocking. Gated off by default
/// via `.enabled(if:)` so a plain `swift test` never runs it (no network call, no session
/// rotation burned) regardless of any external tag-filter configuration; `.tags(.live)` is the
/// documented/filterable marker for the same intent. Run deliberately, from a residential line:
///
///     INNERTUBE_LIVE=1 swift test --filter LiveResolveTests
///
/// Green as of 2026-08-24. It exercises the session bootstrap: the first POST goes out tokenless
/// and comes back bot-checked, the resolver adopts that response's `responseContext.visitorData`
/// and retries the same rung, which returns OK + `hlsManifestUrl`. A regression here shows up as
/// `.progressive` (the ANDROID itag-18 rung) instead of `.hls`.
@Suite struct LiveResolveTests {
    /// "Normal lecture" case from `probes/probe-2026-08-23.md` — confirmed VISIONOS OK +
    /// `hlsManifestUrl` present, no pot demanded, live-verified 2026-08-23.
    /// The repo's ONE shared video id, used by every offline fixture and every live check across
    /// the iOS targets. It is a lecture from the app's approved catalog: owner directive 2026-08-27
    /// forbids a music video (or any arbitrary YouTube video) as a test id -- this is a Muslim
    /// audience app -- so nothing here may be swapped for a "famous" id.
    private static let knownGoodVideoId = "xc7keR2piUM"

    private struct AlwaysAvailable: AvailabilityGate {
        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
    }

    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["INNERTUBE_LIVE"] == "1"))
    func resolvesKnownGoodVideoToHLS() async throws {
        let innerTube = InnerTube(
            keyValueStore: InMemoryKeyValueStore(),
            availabilityGate: AlwaysAvailable(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.invalid/remote-config.json")!
        )

        let resolved = try await innerTube.resolver.resolve(
            Self.knownGoodVideoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)

        guard case .hls = resolved.stream else {
            Issue.record("expected .hls, got \(resolved.stream)")
            return
        }
    }

    /// Muxed save-walk live leg (Task 4 fix round / save-purpose walk): the `requiresMuxed` walk
    /// must land on the ANDROID itag-18 rung against live YouTube, and one 10 MB `Range` chunk of
    /// that URL must answer 206 with bytes (the `ProgressiveEngine` walk shape). Deliberately does
    /// NOT download the file. Run: `OFFLINE_LIVE=1 swift test --filter theMuxedSaveWalk`.
    @Test(.tags(.live), .enabled(if: ProcessInfo.processInfo.environment["OFFLINE_LIVE"] == "1"))
    func theMuxedSaveWalkResolvesItag18AndARangeChunkAnswers206() async throws {
        let innerTube = InnerTube(
            keyValueStore: InMemoryKeyValueStore(),
            availabilityGate: AlwaysAvailable(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.invalid/remote-config.json")!
        )

        let resolved = try await innerTube.resolver.resolve(
            Self.knownGoodVideoId, purpose: .prefetch, sourceChannelId: nil, forceRefresh: false,
            requiresMuxed: true)
        guard case .progressive(let url, let label) = resolved.stream else {
            Issue.record("expected .progressive, got \(resolved.stream)")
            return
        }
        // Never print the full URL: googlevideo playback URLs embed the caller's IP.
        print("[muxed-live] client=\(resolved.client) label=\(label) host=\(url.host() ?? "?") itag18=\(url.absoluteString.contains("itag=18"))")
        #expect(url.absoluteString.contains("itag=18"))

        var request = URLRequest(url: url)
        request.setValue(resolved.userAgent, forHTTPHeaderField: "User-Agent")
        request.setValue("bytes=0-10485759", forHTTPHeaderField: "Range")
        let (data, response) = try await URLSession.shared.data(for: request)
        let http = try #require(response as? HTTPURLResponse)
        print("[muxed-live] status=\(http.statusCode) bytes=\(data.count) contentRange=\(http.value(forHTTPHeaderField: "Content-Range") ?? "nil")")
        #expect(http.statusCode == 206)
        #expect(!data.isEmpty)
    }
}
