import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SplashRouterTests {
    @Test func onboardingNotCompletedRoutesToOnboarding() {
        #expect(SplashRouter.destination(onboardingCompleted: false) == .onboarding)
    }

    @Test func onboardingCompletedRoutesToMain() {
        #expect(SplashRouter.destination(onboardingCompleted: true) == .main)
    }

    // MARK: - Spec §6 matrix

    /// Onboarding outranks every signed-in state: a first launch that happens to carry a session
    /// still shows onboarding, and never signs anyone out on the way.
    @Test(arguments: AccountStatus.allCases)
    func onboardingIncompleteWinsOverEverySignedInState(status: AccountStatus) {
        let outcome = SplashRouter.outcome(onboardingCompleted: false, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: status)
        #expect(outcome == SplashOutcome(destination: .onboarding))
    }

    /// `hasPasswordProvider: true` with `isEmailVerified: false` on purpose (Task 8 review M1): it
    /// is what pins the signed-out guard's precedence over the §13 verification branch.
    @Test func signedOutRoutesToMainAsGuest() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: false,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: nil)
        #expect(outcome == SplashOutcome(destination: .main))
    }

    /// `me` never arrived (network). Render guest; the caller retries `fetchMe` in the background.
    /// No alert — a dropped request is not a terminal account event.
    @Test func signedInWithoutStatusRoutesToMainAsGuest() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: nil)
        #expect(outcome == SplashOutcome(destination: .main))
    }

    @Test func activeRoutesToMain() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .active)
        #expect(outcome == SplashOutcome(destination: .main))
    }

    @Test func pendingProfileRoutesToProfileBootstrap() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .pendingProfile)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    @Test func blockedSignsOutToMainWithTerminalAlert() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .blocked)
        #expect(outcome == SplashOutcome(destination: .main, signOut: true, alert: .blocked))
    }

    @Test func deletedSignsOutToMainWithTerminalAlert() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: true,
                                           status: .deleted)
        #expect(outcome == SplashOutcome(destination: .main, signOut: true, alert: .deleted))
    }

    // MARK: - Spec §13 bullet 1 — verification gate, ahead of status

    /// A password user with an unverified address lands on verification whatever the status is,
    /// including a status that never arrived and a profile that was never completed.
    @Test(arguments: [nil, AccountStatus.active, .pendingProfile])
    func unverifiedPasswordUserRoutesToEmailVerification(status: AccountStatus?) {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: status)
        #expect(outcome == SplashOutcome(destination: .emailVerification))
    }

    @Test func verifiedPasswordUserRoutesByStatus() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: true,
                                           status: .pendingProfile)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    /// Google-only: Firebase reports `isEmailVerified == false` for plenty of federated accounts,
    /// and a user with no password to verify can never leave a verification screen.
    @Test func googleOnlyUnverifiedRoutesByStatus() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: false, isEmailVerified: false,
                                           status: .pendingProfile)
        #expect(outcome == SplashOutcome(destination: .profileBootstrap))
    }

    /// Terminal status outranks §13 (plan review): verification would be a loop a blocked account
    /// can never leave, and the sign-out is the whole point of the row.
    @Test func blockedUnverifiedStillSignsOut() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: .blocked)
        #expect(outcome == SplashOutcome(destination: .main, signOut: true, alert: .blocked))
    }

    @Test func deletedUnverifiedStillSignsOut() {
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: false,
                                           status: .deleted)
        #expect(outcome == SplashOutcome(destination: .main, signOut: true, alert: .deleted))
    }

    // MARK: - Wire mapping the matrix feeds on

    @Test func fromWireIsCaseInsensitive() {
        #expect(AccountStatus.fromWire("PENDING_PROFILE") == .pendingProfile)
    }

    @Test func fromWireUnknownOrMissingIsBlocked() {
        #expect(AccountStatus.fromWire("something_new") == .blocked)
        #expect(AccountStatus.fromWire(nil) == .blocked)
    }
}
