import Foundation

/// Kind of extraction request — different kinds draw from different budgets
/// (`extraction.md` §6.1). Maps 1:1 to Android's
/// `player/ExtractionRateLimiter.kt` `RequestKind`: `.player` == Android's
/// `MANUAL` (the exponential-backoff lane), `.autoRecovery` ==
/// `AUTO_RECOVERY`, `.prefetch` == `PREFETCH`, `.proactiveTTLRefresh` ==
/// `PROACTIVE_TTL_REFRESH`.
public enum RequestKind: Sendable {
    case player
    case autoRecovery
    case prefetch
    case proactiveTTLRefresh
}

public enum Decision: Sendable, Equatable {
    case allowed
    case delayed(Duration, reason: String)
    case blocked(reason: String, retryAfter: Duration)
}

/// App-side permit ledger for stream extraction, per videoId (`extraction.md`
/// §6.1). Callers check this before triggering a resolve; the attempt is
/// recorded BEFORE returning `.allowed` so failures can't storm.
///
/// Actor isolation replaces Android's `ConcurrentHashMap` +
/// `synchronized(record)` — all state below is only ever touched from within
/// `check`, so no manual locking is needed.
public actor ExtractionRateLimiter {
    private static let minExtractionInterval = Duration.seconds(30)
    private static let perVideoWindow = Duration.seconds(5 * 60)
    private static let maxAttemptsPerVideo = 3
    private static let globalWindow = Duration.seconds(60)
    private static let maxGlobalAttempts = 10
    private static let autoRecoveryReserved = 2
    private static let proactiveTTLReserved = 2
    private static let maxProactiveGlobalPerMinute = 10

    private struct VideoRecord {
        /// Shared per-video budget: player + autoRecovery + prefetch attempts
        /// (proactiveTTLRefresh intentionally does not add here — it must
        /// not starve manual/reactive recovery).
        var attemptTimestamps: [Duration] = []
        /// Consecutive `.player` attempts, for exponential backoff. Only
        /// `.player` increments this; `onSuccess` clears it.
        var consecutivePlayerAttempts = 0
        var lastPlayerAttemptTime: Duration?
        var lastAutoRecoveryAttemptTime: Duration?
        var lastPrefetchAttemptTime: Duration?
        var lastProactiveTTLRefreshAttemptTime: Duration?
        var autoRecoveryAttemptTimestamps: [Duration] = []
        var proactiveTTLRefreshAttemptTimestamps: [Duration] = []
    }

    private var records: [String: VideoRecord] = [:]
    /// Shared global lane: player + prefetch.
    private var globalAttemptTimestamps: [Duration] = []
    /// Separate global lane for proactiveTTLRefresh so it doesn't compete
    /// with manual/prefetch budget, bounded by its own ceiling.
    private var globalProactiveTTLRefreshTimestamps: [Duration] = []

    public init() {}

    public func check(_ videoId: String, kind: RequestKind, now: Duration) -> Decision {
        if let blocked = globalDecision(kind: kind, now: now) {
            return blocked
        }

        var record = records[videoId] ?? VideoRecord()
        record.attemptTimestamps.removeAll { now - $0 > Self.perVideoWindow }
        record.autoRecoveryAttemptTimestamps.removeAll { now - $0 > Self.perVideoWindow }
        record.proactiveTTLRefreshAttemptTimestamps.removeAll { now - $0 > Self.perVideoWindow }

        if let decision = minIntervalDecision(record: record, kind: kind, now: now) {
            records[videoId] = record
            return decision
        }
        if let decision = perVideoBudgetDecision(record: record, kind: kind, now: now) {
            records[videoId] = record
            return decision
        }

        // Record the attempt NOW, before returning .allowed.
        switch kind {
        case .player:
            record.attemptTimestamps.append(now)
            record.consecutivePlayerAttempts += 1
            record.lastPlayerAttemptTime = now
        case .autoRecovery:
            record.attemptTimestamps.append(now)
            record.lastAutoRecoveryAttemptTime = now
            record.autoRecoveryAttemptTimestamps.append(now)
        case .prefetch:
            record.attemptTimestamps.append(now)
            record.lastPrefetchAttemptTime = now
        case .proactiveTTLRefresh:
            record.lastProactiveTTLRefreshAttemptTime = now
            record.proactiveTTLRefreshAttemptTimestamps.append(now)
        }
        records[videoId] = record

        switch kind {
        case .autoRecovery:
            break // bypasses global accounting entirely
        case .proactiveTTLRefresh:
            globalProactiveTTLRefreshTimestamps.append(now)
        case .player, .prefetch:
            globalAttemptTimestamps.append(now)
        }

        return .allowed
    }

    /// Clears the `.player` exponential backoff (does not affect attempt
    /// counts — those are already recorded by `check`).
    public func onSuccess(_ videoId: String) {
        records[videoId]?.consecutivePlayerAttempts = 0
    }

    // MARK: - global lane

    /// `.autoRecovery` bypasses the shared global limit entirely — reactive
    /// recovery must never be blocked. `.proactiveTTLRefresh` has its own
    /// ceiling, separate from the shared player/prefetch lane.
    private func globalDecision(kind: RequestKind, now: Duration) -> Decision? {
        switch kind {
        case .autoRecovery:
            return nil
        case .proactiveTTLRefresh:
            globalProactiveTTLRefreshTimestamps.removeAll { now - $0 > Self.globalWindow }
            guard globalProactiveTTLRefreshTimestamps.count >= Self.maxProactiveGlobalPerMinute else { return nil }
            return .blocked(
                reason: "global proactive TTL refresh limit (\(Self.maxProactiveGlobalPerMinute) per minute)",
                retryAfter: retryAfter(for: globalProactiveTTLRefreshTimestamps, window: Self.globalWindow, now: now)
            )
        case .player, .prefetch:
            globalAttemptTimestamps.removeAll { now - $0 > Self.globalWindow }
            guard globalAttemptTimestamps.count >= Self.maxGlobalAttempts else { return nil }
            return .blocked(
                reason: "global limit (\(Self.maxGlobalAttempts) per minute)",
                retryAfter: retryAfter(for: globalAttemptTimestamps, window: Self.globalWindow, now: now)
            )
        }
    }

    // MARK: - per-video lane

    /// Minimum interval between same-video-same-kind attempts. The first
    /// `.autoRecovery` attempt for a video skips this check.
    private func minIntervalDecision(record: VideoRecord, kind: RequestKind, now: Duration) -> Decision? {
        let lastAttempt: Duration?
        switch kind {
        case .player: lastAttempt = record.lastPlayerAttemptTime
        case .autoRecovery: lastAttempt = record.lastAutoRecoveryAttemptTime
        case .prefetch: lastAttempt = record.lastPrefetchAttemptTime
        case .proactiveTTLRefresh: lastAttempt = record.lastProactiveTTLRefreshAttemptTime
        }
        guard let lastAttempt else { return nil }

        let sinceLastAttempt = now - lastAttempt
        guard sinceLastAttempt < Self.minExtractionInterval else { return nil }

        if kind == .autoRecovery, record.autoRecoveryAttemptTimestamps.isEmpty {
            return nil
        }
        return .delayed(Self.minExtractionInterval - sinceLastAttempt, reason: "minimum interval")
    }

    private func perVideoBudgetDecision(record: VideoRecord, kind: RequestKind, now: Duration) -> Decision? {
        let attemptsInWindow = record.attemptTimestamps.count

        switch kind {
        case .autoRecovery:
            // Reserved budget: proceeds even if the shared per-video total is
            // already exhausted, unless auto-recovery's own reserve AND the
            // shared total are both exhausted.
            guard record.autoRecoveryAttemptTimestamps.count >= Self.autoRecoveryReserved else { return nil }
            guard attemptsInWindow >= Self.maxAttemptsPerVideo else { return nil }
            return .blocked(
                reason: "auto-recovery limit (\(Self.autoRecoveryReserved) per window)",
                retryAfter: retryAfter(for: record.attemptTimestamps, window: Self.perVideoWindow, now: now)
            )

        case .player:
            if attemptsInWindow >= Self.maxAttemptsPerVideo {
                return .blocked(
                    reason: "per-video limit (\(Self.maxAttemptsPerVideo) in 5min)",
                    retryAfter: retryAfter(for: record.attemptTimestamps, window: Self.perVideoWindow, now: now)
                )
            }
            guard record.consecutivePlayerAttempts > 0 else { return nil }
            let backoff = Self.backoffDelay(forConsecutiveAttempts: record.consecutivePlayerAttempts)
            let sinceLastAttempt = now - (record.lastPlayerAttemptTime ?? now)
            guard sinceLastAttempt < backoff else { return nil }
            return .delayed(backoff - sinceLastAttempt, reason: "exponential backoff")

        case .prefetch:
            // Lowest priority: blocked once budget pressure exists, to
            // preserve room for manual/recovery.
            guard attemptsInWindow >= Self.maxAttemptsPerVideo - 1 else { return nil }
            return .blocked(reason: "prefetch blocked (budget reserved)", retryAfter: Self.perVideoWindow)

        case .proactiveTTLRefresh:
            guard record.proactiveTTLRefreshAttemptTimestamps.count >= Self.proactiveTTLReserved else { return nil }
            return .blocked(
                reason: "proactive TTL refresh limit (\(Self.proactiveTTLReserved) per window)",
                retryAfter: retryAfter(for: record.proactiveTTLRefreshAttemptTimestamps, window: Self.perVideoWindow, now: now)
            )
        }
    }

    private func retryAfter(for timestamps: [Duration], window: Duration, now: Duration) -> Duration {
        let oldest = timestamps.min() ?? now
        return max(.zero, oldest + window - now)
    }

    /// Exponential backoff on consecutive `.player` attempts: 2s, 4s, 8s,
    /// 16s, 32s, capped at 60s.
    private static func backoffDelay(forConsecutiveAttempts attempts: Int) -> Duration {
        let exponent = min(attempts - 1, 5)
        let seconds = min(2 << exponent, 60)
        return .seconds(seconds)
    }
}
