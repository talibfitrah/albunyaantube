import Foundation
import Observation

/// Drives `SignInScreen` (`SignInViewModel.kt`). Everything Firebase-shaped stays behind
/// `AuthClient`/`OAuthSignInProvider`, so this type names no SDK at all.
@MainActor @Observable final class SignInViewModel {

    nonisolated enum Mode: Sendable, Equatable { case signIn, signUp }

    nonisolated struct UiState: Equatable {
        var mode: Mode = .signIn
        var email = ""
        var password = ""
        var isLoading = false
        /// Operation error, NOT auth state (`AuthState.kt:11-12`): it lives on the screen, never on
        /// the client.
        var error: AuthErrorCode?
        var passwordResetSent = false
        var capabilities: SignInCapabilities
    }

    /// `SignInViewModel.kt:115`. Firebase's own minimum, so this gate can never reject a password
    /// Firebase would have accepted — it only stops the attempts Firebase would immediately reject,
    /// which is what keeps them off the IP throttle.
    static let minPasswordLength = 6

    private let auth: any AuthClient
    private let session: AccountSession

    private(set) var state: UiState

    /// Set ONCE, by a successful sign-in: where spec §13 says this account lands. The screen
    /// dismisses on it; `RootView.destination(for:)` is what renders the destination, so nothing
    /// here has to know whether Tasks 11/12 have shipped their screens yet.
    ///
    /// The whole `SplashOutcome`, not just its destination: `signOut`/`alert` travel with it for the
    /// blocked/deleted rows, and `RootView`'s `onChange(of: outcome)` is the caller that acts on
    /// them — dropping them here would be dropping the terminal-account handling.
    private(set) var landing: SplashOutcome?

    /// The capability-filtered button list, in render order. `SignInCapabilities.visibleProviders`
    /// is the ONE table (Task 5) — this is the screen's view of it, never a second copy.
    var visibleProviders: [SignInProvider] { SignInCapabilities.visibleProviders(state.capabilities) }

    /// Both fields clear the last error as they are edited (`onEmailChanged`/`onPasswordChanged`):
    /// a banner about the previous attempt has nothing to say about the text now on screen.
    var email: String {
        get { state.email }
        set {
            state.email = newValue
            state.error = nil
            state.passwordResetSent = false
        }
    }

    var password: String {
        get { state.password }
        set {
            state.password = newValue
            state.error = nil
        }
    }

    init(auth: any AuthClient, session: AccountSession, capabilities: SignInCapabilities) {
        self.auth = auth
        self.session = session
        state = UiState(capabilities: capabilities)
    }

    func toggleMode() {
        state.mode = state.mode == .signIn ? .signUp : .signIn
        state.error = nil
        state.passwordResetSent = false
    }

    /// Email/password, in whichever mode the toggle is in. The two pre-network gates run BEFORE any
    /// loading state is raised, so a refused attempt never flickers the spinner.
    func submit() async {
        guard !state.isLoading else { return }   // de-dupe rapid double-taps
        guard EmailShape.isValid(state.email) else {
            state.error = .invalidEmail
            return
        }
        guard state.password.count >= Self.minPasswordLength else {
            state.error = .weakPassword
            return
        }
        beginLoading()
        do {
            let user: AuthUser
            if state.mode == .signIn {
                user = try await auth.signIn(email: state.email, password: state.password)
            } else {
                user = try await auth.signUp(email: state.email, password: state.password)
            }
            await land(user)
        } catch {
            finish(with: error)
        }
    }

    /// Google/Apple. The `isLoading` guard is what keeps `AppleAuthProvider`'s re-entrancy latch off
    /// the user-visible path: the second tap is refused HERE, silently, so the latch's throw is a
    /// backstop nobody sees.
    func signIn(with provider: any OAuthSignInProvider) async {
        guard !state.isLoading else { return }
        // Ruling F11: an unavailable provider is never asked. `visibleProviders` already keeps its
        // button off the screen — this is the second defence, for a provider that lost its
        // prerequisite between render and tap.
        guard provider.isAvailable else { return }
        beginLoading()

        let credential: OAuthCredential
        do {
            credential = try await provider.presentSignIn()
        } catch {
            state.isLoading = false
            // A cancel is the user's own choice: back to idle, NO banner. Only a real failure
            // carries a code to render.
            if case .failed(let code) = error { state.error = code }
            return
        }

        do {
            await land(try await auth.signIn(with: credential))
        } catch {
            finish(with: error)
        }
    }

    /// Blank or malformed goes nowhere near the network (`SignInViewModel.kt:160-167`), and every
    /// failure is ONE code: the user can do nothing different about a network error than about a
    /// rejected address.
    func forgotPassword() async {
        guard !state.isLoading else { return }
        guard EmailShape.isValid(state.email) else {
            state.error = .invalidEmail
            return
        }
        beginLoading()
        do {
            try await auth.sendPasswordReset(email: state.email)
            state.isLoading = false
            state.passwordResetSent = true
        } catch {
            finish(with: .passwordResetFailed)
        }
    }

    // MARK: -

    private func beginLoading() {
        state.isLoading = true
        state.error = nil
        state.passwordResetSent = false
    }

    private func finish(with error: AuthErrorCode) {
        state.isLoading = false
        state.error = error
    }

    /// Spec §13's post-sign-in rule, asked of Task 8's matrix rather than re-derived here.
    ///
    /// The account STATUS is fetched explicitly rather than read off whatever `AccountSession` holds
    /// at this instant: `start()`'s own refresh is driven by the auth stream and has not necessarily
    /// landed when `signIn` returns, and reading a still-`nil` status would route a pending-profile
    /// account to the shell. `maxAttempts: 1` — the splash's budget; a network failure leaves the
    /// status nil, which the matrix reads as "guest for now, the caller retries".
    ///
    /// `onboardingCompleted: true` is a fact, not an assumption: this screen is only reachable from
    /// the Me tab, which lives behind the onboarding gate.
    private func land(_ user: AuthUser) async {
        await session.refresh(maxAttempts: 1)
        state.isLoading = false
        landing = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                       hasPasswordProvider: user.hasPasswordProvider,
                                       isEmailVerified: user.isEmailVerified,
                                       status: session.state.me?.status)
    }
}
