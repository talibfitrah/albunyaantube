import Foundation
import GoogleCast
import InnerTubeKit

/// One resolved stream, in the shape a Cast receiver needs (spec §10 Chromecast). Pure value:
/// `CastController` turns it into a `GCKMediaInformation` at load time, nothing else.
nonisolated struct CastMediaInfo: Sendable, Equatable {
    var contentURL: URL
    var title: String
    var channelName: String?
    var thumbnailURL: URL?
}

/// The `Resolved` + `PlayerArgs` -> `CastMediaInfo` mapping and its one thin SDK translation.
/// Everything that decides anything is in `make`, which is pure and exhaustively tested
/// (`CastMediaTests`); `gckMediaInformation` only copies fields across.
nonisolated enum CastMedia {
    /// The only castable stream: the single muxed itag-18 MP4 (rung 2, or the cast's own muxed walk
    /// in `PlayerViewModel.castMedia`), as Android casts it.
    static let progressiveContentType = "video/mp4"

    static func make(resolved: Resolved, args: PlayerArgs) -> CastMediaInfo? {
        let url: URL
        switch resolved.stream {
        case .hls:
            // Never castable. Proven on a real Chromecast (Default Media Receiver, 2026-09-27):
            // YouTube's manifest -- video-only TS variants plus separate packed-AAC audio
            // renditions -- loads and goes IDLE/ERROR within 10 s whether sent as LIVE, BUFFERED or
            // with explicit HLS segment formats, while the same lecture's itag-18 MP4 plays. Every
            // URL answered 200 to a Chromecast User-Agent: the format fails, not the fetch.
            return nil
        case .progressive(let progressiveURL, _):
            url = progressiveURL
        case .embed:
            // Owner directive 2026-08-27: no YouTube hand-off, ever. Rung 3 IS YouTube's own
            // player in a locked WKWebView -- there is no stream URL to hand a receiver, and
            // handing it the video id would be that hand-off by another route. Never castable.
            return nil
        }
        // Compliance pin: the receiver fetches `contentURL` over the network,
        // so only an http(s) URL can ever be cast. An offline `PlayerScreen` resolves through
        // `OfflineResolver`, which answers with a sandbox `file://` URL — handing that to a
        // receiver is a guaranteed-doomed load AND points the cast surface at a saved media file,
        // which must never leave the sandbox. `PlayerScreen` also refuses to start a cast from an
        // offline player; this is the belt-and-braces half, at the one place every cast load is
        // built.
        guard let scheme = url.scheme?.lowercased(), scheme == "http" || scheme == "https" else {
            return nil
        }
        return CastMediaInfo(contentURL: url,
                             title: args.title ?? args.videoId, channelName: args.channelName,
                             thumbnailURL: args.thumbnailURL)
    }

    /// The one SDK translation. `@MainActor` because `GCKMediaInformation` and its builder are
    /// non-`Sendable` Objective-C objects that only `CastController` (also `@MainActor`) touches.
    @MainActor
    static func gckMediaInformation(from media: CastMediaInfo) -> GCKMediaInformation {
        let metadata = GCKMediaMetadata(metadataType: .movie)
        metadata.setString(media.title, forKey: kGCKMetadataKeyTitle)
        if let channelName = media.channelName {
            metadata.setString(channelName, forKey: kGCKMetadataKeySubtitle)
        }
        if let thumbnailURL = media.thumbnailURL {
            // Nominal 16:9 -- the receiver scales; these are hints, not a fetch contract.
            metadata.addImage(GCKImage(url: thumbnailURL, width: 480, height: 270))
        }
        let builder = GCKMediaInformationBuilder(contentURL: media.contentURL)
        builder.contentType = progressiveContentType
        builder.streamType = .buffered
        builder.metadata = metadata
        return builder.build()
    }
}

/// Whether the player toolbar shows a cast slot at all. Spec §10's "Cast SDK is not loaded at all
/// when `GCKCastContext` cannot be created" means NO affordance, not a disabled one; Task 7's
/// offline flag hides it for the same reason it hides Save (a sandboxed file no receiver can
/// fetch, and media that must never leave the sandbox).
nonisolated enum CastAffordance {
    static func isVisible(castAvailable: Bool, isOfflinePlayback: Bool) -> Bool {
        castAvailable && !isOfflinePlayback
    }
}
