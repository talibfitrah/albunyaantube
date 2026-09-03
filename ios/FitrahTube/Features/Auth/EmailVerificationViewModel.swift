import Foundation
import Observation

/// Drives `EmailVerificationScreen` (`EmailVerificationViewModel.kt`). Firebase stays behind
/// `AuthClient`; the backend stays behind `AccountClient`; the clock is injected, so the 60 s
/// cooldown is a pure function of a `Date` a test hands over rather than of wall time.
@MainActor @Observable final class EmailVerificationViewModel {

    /// `EmailVerifyError.kt`. `.network` and `.unknown` render the same copy (Android does too) —
    /// they are kept apart because "no signed-in user" and "the server was unreachable" are
    /// different bugs to read in a log, not different sentences to a user.
    nonisolated enum EmailVerifyError: Sendable, Equatable { case notYetVerified, rateLimited, network, unknown }

    nonisolated struct UiState: Equatable {
        var email = ""
        var isChecking = false
        var isResending = false
        var lastSentAt: Date?
        var error: EmailVerifyError?
    }

    /// `EmailVerificationViewModel.kt:142`, and the same 60 s per uid the backend enforces
    /// (`AccountController.java:48,112-116`).
    static let cooldown: TimeInterval = 60

    /// Per ACCOUNT, not per install: the back affordance on this very screen signs out so the user
    /// can sign up with a different address, and a single global key would then hand the new
    /// account the previous one's latch — no verification mail, and a Resend button parked inside a
    /// cooldown it never started. The brief's `email_verification_last_sent_at` is the prefix.
    static func lastSentKey(uid: String) -> String { "email_verification_last_sent_at.\(uid)" }

    private let auth: any AuthClient
    private let session: AccountSession
    private let account: AccountClient
    private let defaults: UserDefaults
    private let now: @Sendable () -> Date

    private(set) var state = UiState()

    /// The signed-in identity, resolved by `send()`. `AccountSession.user` would also carry it, but
    /// this screen is reachable before `/me` has answered and the email on it is Firebase's, not the
    /// backend's.
    private var user: AuthUser?

    init(auth: any AuthClient, session: AccountSession, account: AccountClient,
         defaults: UserDefaults, now: @escaping @Sendable () -> Date = { Date() }) {
        self.auth = auth
        self.session = session
        self.account = account
        self.defaults = defaults
        self.now = now
    }

    /// The screen's `.task`: resolve the identity, restore the persisted latch, and auto-send only
    /// if nothing has ever been sent for this account (`EmailVerificationViewModel.kt:59-63`, where
    /// the latch is a `SavedStateHandle` that dies with the process — `UserDefaults` here, so a
    /// relaunch does not mail the user again). Idempotent: a second call after a successful send
    /// sees the timestamp and does nothing.
    func send() async {
        guard let user = await auth.currentUser() else {
            state.error = .unknown
            return
        }
        self.user = user
        state.email = user.email ?? ""
        state.lastSentAt = defaults.object(forKey: Self.lastSentKey(uid: user.uid)) as? Date
        guard state.lastSentAt == nil else { return }
        await performSend()
    }

    /// User-initiated. Inside the cooldown it never reaches the network at all — the point is to
    /// keep the attempt off the backend's and Firebase's throttles, not to report their refusal.
    func resend() async {
        guard canResend(at: now()) else {
            state.error = .rateLimited
            return
        }
        await performSend()
    }

    /// The ONE cooldown rule, shared by `resend()` and the screen's disabled state so the button and
    /// the refusal can never disagree. `>=`: Android's gate is `now - last < COOLDOWN_MS`, so the
    /// boundary itself sends.
    func canResend(at date: Date) -> Bool {
        guard let lastSentAt = state.lastSentAt else { return true }
        return date.timeIntervalSince(lastSentAt) >= Self.cooldown
    }

    /// Whole seconds since the last send, for `email_verification_last_sent`; nil when nothing has
    /// been sent yet.
    func secondsSinceLastSend(at date: Date) -> Int? {
        state.lastSentAt.map { max(0, Int(date.timeIntervalSince($0))) }
    }

    /// `reload()` then the flag (`EmailVerificationViewModel.kt:71-84`). Returns whether the account
    /// is verified so the screen can react; the routing itself is `RootView` recomputing
    /// `SplashRouter.outcome` over the session this hands the fresh identity to.
    func checkNow() async -> Bool {
        guard !state.isChecking else { return false }
        state.isChecking = true
        state.error = nil
        do {
            let reloaded = try await auth.reload()
            state.isChecking = false
            guard !Task.isCancelled else { return false }
            user = reloaded
            guard reloaded.isEmailVerified else {
                state.error = .notYetVerified
                return false
            }
            // Firebase's auth-state listener does NOT fire on a reload, so `AccountSession.user`
            // would keep the stale `isEmailVerified: false` that put the account here — and that is
            // the exact field `RootView`'s outcome reads. Without this the screen is a dead end.
            session.adopt(reloaded)
            return true
        } catch {
            state.isChecking = false
            guard !Task.isCancelled else { return false }
            state.error = error == .tooManyRequests ? .rateLimited : .network
            return false
        }
    }

    /// Spec §13: the back affordance signs out. `AccountSession` owns it — signing out through the
    /// auth client directly would leave the per-user stores scoped to the account that just left.
    func signOut() { session.signOut() }

    // MARK: -

    private func performSend() async {
        guard let user else {
            state.error = .unknown
            return
        }
        guard !state.isResending else { return }
        state.isResending = true
        state.error = nil

        let failure = await sendOnce()
        state.isResending = false
        if let failure {
            // Android rethrows `CancellationException` ahead of every catch arm; on iOS the typed
            // throws make that impossible to observe (`AccountClient.send` maps a cancellation to
            // `.network` by contract), so the same guarantee is spelled as this check: a screen the
            // user has already left never gets a banner about it.
            guard !Task.isCancelled else { return }
            state.error = failure
            return
        }
        let sentAt = now()
        defaults.set(sentAt, forKey: Self.lastSentKey(uid: user.uid))
        state.lastSentAt = sentAt
    }

    /// Backend first, Firebase only when the backend answered UNSUCCESSFULLY
    /// (`EmailVerificationViewModel.kt:115-118`). Two responses stop before the fallback:
    ///   - `.network` — the request did not happen, and asking Firebase over the same dead radio is
    ///     a second doomed call (Android's `IOException` leg escapes ahead of the fallback too).
    ///   - `.rateLimited` — 429 IS the backend's own 60 s per-uid limit; routing around it through
    ///     Firebase would defeat the server-side rule this screen's cooldown mirrors.
    private func sendOnce() async -> EmailVerifyError? {
        do {
            try await account.sendVerificationEmail()
            return nil
        } catch {
            switch error {
            case .network: return .network
            case .rateLimited: return .rateLimited
            default: break
            }
        }
        do {
            try await auth.sendVerificationEmail()
            return nil
        } catch {
            return error == .tooManyRequests ? .rateLimited : .network
        }
    }
}
