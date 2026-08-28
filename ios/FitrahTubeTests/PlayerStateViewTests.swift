import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Task 9 (`docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md`): the pure
/// `StreamState -> copy` mapping behind `PlayerStateView` -- spec §6.6's state table, plus the
/// offline gate and the cooldown countdown, all testable with no SwiftUI involved.
///
/// `.ready`/`.rung2Progressive` are deliberately NOT covered here: `PlayerStateCopy.map`
/// `preconditionFailure`s for both (Task 7's identity note keeps them in `PlayerScreen`'s own
/// switch branch), and Swift Testing has no supported way to assert a `preconditionFailure` without
/// crashing the whole test process -- the real guarantee that `PlayerStateView` never receives one
/// is `PlayerScreen`'s single `default` branch (structural, not something this file can probe).
@Suite(.perTest)
struct PlayerStateViewTests {
    private static let enUS = Locale(identifier: "en_US")

    private static let resolved = Resolved(
        stream: .progressive(url: URL(string: "https://example.com/a.mp4")!, label: "360p"),
        client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: nil)

    // MARK: - Loading / offline gate

    @Test func idleWhileOnlineShowsLoadingCopyWithNoRetryAndNoAnnouncement() {
        let copy = PlayerStateCopy.map(.idle, isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "loading"))
        #expect(copy.showsRetry == false)
        #expect(copy.announces == false)
    }

    @Test func loadingWhileOnlineShowsTheSameLoadingCopyAsIdle() {
        let copy = PlayerStateCopy.map(.loading, isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "loading"))
        #expect(copy.showsRetry == false)
    }

    @Test func idleWhileOfflineCollapsesToOneOfflineStateWithRetry() {
        // Spec §6.6: offline is ONE state, not a spinner cascading toward a resolve timeout.
        let copy = PlayerStateCopy.map(.idle, isOnline: false, locale: Self.enUS)
        #expect(copy.message == String(localized: "connectivity_offline_banner"))
        #expect(copy.showsRetry == true)
    }

    @Test func loadingWhileOfflineAlsoCollapsesToTheOfflineState() {
        let copy = PlayerStateCopy.map(.loading, isOnline: false, locale: Self.enUS)
        #expect(copy.message == String(localized: "connectivity_offline_banner"))
        #expect(copy.showsRetry == true)
    }

    // MARK: - Error / contentUnavailable

    @Test func errorResolvesItsMessageKeyToRealLocalizedTextWithRetryAndAnnounces() {
        // Pins the actual bug this task fixes: `PlayerScreen` used to render the raw key
        // ("player_error_message") as on-screen text instead of looking it up.
        let copy = PlayerStateCopy.map(.error(messageKey: "player_error_message"), isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "player_error_message"))
        #expect(copy.message != "player_error_message")
        #expect(copy.showsRetry == true)
        #expect(copy.announces == true)
    }

    /// B1 final review I2: the offline gate covers `.error` too. A resolve that fails ~300 ms in
    /// because the device is offline used to render the generic error copy, so offline was two
    /// surfaces (spinner-then-error) instead of spec §6.6's ONE.
    @Test func errorWhileOfflineCollapsesToTheOfflineStateWithRetry() {
        let copy = PlayerStateCopy.map(.error(messageKey: "player_error_message"), isOnline: false, locale: Self.enUS)
        #expect(copy.message == String(localized: "connectivity_offline_banner"))
        #expect(copy.showsRetry == true)
    }

    @Test func errorResolvesADifferentMessageKeyIndependently() {
        // T2-1: the ladder's generic terminal key, added via EXTRA_KEYS in B3 task 2.
        let copy = PlayerStateCopy.map(.error(messageKey: "player_error_generic"), isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "player_error_generic"))
    }

    @Test func contentUnavailableShowsNotAvailableCopyWithNoRetryAndNoAnnouncement() {
        let copy = PlayerStateCopy.map(.contentUnavailable, isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "player_stream_unavailable"))
        #expect(copy.showsRetry == false)
        #expect(copy.announces == false)
    }

    // MARK: - recoveryExhausted (T7-M3, MUST)

    @Test func recoveryExhaustedShowsItsMessageWithAManualRetryEscapeHatchAndAnnounces() {
        let copy = PlayerStateCopy.map(.recoveryExhausted(Self.resolved), isOnline: true, locale: Self.enUS)
        #expect(copy.message == String(localized: "player_recovery_exhausted_message"))
        #expect(copy.showsRetry == true)
        #expect(copy.announces == true)
    }

    // MARK: - Cooldown countdown (pure: fixed `until` + fixed `now`)

    @Test func cooldownShowsTheCountdownTextWithNoRetryYetAndAnnouncesOnce() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let until = now.addingTimeInterval(45)
        let copy = PlayerStateCopy.map(.cooldown(until: until), isOnline: true, locale: Self.enUS, now: now)
        #expect(copy.message == "Try again in 0:45")
        #expect(copy.showsRetry == false)
        #expect(copy.announces == true)
    }

    @Test func cooldownTextRoundsToTheNearestSecondAndFormatsMinutes() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let until = now.addingTimeInterval(90.4)
        #expect(PlayerStateCopy.cooldownText(until: until, now: now, locale: Self.enUS) == "Try again in 1:30")
    }

    @Test func cooldownAtExactlyZeroRemainingShowsRetryAndStopsAnnouncing() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let copy = PlayerStateCopy.map(.cooldown(until: now), isOnline: true, locale: Self.enUS, now: now)
        #expect(copy.showsRetry == true)
        #expect(copy.announces == false)
    }

    @Test func cooldownPastItsDeadlineAlsoShowsRetry() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let until = now.addingTimeInterval(-5)
        let copy = PlayerStateCopy.map(.cooldown(until: until), isOnline: true, locale: Self.enUS, now: now)
        #expect(copy.showsRetry == true)
        #expect(copy.message == "Try again in 0:00")
    }

    @Test func theQueueEndedStateHasRealCopyAndNoRetry() {
        // B5 task 2. No Retry: there is nothing to retry -- the queue is finished, and Back (or a
        // remaining Up Next row) is the exit. Announced, because a playlist ending is exactly spec
        // 6.6's "Transitions" case. Offline must not hijack it: a finished queue is finished with or
        // without a network.
        for online in [true, false] {
            let copy = PlayerStateCopy.map(.queueEnded, isOnline: online)
            #expect(copy.message == String(localized: "player_queue_ended"))
            #expect(copy.showsRetry == false)
            #expect(copy.announces)
        }
    }
}
