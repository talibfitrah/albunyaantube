import Foundation

/// Per-channel refresh bookkeeping — the first seven columns of Android's
/// `channel_feed_refresh_state` (`ChannelFeedRefreshState.kt:39-52`), minus the channel id (it is
/// the storage key) and minus `etag`/`lastModified`: `AtomFeedFetcher` owns the conditional-GET
/// validators itself (`AtomFeedFetcher.swift:36-63`), and a second copy of them here would be a
/// cache that can disagree with the one doing the request.
///
/// Fork F13: `Codable` so Task 16 can keep it in a `KeyValueStore` rather than SwiftData — no
/// atomicity coupling with row writes, and no schema version to migrate in Part A.
nonisolated struct ChannelRefreshState: Codable, Sendable, Equatable {
    var lastSuccessfulFetchAt: Date?
    var lastAttemptAt: Date?
    var lastErrorMessage: String?
    /// Defaulted so `ChannelRefreshState()` is the "never fetched" state; still required on decode.
    var consecutiveErrorCount: Int = 0
    var backoffUntil: Date?
}

/// Every decision the Me feed's refresh makes, as pure data — `MeFeedRepository.kt:782-935` with
/// the coroutines, the DAO and the telemetry left behind in Task 16.
nonisolated enum MeFeedRefreshGate {
    /// `CACHE_TTL_MS`.
    static let ttl: TimeInterval = 30 * 60
    /// `PER_CHANNEL_TIMEOUT_MS`.
    static let perChannelTimeout: Duration = .seconds(15)
    /// `MAX_CONCURRENT` — the fan-out bound Task 16's task group enforces.
    static let maxConcurrent = 4
    /// `ATOM_429_BACKOFFS` — 1 h, 4 h, 24 h.
    static let rateLimitedBackoffs: [TimeInterval] = [3_600, 14_400, 86_400]
    /// `ATOM_5XX_BACKOFFS` — 5 min, 30 min, 2 h.
    static let serverErrorBackoffs: [TimeInterval] = [300, 1_800, 7_200]

    enum Decision: Equatable, Sendable { case fetch, skipFresh, skipBackoff }

    /// TTL freshness first, then backoff — both bypassed by `force` (pull-to-refresh).
    ///
    /// A `.skipBackoff` writes NO field at all: the backoff is its own state, and touching
    /// `lastAttemptAt` on a channel we never contacted would make the next tick's freshness read a
    /// lie (`MeFeedRepository.kt:802-816`).
    static func decide(_ state: ChannelRefreshState?, now: Date, force: Bool) -> Decision {
        guard !force else { return .fetch }
        if let last = state?.lastSuccessfulFetchAt, now.timeIntervalSince(last) < ttl { return .skipFresh }
        if let until = state?.backoffUntil, now < until { return .skipBackoff }
        return .fetch
    }

    enum Outcome: Equatable, Sendable { case success, timeout, httpError(Int), transport }

    /// The new state after one attempt. `state == nil` is a channel with no bookkeeping yet.
    ///
    /// A timeout is a SOFT failure — it records the attempt and the message and leaves the counter
    /// and the backoff exactly where they were. Ambient network jitter must never push a user onto
    /// a 24 h cooldown (`MeFeedRepository.kt:851-861`).
    static func apply(_ outcome: Outcome, to state: ChannelRefreshState?, now: Date) -> ChannelRefreshState {
        var next = state ?? ChannelRefreshState()
        next.lastAttemptAt = now
        switch outcome {
        case .success:
            next.lastSuccessfulFetchAt = now
            next.lastErrorMessage = nil
            next.consecutiveErrorCount = 0
            next.backoffUntil = nil
        case .timeout:
            next.lastErrorMessage = "timeout after \(perChannelTimeout)"
        case .httpError(let status):
            next.consecutiveErrorCount += 1
            next.lastErrorMessage = "HTTP \(status)"
            // A status on neither ladder still counts, but invents no cooldown of its own: whatever
            // backoff was already running is preserved (`:902-905`).
            if let ladder = backoffs(forStatus: status) {
                next.backoffUntil = now + step(ladder, errorCount: next.consecutiveErrorCount)
            }
        case .transport:
            next.consecutiveErrorCount += 1
            next.lastErrorMessage = "transport failure"
            next.backoffUntil = now + step(serverErrorBackoffs, errorCount: next.consecutiveErrorCount)
        }
        return next
    }

    private static func backoffs(forStatus status: Int) -> [TimeInterval]? {
        switch status {
        case 429: rateLimitedBackoffs
        case 500...599: serverErrorBackoffs
        default: nil
        }
    }

    /// `(errCount - 1).coerceAtMost(lastIndex)` — the ladder STAYS at its top step, it does not
    /// wrap or keep doubling.
    private static func step(_ ladder: [TimeInterval], errorCount: Int) -> TimeInterval {
        ladder[min(max(errorCount - 1, 0), ladder.count - 1)]
    }
}
