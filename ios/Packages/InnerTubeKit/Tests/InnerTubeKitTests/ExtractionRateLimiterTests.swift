import Testing
@testable import InnerTubeKit

@Suite struct ExtractionRateLimiterTests {
    /// Spaces consecutive `.player` attempts 301s apart (just past the 300s
    /// per-video window) so each prior timestamp ages out before the next
    /// check — this builds `consecutivePlayerAttempts` up to `count` without
    /// ever tripping the 3-per-5min per-video budget. Returns the `now` used
    /// for the final (count-th) attempt.
    @discardableResult
    private func buildConsecutivePlayerAttempts(
        _ count: Int, videoId: String, limiter: ExtractionRateLimiter
    ) async -> Duration {
        var now = Duration.zero
        for i in 0..<count {
            if i > 0 { now += .seconds(301) }
            let decision = await limiter.check(videoId, kind: .player, now: now)
            #expect(decision == .allowed, "attempt \(i + 1) should be allowed")
        }
        return now
    }

    @Test func firstAttemptIsAllowed() async {
        let limiter = ExtractionRateLimiter()
        let decision = await limiter.check("abc123def45", kind: .player, now: .zero)
        #expect(decision == .allowed)
    }

    @Test func secondAttemptWithinMinIntervalIsDelayed() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .player, now: .zero)

        let decision = await limiter.check("abc123def45", kind: .player, now: .seconds(15))

        #expect(decision == .delayed(.seconds(15), reason: "minimum interval"))
    }

    @Test func thirdPlayerAttemptWithinFiveMinuteWindowIsBlocked() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(0))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(31))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(62))
        await limiter.onSuccess("abc123def45")

        let decision = await limiter.check("abc123def45", kind: .player, now: .seconds(93))

        #expect(decision == .blocked(reason: "per-video limit (3 in 5min)", retryAfter: .seconds(207)))
    }

    @Test func prefetchBlockedAtAttemptsEqualsMaxMinusOneWhileAutoRecoveryStillAllowed() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(0))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(31))
        await limiter.onSuccess("abc123def45")
        // attemptsInWindow == 2 == MAX_ATTEMPTS_PER_VIDEO(3) - 1.

        let prefetchDecision = await limiter.check("abc123def45", kind: .prefetch, now: .seconds(62))
        #expect(prefetchDecision == .blocked(reason: "prefetch blocked (budget reserved)", retryAfter: .seconds(300)))

        let recoveryDecision = await limiter.check("abc123def45", kind: .autoRecovery, now: .seconds(62))
        #expect(recoveryDecision == .allowed)
    }

    @Test func globalEleventhPlayerOrPrefetchInWindowIsBlockedButAutoRecoveryBypasses() async {
        let limiter = ExtractionRateLimiter()
        for i in 0..<10 {
            let decision = await limiter.check("vid\(i)abcde12", kind: .player, now: .zero)
            #expect(decision == .allowed, "attempt \(i) should be allowed")
        }

        let eleventh = await limiter.check("vid10abcde12", kind: .player, now: .zero)
        #expect(eleventh == .blocked(reason: "global limit (10 per minute)", retryAfter: .seconds(60)))

        let prefetchBlocked = await limiter.check("vid11abcde12", kind: .prefetch, now: .zero)
        #expect(prefetchBlocked == .blocked(reason: "global limit (10 per minute)", retryAfter: .seconds(60)))

        let recovery = await limiter.check("vid12abcde12", kind: .autoRecovery, now: .zero)
        #expect(recovery == .allowed)
    }

    @Test func playerBackoffReaches32SecondsAtFifthConsecutiveAttempt() async {
        let limiter = ExtractionRateLimiter()
        let last = await buildConsecutivePlayerAttempts(5, videoId: "abc123def45", limiter: limiter)

        let decision = await limiter.check("abc123def45", kind: .player, now: last + .seconds(31))

        #expect(decision == .delayed(.seconds(1), reason: "exponential backoff"))
    }

    @Test func playerBackoffCapsAt60SecondsAtSixthConsecutiveAttempt() async {
        let limiter = ExtractionRateLimiter()
        let last = await buildConsecutivePlayerAttempts(6, videoId: "abc123def45", limiter: limiter)

        let decision = await limiter.check("abc123def45", kind: .player, now: last + .seconds(31))

        #expect(decision == .delayed(.seconds(29), reason: "exponential backoff"))
    }

    @Test func onSuccessClearsPlayerBackoff() async {
        let limiter = ExtractionRateLimiter()
        let last = await buildConsecutivePlayerAttempts(5, videoId: "abc123def45", limiter: limiter)

        await limiter.onSuccess("abc123def45")

        let decision = await limiter.check("abc123def45", kind: .player, now: last + .seconds(31))
        #expect(decision == .allowed)
    }

    @Test func autoRecoveryReservedBudgetAllowedTwiceThenBlockedAfterPlayerExhausted() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(0))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(31))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(62))
        await limiter.onSuccess("abc123def45")
        // Player is now exhausted (3/3 in the 5min window).

        let first = await limiter.check("abc123def45", kind: .autoRecovery, now: .seconds(93))
        #expect(first == .allowed, "1st reserved auto-recovery attempt should be allowed")

        let second = await limiter.check("abc123def45", kind: .autoRecovery, now: .seconds(124))
        #expect(second == .allowed, "2nd reserved auto-recovery attempt should be allowed")

        let third = await limiter.check("abc123def45", kind: .autoRecovery, now: .seconds(155))
        #expect(third == .blocked(reason: "auto-recovery limit (2 per window)", retryAfter: .seconds(145)))
    }

    @Test func proactiveTTLRefreshDoesNotConsumeSharedPerVideoBudget() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(0))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(31))
        await limiter.onSuccess("abc123def45")
        _ = await limiter.check("abc123def45", kind: .player, now: .seconds(62))
        await limiter.onSuccess("abc123def45")
        // Player is exhausted, but proactive TTL refresh has its own 2/video budget.

        let first = await limiter.check("abc123def45", kind: .proactiveTTLRefresh, now: .seconds(93))
        #expect(first == .allowed)

        let second = await limiter.check("abc123def45", kind: .proactiveTTLRefresh, now: .seconds(124))
        #expect(second == .allowed)

        let third = await limiter.check("abc123def45", kind: .proactiveTTLRefresh, now: .seconds(155))
        #expect(third == .blocked(reason: "proactive TTL refresh limit (2 per window)", retryAfter: .seconds(238)))
    }

    @Test func proactiveTTLRefreshHasOwnGlobalCeilingIndependentOfSharedGlobal() async {
        let limiter = ExtractionRateLimiter()
        for i in 0..<10 {
            let decision = await limiter.check("vid\(i)abcde12", kind: .player, now: .zero)
            #expect(decision == .allowed, "player attempt \(i) should be allowed")
        }
        // Shared global (player+prefetch) is now exhausted.
        let blockedPlayer = await limiter.check("vidXabcde123", kind: .player, now: .zero)
        guard case .blocked = blockedPlayer else {
            Issue.record("expected player blocked by exhausted shared global, got \(blockedPlayer)")
            return
        }

        for i in 0..<10 {
            let decision = await limiter.check("ttl\(i)abcde12", kind: .proactiveTTLRefresh, now: .zero)
            #expect(decision == .allowed, "proactive TTL refresh \(i) should be allowed on its own lane")
        }

        let eleventh = await limiter.check("ttl10abcde12", kind: .proactiveTTLRefresh, now: .zero)
        #expect(
            eleventh
                == .blocked(
                    reason: "global proactive TTL refresh limit (10 per minute)", retryAfter: .seconds(60)))
    }

    // MARK: - B9: min-interval delayed path, per kind's own last-attempt field

    @Test func secondPrefetchAttemptWithinMinIntervalIsDelayed() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .prefetch, now: .zero)

        let decision = await limiter.check("abc123def45", kind: .prefetch, now: .seconds(15))

        #expect(decision == .delayed(.seconds(15), reason: "minimum interval"))
    }

    @Test func secondProactiveTTLRefreshAttemptWithinMinIntervalIsDelayed() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .proactiveTTLRefresh, now: .zero)

        let decision = await limiter.check("abc123def45", kind: .proactiveTTLRefresh, now: .seconds(15))

        #expect(decision == .delayed(.seconds(15), reason: "minimum interval"))
    }

    @Test func nonFirstAutoRecoveryAttemptWithinMinIntervalIsDelayed() async {
        let limiter = ExtractionRateLimiter()
        _ = await limiter.check("abc123def45", kind: .autoRecovery, now: .zero)

        let decision = await limiter.check("abc123def45", kind: .autoRecovery, now: .seconds(15))

        #expect(decision == .delayed(.seconds(15), reason: "minimum interval"))
    }
}
