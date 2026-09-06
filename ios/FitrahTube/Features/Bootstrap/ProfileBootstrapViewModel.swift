import Foundation
import Observation

/// Drives `ProfileBootstrapScreen` (`ProfileBootstrapViewModel.kt`). Firebase stays behind
/// `AuthClient`, the backend behind `AccountClient`, and both the clock and the calendar are
/// injected so the age gate and the wire date are pure functions of what a test hands over.
@MainActor @Observable final class ProfileBootstrapViewModel {

    /// Where the screen goes once the form is settled. `.main` is advisory — `RootView` recomputes
    /// `SplashRouter.outcome` off the refreshed session and renders the shell itself.
    nonisolated enum Nav: Sendable, Equatable { case idle, main }

    nonisolated struct UiState: Equatable {
        var displayName = ""
        var dateOfBirth: Date?
        /// The NATIONAL portion as typed. The screen renders a fixed leading "+", so E.164 is
        /// assembled here (`e164`) rather than being something the user can get wrong.
        var phoneNumber = ""
        var password = ""
        var passwordConfirm = ""
        /// True when the signed-in account has no password provider: attaching one during bootstrap
        /// is what lets the same email later reach the admin dashboard from a browser
        /// (`ProfileBootstrapViewModel.kt:54-61`).
        var passwordRequired = false
        /// Set once `POST /profile` has returned 200. The backend 409s a second POST, so a retry
        /// after a failed password attach must re-run ONLY the password step.
        var profileSaved = false
        var isLoading = false
        var error: BootstrapError?
    }

    private let account: AccountClient
    private let auth: any AuthClient
    private let session: AccountSession
    private let calendar: Calendar
    private let today: @Sendable () -> Date

    private(set) var state = UiState()
    private(set) var nav: Nav = .idle

    init(account: AccountClient, auth: any AuthClient, session: AccountSession,
         calendar: Calendar = .current, today: @escaping @Sendable () -> Date = { Date() }) {
        self.account = account
        self.auth = auth
        self.session = session
        self.calendar = calendar
        self.today = today
    }

    // MARK: - Bindings

    // Every setter clears the standing error: a message about the previous attempt has nothing to
    // say about the text now on screen (`onDisplayNameChanged` and friends).
    /// Capped at 40 (`android:maxLength="40"`), so a paste is capped exactly as typing is and the
    /// server's `@Size(max = 40)` can never be the thing that reports it.
    var displayName: String {
        get { state.displayName }
        set {
            state.displayName = String(newValue.prefix(BootstrapValidator.maxNameLength))
            state.error = nil
        }
    }

    var dateOfBirth: Date? {
        get { state.dateOfBirth }
        set { state.dateOfBirth = newValue; state.error = nil }
    }

    var phoneNumber: String {
        get { state.phoneNumber }
        // `BootstrapValidator.normalizedDigits` — the same rule `EditPhoneSheet` applies, spelled
        // once beside the pattern it feeds (Stage 1 / B3a).
        set {
            state.phoneNumber = BootstrapValidator.normalizedDigits(newValue)
            state.error = nil
        }
    }

    var password: String {
        get { state.password }
        set { state.password = newValue; state.error = nil }
    }

    var passwordConfirm: String {
        get { state.passwordConfirm }
        set { state.passwordConfirm = newValue; state.error = nil }
    }

    /// E.164 as the server sees it.
    var e164: String { BootstrapValidator.e164(state.phoneNumber) }

    // MARK: - Validation (ONE validator, both consumers)

    func firstError() -> BootstrapError? {
        BootstrapValidator.firstError(name: state.displayName, dob: state.dateOfBirth, phone: e164,
                                      password: state.password, passwordConfirm: state.passwordConfirm,
                                      passwordRequired: state.passwordRequired,
                                      today: today(), calendar: calendar)
    }

    /// Drives the submit button's enabled state.
    var isFormValid: Bool { firstError() == nil }

    // MARK: - Lifecycle

    /// The screen's `.task`. `currentUser()` is async on iOS, so the password requirement cannot be
    /// derived at construction the way Android's fragment does it.
    func load() async {
        guard let user = await auth.currentUser() else { return }
        state.passwordRequired = !user.hasPasswordProvider
    }

    /// Two-phase commit. Phase one is `POST /profile`, latched by `profileSaved`; phase two attaches
    /// the password. A failure in phase two leaves the user on this screen with the latch set, so the
    /// retry sends only the password (`ProfileBootstrapViewModel.kt:161-217`).
    func submit() async {
        guard !state.isLoading else { return }   // de-dupe rapid double-taps
        if let error = firstError() {
            state.error = error
            return
        }
        guard let dob = state.dateOfBirth else { return }   // firstError() guarantees non-nil
        state.isLoading = true
        state.error = nil

        if !state.profileSaved {
            do {
                _ = try await account.completeProfile(
                    displayName: state.displayName.trimmingCharacters(in: .whitespacesAndNewlines),
                    dateOfBirth: Self.wireDate(dob, calendar: calendar),
                    phoneNumber: e164)
                state.profileSaved = true
            } catch {
                // Stage 3 / M4: both of these were decoded, thrown, tested — and then collapsed
                // into "couldn't save your profile", which for the 409 is a dead end BY
                // CONSTRUCTION: the server says the form is already done, and the only thing that
                // could move the user on is the `/me` re-read this arm never issued, so the screen
                // repeated the same refusal forever. A 403 `EMAIL_NOT_VERIFIED` is the same shape —
                // the router lands on verification once the session is re-read.
                switch error {
                case .profileAlreadyCompleted, .emailNotVerified:
                    await session.refresh()
                    state.isLoading = false
                    nav = .main
                    return
                case .ageIneligible:
                    state.isLoading = false
                    // R7-P1 #3: the teardown runs WITH the verdict, not when the user acknowledges
                    // it. The server revokes the refresh tokens and DISABLES the Firebase account
                    // before answering (`AccountProfileService.java:130,140`), so a session kept
                    // alive past this point is one every later request 401s on -- and the refused
                    // forced mint maps `.userDisabled` to `.blocked`, which replaced the age
                    // message with "your account has been blocked" and re-routed off `.signedOut`
                    // so the Firebase delete never ran at all. `RootView` presents the terminal
                    // screen on `session.isAgeIneligible`, over whatever the outcome now resolves
                    // to; the terminal screen IS the message, so no inline error either.
                    await session.terminateAgeIneligible()
                    return
                default:
                    state.isLoading = false
                    state.error = .saveFailed
                    return
                }
            }
        }

        if state.passwordRequired {
            // The session can expire between the two phases. The profile is already committed, so
            // the only way back is a fresh sign-in — say what happened, never why.
            guard await auth.currentUser() != nil else {
                state.isLoading = false
                state.error = .passwordSetFailed
                return
            }
            do {
                try await auth.updatePassword(state.password)
            } catch {
                state.isLoading = false
                state.error = .passwordSetFailed
                return
            }
        }

        // `RootView` routes off `AccountSession.state.me?.status` and NOTHING else re-reads `/me`, so
        // without this the account stays `pending_profile` and the screen it was just released from
        // renders again.
        await session.refresh()
        state.isLoading = false
        nav = .main
    }

    /// ISO `yyyy-MM-dd`, in ASCII digits, for the day the picker SHOWED.
    ///
    /// `String(format:)` takes no locale and so never localizes its digits — a `DateFormatter` on
    /// `Locale.current` would emit Arabic-Indic digits for an `ar` user and the backend would 400 on
    /// every submit. The TIME ZONE is the picker's (`Calendar.current` in the app), NOT UTC: a user
    /// at UTC+13 picking 2000-01-01 holds an instant that is still 1999-12-31 in UTC, and sending
    /// that would make every such account a day younger than it is. The NUMBERING is always
    /// Gregorian (`BootstrapValidator.gregorian`) — the picker may render Hijri, the wire may not.
    nonisolated static func wireDate(_ date: Date, calendar: Calendar) -> String {
        let parts = BootstrapValidator.gregorian(calendar).dateComponents([.year, .month, .day], from: date)
        return String(format: "%04d-%02d-%02d", parts.year ?? 0, parts.month ?? 0, parts.day ?? 0)
    }
}

extension BootstrapError {
    /// The inline message under the form. `passwordSetFailed`/`saveFailed` say WHAT, never why.
    var messageKey: String {
        switch self {
        case .invalidName: "bootstrap_error_invalid_name"
        case .invalidDOB: "bootstrap_error_invalid_dob"
        case .underAge: "bootstrap_error_under_age"
        case .invalidPhone: "bootstrap_error_invalid_phone"
        case .invalidPassword: "bootstrap_error_invalid_password"
        case .passwordMismatch: "bootstrap_error_password_mismatch"
        case .passwordSetFailed: "bootstrap_error_password_set_failed"
        case .saveFailed: "bootstrap_error_save_failed"
        }
    }
}
