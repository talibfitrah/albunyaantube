import Foundation
import InnerTubeKit
import MediaPlayer

/// The pure mapper from "what the player is doing" to the `MPNowPlayingInfoCenter` dictionary
/// (spec §10, plan §6.5). Parity source: Android's `MediaSessionMetadataManager.kt:96-120`
/// (title / artist = channel name / artwork). Kept free of AVFoundation and of any network call so
/// the whole field contract is unit-testable -- `BackgroundPlaybackController` is the only thing
/// that reads a live `AVPlayer` and the only thing that fetches artwork.
struct NowPlayingSnapshot: Equatable, Sendable {
    var title: String
    var channel: String?
    var duration: TimeInterval?
    var elapsed: TimeInterval
    var rate: Float
    var isLive: Bool

    /// `nil` when there is nothing playable to advertise (`.loading`, `.error`, …) -- the caller
    /// clears `MPNowPlayingInfoCenter.default().nowPlayingInfo` in that case.
    ///
    /// `duration` is the *item's* measured duration when the caller has a finite one; CF-B6 says
    /// `Resolved` carries none, so `args.durationSeconds` is the seed that fills the gap between
    /// the first prepare and the item reporting for itself.
    static func make(args: PlayerArgs, state: StreamState, elapsed: TimeInterval,
                     duration: TimeInterval?, rate: Float) -> NowPlayingSnapshot? {
        let isLive: Bool
        switch state {
        case .ready(let resolved):
            if case .hls(_, let live, _, _) = resolved.stream { isLive = live } else { isLive = false }
        case .rung2Progressive:
            isLive = false
        default:
            // Nothing playable to advertise. `.recoveryExhausted` lands here too (CF-G-16):
            // playback has stopped for good until a manual retry, so advertising it was a lie --
            // masked only because that state dismantles the host, which clears the dictionary.
            return nil
        }
        let title = args.title?.trimmingCharacters(in: .whitespacesAndNewlines)
        // Ruling 39: the real channel title, never the category, never a placeholder. `PlayerArgs`
        // already applied that preference at its `ContentItem` boundary (ruling 17).
        let channel = args.channelName?.trimmingCharacters(in: .whitespacesAndNewlines)
        return NowPlayingSnapshot(
            title: title?.isEmpty == false ? title! : String(localized: "player_default_title"),
            channel: channel?.isEmpty == false ? channel : nil,
            // A live stream has no duration; publishing one gives the lock screen a lying scrubber.
            duration: isLive ? nil : (duration ?? args.durationSeconds.map(TimeInterval.init)),
            elapsed: elapsed, rate: rate, isLive: isLive)
    }

    /// The `MPNowPlayingInfoCenter` dictionary. Artwork is added by the controller, not here --
    /// it needs a network fetch and this type stays pure.
    var info: [String: Any] {
        var info: [String: Any] = [
            MPMediaItemPropertyTitle: title,
            MPNowPlayingInfoPropertyElapsedPlaybackTime: elapsed,
            MPNowPlayingInfoPropertyPlaybackRate: rate,
            MPNowPlayingInfoPropertyIsLiveStream: isLive,
            MPNowPlayingInfoPropertyMediaType: MPNowPlayingInfoMediaType.video.rawValue
        ]
        if let channel { info[MPMediaItemPropertyArtist] = channel }
        if let duration { info[MPMediaItemPropertyPlaybackDuration] = duration }
        return info
    }
}
