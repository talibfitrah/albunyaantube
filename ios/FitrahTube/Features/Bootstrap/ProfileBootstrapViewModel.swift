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
        /// Fields the user has changed. A seed (`load()`) or a write of the same value (a binding
        /// echo) is not a touch (`shownError`).
        var touched: Set<BootstrapField> = []
        /// Fields the user has LEFT at least once (1.0.1, Android's `onFieldLeft`): for the
        /// `leaveFirst` fields, only then does the field's own error sit under it.
        var left: Set<BootstrapField> = []
        /// True when the signed-in account has no password provider: attaching one during bootstrap
        /// is what lets the same email later reach the admin dashboard from a browser
        /// (`ProfileBootstrapViewModel.kt:54-61`). Never for an Apple account — Apple's HIG: "Don't
        /// ask people to supply a password" to a Sign in with Apple user.
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
    /// server's `@Size(max = 40)` can never be the thing that reports it. R8-P1: capped by
    /// `BootstrapValidator.clamped(name:)`, i.e. in the UTF-16 units the GATE counts — a grapheme
    /// `prefix` was a second, looser rule, and the names it let through disabled Continue for good
    /// with no message.
    var displayName: String {
        get { state.displayName }
        set { edit(\.displayName, BootstrapValidator.clamped(name: newValue), .name) }
    }

    var dateOfBirth: Date? {
        get { state.dateOfBirth }
        set { state.dateOfBirth = newValue; state.touched.insert(.dob); state.error = nil }
    }

    var phoneNumber: String {
        get { state.phoneNumber }
        // `BootstrapValidator.normalizedDigits` — the same rule `EditPhoneSheet` applies, spelled
        // once beside the pattern it feeds (Stage 1 / B3a).
        set { edit(\.phoneNumber, BootstrapValidator.normalizedDigits(newValue), .phone) }
    }

    var password: String {
        get { state.password }
        set { edit(\.password, newValue, .password) }
    }

    var passwordConfirm: String {
        get { state.passwordConfirm }
        set { edit(\.passwordConfirm, newValue, .confirm) }
    }

    /// The screen's focus moved off `field`.
    func leave(_ field: BootstrapField) { state.left.insert(field) }

    /// One text write: a changed value touches its field (`shownError`).
    private func edit(_ keyPath: WritableKeyPath<UiState, String>, _ value: String, _ field: BootstrapField) {
        if state[keyPath: keyPath] != value { state.touched.insert(field) }
        state[keyPath: keyPath] = value
        state.error = nil
    }

    /// E.164 as the server sees it, or nil for an empty field: the phone is optional (owner ruling
    /// 2026-09-27), and an absent number is left out of the request — never "" (the server's
    /// `@Pattern` refuses it) and never the bare "+" the fixed prefix would assemble.
    var e164: String? { state.phoneNumber.isEmpty ? nil : BootstrapValidator.e164(state.phoneNumber) }

    // MARK: - Validation (ONE validator, both consumers)

    func firstError() -> BootstrapError? {
        BootstrapValidator.firstError(name: state.displayName, dob: state.dateOfBirth, phone: e164,
                                      password: state.password, passwordConfirm: state.passwordConfirm,
                                      passwordRequired: state.passwordRequired,
                                      today: today(), calendar: calendar)
    }

    /// Drives the submit button's enabled state.
    var isFormValid: Bool { firstError() == nil }

    /// What the screen says (Android's `shownError`): a failed submit's reason, else — once the
    /// user has touched anything — what still keeps Continue disabled. Without the second half
    /// every refusal was silent (owner report 2026-09-27): `state.error` is written only by
    /// `submit()`, which a disabled button never reaches. The error sits ON its field once that
    /// field was touched — or, for a `leaveFirst` field, once it was LEFT (1.0.1: "touched" put the
    /// password rule there at the first keystroke) — otherwise by Continue. An empty confirmation,
    /// or one still a prefix of the password while its field is focused, is "confirm your
    /// password"; any other is a mismatch (Android parity).
    var shownError: ShownError? {
        if let error = state.error { return ShownError(error: error, onField: true) }
        guard !state.touched.isEmpty, let error = firstError() else { return nil }
        let onField = error.field.map {
            Self.leaveFirst.contains($0) ? state.left.contains($0) : state.touched.contains($0)
        } ?? false
        let confirm = state.passwordConfirm
        let unfinished = error == .passwordMismatch
            && (confirm.isEmpty || (!state.left.contains(.confirm) && state.password.hasPrefix(confirm)))
        return ShownError(error: unfinished ? .confirmPassword : error, onField: onField)
    }

    /// Android's `LEAVE_FIRST`: the typed fields whose own error stays off the field while the user
    /// is still in it. The name is judged as it is changed; a picked date is a finished answer.
    private static let leaveFirst: Set<BootstrapField> = [.phone, .password, .confirm]

    // MARK: - Lifecycle

    /// The screen's `.task`. `currentUser()` is async on iOS, so the password requirement cannot be
    /// derived at construction the way Android's fragment does it.
    ///
    /// Guideline 4.0: the name a provider already shared (Apple's first authorization, Google's
    /// profile) seeds an EMPTY field — Android's `seedDisplayName` — so it is never asked for twice,
    /// and a name the user has since edited survives the next appearance.
    func load() async {
        guard let user = await auth.currentUser() else { return }
        state.passwordRequired = !user.hasPasswordProvider && !user.hasAppleProvider
        if state.displayName.isEmpty, let name = user.displayName {
            state.displayName = BootstrapValidator.clamped(name: name)
        }
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
            // Whose request this is, read BEFORE it goes out: the 422 can land after this account
            // was signed out and another one arrived (the task outlives the screen).
            let issuedFor = session.currentUid
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
                    await session.terminateAgeIneligible(for: issuedFor)
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

    /// R9-P2: the ONE way off this screen once the profile is committed and the password step keeps
    /// failing.
    ///
    /// `ProfileBootstrapScreen` is a ROOT destination with `.navigationBarBackButtonHidden()` and no
    /// tab bar, and `profileSaved` sends every later `submit()` straight back into the password
    /// block — where a Google/Apple session too old to write throws `requiresRecentLogin` on every
    /// retry, forever. Waiting for the session to expire produces the SAME message, not an exit, so
    /// the only other way out was force-quitting the app, which changes nothing. Signing out drops
    /// the session and `SplashRouter` re-routes to sign-in, where a fresh sign-in is recent enough
    /// to attach the password. `session.signOut()`, the path the Me kebab and Settings already use
    /// — no new teardown.
    ///
    /// Offered ONLY on `.passwordSetFailed`: this is the recovery for a stranded form, not a second
    /// sign-out control on a screen that is working.
    func signOutFromStuckPasswordStep() {
        guard state.error == .passwordSetFailed else { return }
        session.signOut()
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

nonisolated enum BootstrapField: Sendable, Hashable { case name, dob, phone, password, confirm }

/// `error` on its own field when `onField`, else in the line by Continue.
nonisolated struct ShownError: Sendable, Equatable {
    let error: BootstrapError
    let onField: Bool
}

extension BootstrapError {
    /// The field an error is about; nil for the two that are no field's fault.
    var field: BootstrapField? {
        switch self {
        case .invalidName: .name
        case .invalidDOB, .underAge: .dob
        case .invalidPhone: .phone
        case .invalidPassword: .password
        case .passwordMismatch, .confirmPassword: .confirm
        case .passwordSetFailed, .saveFailed: nil
        }
    }

    /// The inline message under the form. `passwordSetFailed`/`saveFailed` say WHAT, never why.
    var messageKey: String {
        switch self {
        case .invalidName: "bootstrap_error_invalid_name"
        case .invalidDOB: "bootstrap_error_invalid_dob"
        case .underAge: "bootstrap_error_under_age"
        case .invalidPhone: "bootstrap_error_invalid_phone"
        case .invalidPassword: "bootstrap_error_invalid_password"
        case .passwordMismatch: "bootstrap_error_password_mismatch"
        case .confirmPassword: "bootstrap_error_confirm_password"
        case .passwordSetFailed: "bootstrap_error_password_set_failed"
        case .saveFailed: "bootstrap_error_save_failed"
        }
    }
}
