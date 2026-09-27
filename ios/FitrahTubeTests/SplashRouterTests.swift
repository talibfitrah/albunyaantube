import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SplashRouterTests {
    // MARK: - Stage 3 / I5: an unrecognised status is not a terminal one

    /// The whole point of `.unknown`: signed in, main shell, NO sign-out and NO alert — so a backend
    /// that adds a `UserStatus` value cannot terminate (or wall off) every installed session.
    @Test func anUnknownStatusRoutesToMainWithNoSignOutAndNoAlert() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .unknown, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .main))
    }

    /// And it does not swallow §13: a password account that has not verified still goes there.
    @Test func anUnknownStatusStillHonoursTheVerificationBranch() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: .unknown, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .emailVerification))
    }

    // MARK: - Spec §6 matrix

    /// Onboarding outranks every signed-in state: a first launch that happens to carry a session
    /// still shows onboarding, and never signs anyone out on the way.
    @Test(arguments: AccountStatus.allCases)
    func onboardingIncompleteWinsOverEverySignedInState(status: AccountStatus) {
        let outcome = SplashRouter.outcome(onboardingCompleted: false, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: status, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .onboarding))
    }

    /// Owner ruling 2026-09-24 (overrides D11 / RULING 31): sign-in is forced, like Android's
    /// `SplashRouter.kt` `!signedIn -> signIn`. `hasPasswordProvider: true` with
    /// `isEmailVerified: false` on purpose (Task 8 review M1): it pins the signed-out guard's
    /// precedence over the §13 verification branch.
    @Test func signedOutRoutesToSignIn() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: false,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: nil, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn))
    }

    /// `/me` failed (Android's `accountStatus == null -> signIn`). No alert — a failed request is
    /// not a terminal account event — and no content until an account record is in hand.
    @Test func signedInWhoseStatusFailedRoutesToSignIn() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: nil, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn))
    }

    /// `/me` still in flight: Android's splash awaits it; iOS holds the splash instead of flashing
    /// either the shell or the sign-in wall before the answer lands.
    @Test func signedInWhileStatusIsInFlightHoldsTheSplash() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: nil, awaitingStatus: true)
        #expect(outcome == SplashOutcome(destination: .awaitingAccount))
    }

    /// §13 needs no `/me`, so an unverified password account goes to verification without waiting.
    @Test func unverifiedPasswordUserDoesNotWaitForStatus() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: nil, awaitingStatus: true)
        #expect(outcome == SplashOutcome(destination: .emailVerification))
    }

    @Test func activeRoutesToMain() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .active, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .main))
    }

    @Test func pendingProfileRoutesToProfileBootstrap() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .pendingProfile, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    @Test func blockedSignsOutToSignInWithTerminalAlert() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .blocked, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn, alert: .blocked))
    }

    @Test func deletedSignsOutToSignInWithTerminalAlert() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .deleted, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn, alert: .deleted))
    }

    // MARK: - Spec §13 bullet 1 — verification gate, ahead of status

    /// A password user with an unverified address lands on verification whatever the status is,
    /// including a status that never arrived and a profile that was never completed.
    @Test(arguments: [nil, AccountStatus.active, .pendingProfile])
    func unverifiedPasswordUserRoutesToEmailVerification(status: AccountStatus?) {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: status, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .emailVerification))
    }

    @Test func verifiedPasswordUserRoutesByStatus() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: true,
                                           status: .pendingProfile, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    /// Google-only: Firebase reports `isEmailVerified == false` for plenty of federated accounts,
    /// and a user with no password to verify can never leave a verification screen.
    @Test func googleOnlyUnverifiedRoutesByStatus() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: false,
                                           status: .pendingProfile, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    /// Terminal status outranks §13 (plan review): verification would be a loop a blocked account
    /// can never leave, and the sign-out is the whole point of the row.
    @Test func blockedUnverifiedStillSignsOut() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: .blocked, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn, alert: .blocked))
    }

    @Test func deletedUnverifiedStillSignsOut() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: .deleted, awaitingStatus: false)
        #expect(outcome == SplashOutcome(destination: .signIn, alert: .deleted))
    }

    // MARK: - Wire mapping the matrix feeds on

    @Test func fromWireIsCaseInsensitive() {
        #expect(AccountStatus.fromWire("PENDING_PROFILE") == .pendingProfile)
    }

    /// Stage 3 / I5: unknown and missing are `.unknown`, which the matrix above routes to the main
    /// shell with no sign-out and no alert (a deliberate difference from Android, which maps it to
    /// BLOCKED). `.blocked` is reserved for the literal wire value.
    @Test func fromWireUnknownOrMissingIsUnknownNotBlocked() {
        #expect(AccountStatus.fromWire("something_new") == .unknown)
        #expect(AccountStatus.fromWire(nil) == .unknown)
        #expect(AccountStatus.fromWire("blocked") == .blocked)
    }
}
