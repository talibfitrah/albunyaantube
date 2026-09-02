import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Phase 3 Task 8 (spec §10 Chromecast): the `Resolved` + `PlayerArgs` -> `CastMediaInfo` mapping.
/// Pure -- no Cast SDK, no session, no network. The `GCKMediaInformation` half is one thin
/// `@MainActor` function over this value (`CastMedia.gckMediaInformation`), so everything worth
/// asserting lives here.
@Suite(.perTest)
struct CastMediaTests {
    /// Approved lecture id (never a music-video id).
    private let args = PlayerArgs(videoId: "xc7keR2piUM",
                                  title: "Tafsir of Surah Al-Kahf",
                                  channelName: "Fixture Channel",
                                  thumbnailURL: URL(string: "https://i.example/thumb.jpg"))

    private func resolved(_ stream: ResolvedStream) -> Resolved {
        Resolved(stream: stream, client: .visionos, userAgent: "FitrahTube/Test",
                 resolvedAt: Date(), expiresAt: nil)
    }

    private let hlsURL = URL(string: "https://manifest.example/hls.m3u8")!
    private let progressiveURL = URL(string: "https://progressive.example/itag18.mp4")!

    /// The rung-1 manifest: two tests below assert different field groups of this one value.
    private var hlsMedia: CastMediaInfo? {
        CastMedia.make(resolved: resolved(.hls(url: hlsURL, isLive: false, audioOnlyURL: nil,
                                               captionTracks: [])), args: args)
    }

    @Test func anHLSStreamCastsWithTheHLSManifestContentType() throws {
        let media = try #require(hlsMedia)
        #expect(media.contentURL == hlsURL)
        #expect(media.contentType == "application/x-mpegurl")
        #expect(media.streamType == .buffered)
    }

    /// Rung 2 is the single muxed itag-18 progressive; the receiver needs the MP4 type, not HLS.
    @Test func anItag18ProgressiveStreamCastsAsVideoMP4() throws {
        let media = try #require(CastMedia.make(resolved: resolved(
            .progressive(url: progressiveURL, label: "360p")), args: args))
        #expect(media.contentURL == progressiveURL)
        #expect(media.contentType == "video/mp4")
        #expect(media.streamType == .buffered)
    }

    /// Liveness is the only thing this adds; the content type is the HLS test's above.
    @Test func aLiveHLSStreamCastsAsALiveStream() throws {
        let media = try #require(CastMedia.make(resolved: resolved(
            .hls(url: hlsURL, isLive: true, audioOnlyURL: nil, captionTracks: [])), args: args))
        #expect(media.streamType == .live)
    }

    /// The no-hand-off directive's cast-shaped edge: rung 3 plays inside a `WKWebView` pointed at
    /// youtube-nocookie.com. There is no stream URL to hand a receiver, and handing it the video id
    /// would be a YouTube hand-off by another route. Never castable, no fallback, no second rung.
    @Test func theEmbedRungIsNeverCastable() {
        #expect(CastMedia.make(resolved: resolved(.embed(videoId: "xc7keR2piUM")), args: args) == nil)
    }

    /// Compliance pin (re-review Minor 1): a receiver fetches `contentURL` over the network, so a
    /// sandbox `file://` URL — what `OfflineResolver` answers with for a saved video — is never
    /// castable. Handing one to a receiver is a guaranteed-doomed load AND points the cast surface
    /// at a saved media file, which must never leave the sandbox. `PlayerScreen` also refuses to
    /// start a cast from an offline player; this is the pin at the one place every load is built.
    @Test func aSavedFileURLIsNeverCastable() {
        let file = URL(fileURLWithPath: "/var/mobile/Containers/Data/Application/offline/xc7keR2piUM.mp4")
        #expect(CastMedia.make(resolved: resolved(.progressive(url: file, label: "360p")), args: args) == nil)
        #expect(CastMedia.make(resolved: resolved(
            .hls(url: file, isLive: false, audioOnlyURL: nil, captionTracks: [])), args: args) == nil)
    }

    @Test func theMetadataFieldsAreCarriedFromTheArgs() throws {
        let media = try #require(hlsMedia)
        #expect(media.title == "Tafsir of Surah Al-Kahf")
        #expect(media.channelName == "Fixture Channel")
        #expect(media.thumbnailURL == URL(string: "https://i.example/thumb.jpg"))
    }

    /// A deep-linked open carries nothing but an id, and the receiver still has to show something.
    @Test func aTitlelessOpenFallsBackToTheVideoId() throws {
        let media = try #require(CastMedia.make(resolved: resolved(
            .progressive(url: progressiveURL, label: "360p")), args: PlayerArgs(videoId: "xc7keR2piUM")))
        #expect(media.title == "xc7keR2piUM")
        #expect(media.channelName == nil)
        #expect(media.thumbnailURL == nil)
    }
}
