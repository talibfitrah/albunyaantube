import Foundation
import Testing
@testable import FitrahTube

/// Task 15. Every clock here is a fixed `Date` handed to the gate — no wall-clock, no sleeps: the
/// whole point of the type is that the TTL and both backoff ladders are decidable from data.
///
/// Mirrors `MeFeedRepository.kt:782-935`: TTL first, then backoff, both bypassed by `force`; a
/// timeout is a SOFT failure that never escalates; 429 and 5xx walk their own ladders and STAY at
/// the top step (`(errCount - 1).coerceAtMost(lastIndex)`).
@Suite(.perTest)
struct MeFeedRefreshGateTests {

    private static let now = Date(timeIntervalSince1970: 1_800_000_000)

    private static func state(successAgo: TimeInterval? = nil, errors: Int = 0,
                              backoffIn: TimeInterval? = nil) -> ChannelRefreshState {
        ChannelRefreshState(
            lastSuccessfulFetchAt: successAgo.map { now.addingTimeInterval(-$0) },
            lastAttemptAt: now.addingTimeInterval(-60),
            lastErrorMessage: errors > 0 ? "HTTP 429" : nil,
            consecutiveErrorCount: errors,
            backoffUntil: backoffIn.map { now.addingTimeInterval($0) }
        )
    }

    @Test func aChannelWithNoSuccessfulFetchIsFetched() {
        #expect(MeFeedRefreshGate.decide(nil, now: Self.now, force: false) == .fetch)
        #expect(MeFeedRefreshGate.decide(Self.state(), now: Self.now, force: false) == .fetch)
    }

    @Test func aSuccessInsideTheTTLSkipsAsFresh() {
        #expect(MeFeedRefreshGate.ttl == 30 * 60)
        #expect(MeFeedRefreshGate.decide(Self.state(successAgo: 60), now: Self.now, force: false) == .skipFresh)
        #expect(MeFeedRefreshGate.decide(Self.state(successAgo: MeFeedRefreshGate.ttl - 1),
                                         now: Self.now, force: false) == .skipFresh)
        // `now - last < CACHE_TTL_MS` is strict, so the TTL instant itself refetches.
        #expect(MeFeedRefreshGate.decide(Self.state(successAgo: MeFeedRefreshGate.ttl),
                                         now: Self.now, force: false) == .fetch)
    }

    /// Pull-to-refresh: the user asked, so neither skip applies.
    @Test func forceOverridesBothSkips() {
        #expect(MeFeedRefreshGate.decide(Self.state(successAgo: 60), now: Self.now, force: true) == .fetch)
        #expect(MeFeedRefreshGate.decide(Self.state(errors: 3, backoffIn: 3_600),
                                         now: Self.now, force: true) == .fetch)
    }

    /// While a backoff is active NO field is written at all — the decision is the whole effect, and
    /// the state the caller holds comes back byte-identical (`MeFeedRepository.kt:802-816`).
    @Test func anActiveBackoffSkipsAndWritesNoField() throws {
        let state = Self.state(successAgo: MeFeedRefreshGate.ttl * 2, errors: 3, backoffIn: 3_600)
        let encoder = JSONEncoder()
        encoder.outputFormatting = .sortedKeys
        let before = try encoder.encode(state)

        #expect(MeFeedRefreshGate.decide(state, now: Self.now, force: false) == .skipBackoff)

        #expect(try encoder.encode(state) == before)
    }

    @Test func anExpiredBackoffFetchesAgain() {
        #expect(MeFeedRefreshGate.decide(Self.state(successAgo: MeFeedRefreshGate.ttl * 2, errors: 3,
                                                    backoffIn: -1),
                                         now: Self.now, force: false) == .fetch)
    }

    /// The load-bearing one: ambient network jitter must never push a user onto a 24 h cooldown, so
    /// a timeout records the attempt and the message and leaves the counter and the backoff alone
    /// (`MeFeedRepository.kt:851-861`).
    @Test func aTimeoutRecordsTheAttemptWithoutEscalating() {
        let previous = Self.state(successAgo: 7_200, errors: 2, backoffIn: 600)
        let next = MeFeedRefreshGate.apply(.timeout, to: previous, now: Self.now)

        #expect(next.consecutiveErrorCount == 2)
        #expect(next.lastAttemptAt == Self.now)
        #expect(next.lastErrorMessage != nil)
        #expect(next.backoffUntil == previous.backoffUntil)
        #expect(next.lastSuccessfulFetchAt == previous.lastSuccessfulFetchAt)

        // Three timeouts in a row still leave the channel un-escalated.
        var repeated: ChannelRefreshState? = nil
        for _ in 0..<3 { repeated = MeFeedRefreshGate.apply(.timeout, to: repeated, now: Self.now) }
        #expect(repeated?.consecutiveErrorCount == 0)
        #expect(repeated?.backoffUntil == nil)
    }

    @Test func rateLimitedFailuresWalkTheHourFourHourDayLadderAndStayAtTheTop() {
        #expect(MeFeedRefreshGate.rateLimitedBackoffs == [3_600, 14_400, 86_400])

        var state: ChannelRefreshState? = nil
        var walked: [TimeInterval?] = []
        for _ in 0..<4 {
            state = MeFeedRefreshGate.apply(.httpError(429), to: state, now: Self.now)
            walked.append(state?.backoffUntil?.timeIntervalSince(Self.now))
        }
        #expect(walked == [3_600, 14_400, 86_400, 86_400])
        #expect(state?.consecutiveErrorCount == 4)
        #expect(state?.lastAttemptAt == Self.now)
    }

    @Test func serverErrorsAndTransportFailuresWalkTheFiveXXLadder() {
        #expect(MeFeedRefreshGate.serverErrorBackoffs == [300, 1_800, 7_200])

        var byStatus: ChannelRefreshState? = nil
        var byTransport: ChannelRefreshState? = nil
        var statusWalk: [TimeInterval?] = []
        var transportWalk: [TimeInterval?] = []
        for _ in 0..<4 {
            byStatus = MeFeedRefreshGate.apply(.httpError(503), to: byStatus, now: Self.now)
            byTransport = MeFeedRefreshGate.apply(.transport, to: byTransport, now: Self.now)
            statusWalk.append(byStatus?.backoffUntil?.timeIntervalSince(Self.now))
            transportWalk.append(byTransport?.backoffUntil?.timeIntervalSince(Self.now))
        }
        #expect(statusWalk == [300, 1_800, 7_200, 7_200])
        #expect(transportWalk == statusWalk)

        // A status on neither ladder still counts as an error but invents no cooldown of its own —
        // it preserves whatever backoff was already running (`MeFeedRepository.kt:902-905`).
        let unknown = MeFeedRefreshGate.apply(.httpError(404), to: Self.state(errors: 1, backoffIn: 600),
                                              now: Self.now)
        #expect(unknown.consecutiveErrorCount == 2)
        #expect(unknown.backoffUntil == Self.now.addingTimeInterval(600))
    }

    @Test func aSuccessClearsTheErrorCountMessageAndBackoff() {
        let next = MeFeedRefreshGate.apply(.success, to: Self.state(successAgo: 86_400, errors: 3,
                                                                    backoffIn: 3_600),
                                           now: Self.now)
        #expect(next.lastSuccessfulFetchAt == Self.now)
        #expect(next.lastAttemptAt == Self.now)
        #expect(next.lastErrorMessage == nil)
        #expect(next.consecutiveErrorCount == 0)
        #expect(next.backoffUntil == nil)
        // And the channel is immediately fresh again.
        #expect(MeFeedRefreshGate.decide(next, now: Self.now, force: false) == .skipFresh)
    }
}
