import Foundation
import InnerTubeKit

/// Android's player `StreamState` (`player.md` §2.1), the resolve-outcome slice of it mapped onto
/// InnerTubeKit's `Resolved`/`ExtractionError` by `PlayerViewModel` (`player.md` §2.2).
/// `.recoveryExhausted` is the manual-retry escape hatch a later recovery task drives into --
/// Task 2's `open()`/`retry()` never produce it, but it's part of the type surface the brief hands
/// down, so it's declared here for that later task to use.
enum StreamState {
    case idle
    case loading
    case ready(Resolved)
    case rung2Progressive(Resolved)
    case error(messageKey: String)
    case contentUnavailable
    case cooldown(until: Date)
    case recoveryExhausted(Resolved)
    /// Rung 3 (plan §6.4 row 3): YouTube's own IFrame player in a navigation-locked `WKWebView`.
    /// A DIFFERENT surface, not a degraded `AVPlayer` -- which is why it is its own state and its
    /// own `PlayerScreen` branch, and why nothing promotes it back to rung 1/2 automatically.
    case embed(Resolved)
    /// Rung 4 (plan §6.4 row 4): terminal. The card carries a reason line and an "Open in YouTube"
    /// button; the hand-off itself is behind a confirmation (spec §6.6: "never an automatic
    /// hand-off"). `messageKey` is why we got here -- the ladder bottomed out
    /// (`player_error_generic`) or the embed reported 101/150 (`player_embed_owner_only`).
    case openInYouTube(Resolved, messageKey: String)
}

extension StreamState: Equatable {
    static func == (lhs: StreamState, rhs: StreamState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.loading, .loading), (.contentUnavailable, .contentUnavailable):
            return true
        case (.ready(let l), .ready(let r)): return l.comparisonKey == r.comparisonKey
        case (.rung2Progressive(let l), .rung2Progressive(let r)): return l.comparisonKey == r.comparisonKey
        case (.recoveryExhausted(let l), .recoveryExhausted(let r)): return l.comparisonKey == r.comparisonKey
        case (.error(let l), .error(let r)): return l == r
        case (.cooldown(let l), .cooldown(let r)): return l == r
        case (.embed(let l), .embed(let r)): return l.comparisonKey == r.comparisonKey
        case (.openInYouTube(let l, let lk), .openInYouTube(let r, let rk)):
            return l.comparisonKey == r.comparisonKey && lk == rk
        default: return false
        }
    }
}

private extension Resolved {
    /// InnerTubeKit's `Resolved`/`ResolvedStream` aren't `Equatable` (not ours to extend with real
    /// conformance) and `Resolved` carries no `videoId`, so this is the stable-key comparison
    /// `StreamState`'s `Equatable` needs: same client + same resolvedAt + same underlying
    /// URL/videoId is "the same resolve" for state-machine purposes.
    var comparisonKey: String {
        let streamKey: String
        switch stream {
        case .hls(let url, let isLive, _, _): streamKey = "hls:\(url.absoluteString):\(isLive)"
        case .progressive(let url, let label): streamKey = "progressive:\(url.absoluteString):\(label)"
        case .embed(let videoId): streamKey = "embed:\(videoId)"
        case .openInYouTube(let url): streamKey = "openInYouTube:\(url.absoluteString)"
        }
        return "\(client):\(userAgent):\(resolvedAt.timeIntervalSince1970):\(streamKey)"
    }
}
