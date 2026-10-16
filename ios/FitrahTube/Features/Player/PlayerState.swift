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
    /// Ruling 33's terminus, and the reason it is NOT `.idle`: `.idle` is the pre-open value and
    /// `PlayerStateCopy` renders it as "Loading..." with no Retry (`PlayerStateView.swift:28-32`),
    /// so ending a playlist on it is a permanent spinner. This is a real terminal state with real
    /// copy. NO Retry -- there is nothing to retry; the queue is genuinely finished and Back (or an
    /// Up Next tap, if any item remains) is the exit. Announced, because a playlist ending while
    /// the user is not looking at the screen is exactly the kind of transition spec 6.6's
    /// "Transitions" row exists for.
    case queueEnded
    // There is no hand-off state and no YouTube player state. Owner directives 2026-08-27 and
    // 2026-10-14: the app never redirects or hands off to YouTube and never plays a YouTube player,
    // in any Safe Mode setting -- so the ladder's floor is the progressive stream, and everything
    // below it lands on `.error`/`.contentUnavailable` like any other terminal outcome.
}

extension StreamState: Equatable {
    static func == (lhs: StreamState, rhs: StreamState) -> Bool {
        switch (lhs, rhs) {
        case (.idle, .idle), (.loading, .loading), (.contentUnavailable, .contentUnavailable),
             (.queueEnded, .queueEnded):
            return true
        case (.ready(let l), .ready(let r)): return l.comparisonKey == r.comparisonKey
        case (.rung2Progressive(let l), .rung2Progressive(let r)): return l.comparisonKey == r.comparisonKey
        case (.recoveryExhausted(let l), .recoveryExhausted(let r)): return l.comparisonKey == r.comparisonKey
        case (.error(let l), .error(let r)): return l == r
        case (.cooldown(let l), .cooldown(let r)): return l == r
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
        }
        return "\(client):\(userAgent):\(resolvedAt.timeIntervalSince1970):\(streamKey)"
    }
}
