import Foundation
import SwiftUI

/// Task 9 (`docs/superpowers/plans/2026-08-24-ios-phase2b1-player-core.md`, spec §6.6): the pure
/// `StreamState -> copy` mapping behind `PlayerStateView`, so the state/copy/announcement rules are
/// testable without SwiftUI (`PlayerStateViewTests`). `.ready`/`.rung2Progressive` are never passed
/// in -- Task 7's identity note (`PlayerScreen.swift`'s `.ready, .rung2Progressive` branch) keeps
/// both playable rungs inside `PlayerScreen`'s own `PlayerHostView`-building switch case; this type
/// only covers the six non-playable states routed to the `default` case.
enum PlayerStateCopy {
    struct Copy: Equatable {
        /// Already-localized display text -- never a raw key. The switch's old placeholder
        /// (`Text(messageKey)`) rendered the literal string "player_error_message" on screen; this
        /// is the fix.
        let message: String
        let showsRetry: Bool
        /// Whether a transition INTO this state posts a VoiceOver announcement of `message` (spec
        /// §6.6 "Transitions"). `.idle`/`.loading`/offline/`.contentUnavailable` don't announce --
        /// none of those is a change worth interrupting VoiceOver for.
        let announces: Bool
    }

    static func map(_ state: StreamState, isOnline: Bool, locale: Locale = .current, now: Date = Date()) -> Copy {
        switch state {
        case .ready, .rung2Progressive:
            preconditionFailure("PlayerStateCopy never maps a playable state -- PlayerScreen keeps " +
                                 "both rungs in its own switch branch (Task 7 identity note)")
        case .idle, .loading:
            // Offline gate (spec §6.6): while offline, `.idle`/`.loading` collapse to ONE state with
            // a Retry escape hatch instead of a spinner that sits there until the resolve call
            // eventually times out.
            return isOnline
                ? Copy(message: String(localized: "loading"), showsRetry: false, announces: false)
                : Copy(message: String(localized: "connectivity_offline_banner"), showsRetry: true, announces: false)
        case .error(let messageKey):
            // I2 (B1 final review): the offline gate covers `.error` too. Offline, the resolve
            // fails within ~300 ms and used to land on the generic error copy -- so offline was
            // TWO surfaces (a blink of spinner, then "there was a problem"), where spec §6.6 wants
            // ONE state that names the real cause and offers Retry.
            guard isOnline else {
                return Copy(message: String(localized: "connectivity_offline_banner"), showsRetry: true, announces: false)
            }
            return Copy(message: String(localized: String.LocalizationValue(messageKey)), showsRetry: true, announces: true)
        case .unplayable(let messageKey):
            // No Retry (ruling 14, and the B3 task 5 live pass that found the Retry): the ladder
            // that produced this refusal produces it again. Announced, because it is a real change
            // of state a VoiceOver user otherwise only learns from silence.
            return Copy(message: String(localized: String.LocalizationValue(messageKey)),
                        showsRetry: false, announces: true)
        case .contentUnavailable:
            // Ruling 14: one non-retryable "not playable" surface for every terminal reason
            // (age-restricted/geo-blocked/private/removed/unavailable) -- `player_stream_unavailable`
            // is the closest existing catalog copy to spec's "This video isn't available".
            return Copy(message: String(localized: "player_stream_unavailable"), showsRetry: false, announces: false)
        case .cooldown(let until):
            let remaining = until.timeIntervalSince(now)
            return Copy(message: cooldownText(remainingSeconds: max(0, remaining), locale: locale),
                       showsRetry: remaining <= 0, announces: remaining > 0)
        case .embed:
            // B3 task 4: the embed rung has its own `PlayerScreen` branch (`EmbedRungView`) and must
            // never reach this shared state view -- ONLINE. Offline it must: loading a `WKWebView`
            // with no network paints a black frame under a caption claiming something is playing, so
            // the `.embed` ENTRY is gated on connectivity and lands on the same single offline
            // surface `.idle`/`.loading`/`.error` already collapse to (I2, B1 final review).
            guard isOnline else {
                return Copy(message: String(localized: "connectivity_offline_banner"), showsRetry: true, announces: false)
            }
            preconditionFailure("PlayerStateCopy never maps the online embed rung -- PlayerScreen " +
                                 "mounts EmbedRungView for it (B3 task 4)")
        case .recoveryExhausted:
            // T7-M3 (deferred-minors.md, MUST): the manual Retry escape hatch -- this used to be a
            // bare `ProgressView` dead end.
            return Copy(message: String(localized: "player_recovery_exhausted_message"), showsRetry: true, announces: true)
        }
    }

    /// "Try again in 0:45" -- fixed `until`/`now` in, deterministic text out (pure, unit-tested).
    static func cooldownText(until: Date, now: Date, locale: Locale = .current) -> String {
        cooldownText(remainingSeconds: max(0, until.timeIntervalSince(now)), locale: locale)
    }

    /// `Format.duration` (Android parity, always Western digits per its own doc comment) reused
    /// rather than a second duration formatter -- ponytail ladder rung 2.
    private static func cooldownText(remainingSeconds: TimeInterval, locale: Locale) -> String {
        Format.localizedFormat("player_cooldown_retry", locale: locale, Format.duration(Int(remainingSeconds.rounded())))
    }
}

/// Renders every non-playable `StreamState` (spec §6.6): a thumbnail+spinner while loading, the
/// offline card, and localized error/unavailable/cooldown/recoveryExhausted copy with a Retry
/// escape hatch where the state allows one. Mounted from ONE `default` branch in
/// `PlayerScreen.stateView` (never `.ready`/`.rung2Progressive`), so it keeps ONE view identity
/// across e.g. `.loading -> .error` -- which is what makes the cross-dissolve animation and the
/// `.onChange(of:)` transition announcement below actually fire on every real transition, instead
/// of a fresh view mounting with no "previous state" to compare against.
struct PlayerStateView: View {
    let state: StreamState
    let isOnline: Bool
    let thumbnailURL: URL?
    let retry: () -> Void

    @Environment(\.accessibilityReduceMotion) private var reduceMotion
    @Environment(\.locale) private var locale

    var body: some View {
        Group {
            if case .cooldown = state {
                // The only state whose copy is time-dependent -- `TimelineView` re-evaluates
                // `content(now:)` once a second so the countdown text AND the Retry button's
                // appearance (once `remaining` hits zero) both stay live with no extra `@State`.
                TimelineView(.periodic(from: .now, by: 1)) { context in content(now: context.date) }
            } else {
                content(now: Date())
            }
        }
        .transition(reduceMotion ? .identity : .opacity)
        .animation(reduceMotion ? nil : .easeInOut, value: state)
        .onAppear { announceIfNeeded() }
        .onChange(of: state) { _, _ in announceIfNeeded() }
    }

    /// Task 10 carry-in (I1): built on the shared `EmptyStateView`/`StateButton`
    /// (`StateViews.swift`) instead of retyping icon/message/button here -- the thumbnail+spinner
    /// case rides the `customIcon` hook, and `combinesMessageWithIcon: false` keeps the message
    /// and retry button individually queryable (`player.state.message`/`.countdown`/
    /// `.retryButton`, which `ScreenshotTests` anchors on directly).
    @ViewBuilder
    private func content(now: Date) -> some View {
        let copy = PlayerStateCopy.map(state, isOnline: isOnline, locale: locale, now: now)
        // ONE action slot, and it is always Retry: the only other control this view ever offered
        // was the YouTube hand-off, removed by owner directive 2026-08-27. A terminal state shows
        // no button at all rather than an escape route out of the app.
        let action: (title: String, run: () -> Void)? = copy.showsRetry
            ? (title: String(localized: "retry"), run: retry)
            : nil
        EmptyStateView(
            systemImage: "exclamationmark.triangle.fill",
            iconColor: .accentRed,
            message: copy.message,
            action: action,
            customIcon: (Self.isLoadingLike(state) && isOnline) ? AnyView(loadingIcon) : nil,
            messageAccessibilityIdentifier: Self.isCooldown(state) ? "player.state.countdown" : "player.state.message",
            actionAccessibilityIdentifier: "player.state.retryButton",
            combinesMessageWithIcon: false
        )
        .background(Color.background)
    }

    private var loadingIcon: some View {
        ZStack {
            RemoteImage(url: thumbnailURL, contentMode: .fit)
                .frame(width: 240, height: 135)
                .clipShape(RoundedRectangle(cornerRadius: Radius.thumbnail))
            ProgressView().tint(.brand)
        }
    }

    private func announceIfNeeded() {
        let copy = PlayerStateCopy.map(state, isOnline: isOnline, locale: locale, now: Date())
        guard copy.announces else { return }
        AccessibilityNotification.Announcement(copy.message).post()
    }

    private static func isLoadingLike(_ state: StreamState) -> Bool {
        switch state {
        case .idle, .loading: return true
        default: return false
        }
    }

    private static func isCooldown(_ state: StreamState) -> Bool {
        if case .cooldown = state { return true }
        return false
    }
}

#if DEBUG
#Preview("Error") {
    PlayerStateView(state: .error(messageKey: "player_error_message"), isOnline: true, thumbnailURL: nil) {}
}

#Preview("Cooldown") {
    PlayerStateView(state: .cooldown(until: Date().addingTimeInterval(45)), isOnline: true, thumbnailURL: nil) {}
}
#endif

extension View {
    /// Task 9 + B3 task 4 + B4 task 3: the two announcements `PlayerStateView` can't make itself,
    /// since neither `.rung2Progressive` nor `.embed` mounts it. ONE site, applied by both
    /// `PlayerScreen` and `ShortsScreen` (plan §6.11 "every rung transition announced") --
    /// `EmbedRungView` deliberately posts nothing of its own. `.onChange` fires only on a real
    /// transition, so entering `.embed` announces exactly once.
    func rungAnnouncements(state: StreamState?, isOnline: Bool) -> some View {
        onChange(of: state) { _, newValue in
            if let text = PlayerScreen.transitionAnnouncement(for: newValue, isOnline: isOnline) {
                AccessibilityNotification.Announcement(text).post()
            }
        }
    }
}
