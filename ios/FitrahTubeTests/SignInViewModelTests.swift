import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Task 10. The pre-network gates (a malformed email, a short password, a re-entrant tap) exist so
/// a bad attempt never reaches Firebase and never burns the IP throttle — so every one of them is
/// asserted by proving the CLIENT WAS NOT CALLED, not merely by reading the error back.
///
/// The post-sign-in landing is asserted by driving `SplashRouter.outcome` THROUGH the view model
/// (Task 8's matrix is the authority; nothing here re-derives the rule).
@Suite(.perTest)
@MainActor
struct SignInViewModelTests {

    private static let base = URL(string: "https://api.fitrah.test/")!

    private static func meJSON(_ status: String) -> String {
        #"{"uid":"fake-uid","email":"student@fitrah.test","status":"\#(status)","role":"user"}"#
    }

    private static let allCapabilities = SignInCapabilities(emailPassword: true, google: true, apple: true)

    private func make(auth: FakeAuthClient,
                      responses: [HTTPResponse] = [],
                      capabilities: SignInCapabilities = allCapabilities)
        -> (model: SignInViewModel, transport: ScriptedTransport, session: AccountSession) {
        let transport = ScriptedTransport(responses)
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil })
        return (SignInViewModel(auth: auth, session: session, capabilities: capabilities), transport, session)
    }

    // MARK: - Pre-network gates

    @Test func aMalformedEmailIsRefusedBeforeAnyClientCall() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, transport, _) = make(auth: auth)
        model.email = "not-an-email"
        model.password = "hunter2"

        await model.submit()

        #expect(model.state.error == .invalidEmail)
        #expect(model.state.isLoading == false)
        #expect(model.landed == false)
        // The fixture signs in by TRANSITIONING to `.signedIn`, so a still-nil user is proof no
        // `signIn`/`signUp` ran — a stronger assertion than reading the error back.
        #expect(await auth.currentUser() == nil, "submit() reached the auth client")
        #expect(transport.sent.isEmpty, "submit() reached the network")
    }

    @Test func aPasswordUnderSixCharactersIsRefusedBeforeAnyClientCall() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        model.email = "student@fitrah.test"
        model.password = "12345"

        await model.submit()

        #expect(model.state.error == .weakPassword)
        #expect(model.state.isLoading == false)
        #expect(await auth.currentUser() == nil, "submit() reached the auth client")
    }

    /// Fix round 1 / I1. The screen raises its banner from a CHANGE, and neither pre-network gate
    /// clears `state.error` first — so a second tap on the same malformed address assigned
    /// `.invalidEmail` over `.invalidEmail`, nothing changed, and the button did nothing at all.
    /// Every failed attempt now carries its own presentation, identical repeats included.
    @Test func twoIdenticalValidationFailuresRaiseTwoDistinctPresentations() async {
        let (model, _, _) = make(auth: FakeAuthClient(state: .signedOut))
        model.email = "not-an-email"
        model.password = "hunter2"

        await model.submit()
        let first = model.errorPresentation
        await model.submit()
        let second = model.errorPresentation

        #expect(first?.code == .invalidEmail)
        #expect(second?.code == .invalidEmail)
        #expect(first != second, "the repeated identical failure raised nothing for the banner")
    }

    /// The double-tap guard. It is raised here through an in-flight PROVIDER sign-in because that is
    /// the one leg a test can hold open without a clock (`FakeOAuthProvider(gate:)`); the guard
    /// itself — `guard !state.isLoading` — is the same line `submit()` and `signIn(with:)` share.
    @Test func aSecondSubmitWhileLoadingIsANoOp() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, session) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        let gate = Gate()
        let provider = FakeOAuthProvider(gate: gate)
        let inFlight = Task { await model.signIn(with: provider) }
        await gate.waitUntilBlocked()
        #expect(model.state.isLoading)

        model.email = "student@fitrah.test"
        model.password = "hunter2"
        await model.submit()
        #expect(model.state.error == nil, "the refused submit surfaced an error")

        await gate.release()
        await inFlight.value
        // ONE sign-in ran: the provider's. The fixture's default account is a verified password
        // user on an active row, so Task 8's matrix lands it on the shell.
        #expect(provider.presentCount == 1)
        #expect(model.landed)
        #expect(destination(session, verified: true) == .main)
    }

    // MARK: - The client's own errors

    @Test func aClientErrorSurfacesItsCodeAndClearsLoading() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.wrongPassword])
        let (model, _, _) = make(auth: auth)
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.state.error == .wrongPassword)
        #expect(model.state.isLoading == false)
        #expect(model.landed == false)
    }

    @Test func togglingTheModeSwitchesTheCallAndClearsTheError() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.wrongPassword])
        let (model, _, session) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"
        await model.submit()
        #expect(model.state.error == .wrongPassword)

        model.toggleMode()
        #expect(model.state.mode == .signUp)
        #expect(model.state.error == nil)

        await model.submit()
        #expect(model.landed)
        #expect(destination(session, verified: true) == .main)
        // Fix round 1 / M5: both fixture legs share one body, so without this the name's "switches
        // the call" was unpinned — the entry point is recorded and asserted, not assumed.
        #expect(auth.entryPoints == [.signIn, .signUp])
    }

    // MARK: - Forgot password

    @Test func forgotPasswordOnABlankEmailNeverReachesTheClient() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        auth.nextError = .passwordResetFailed

        await model.forgotPassword()

        #expect(model.state.error == .invalidEmail)
        #expect(model.state.passwordResetSent == false)
        // `nextError` is CONSUMED by any client call, so finding it still armed is proof none ran.
        #expect(auth.nextError == .passwordResetFailed, "forgotPassword() reached the auth client")
    }

    @Test func aFailedResetSurfacesPasswordResetFailedWhateverTheClientThrew() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        auth.nextError = .network
        model.email = "student@fitrah.test"

        await model.forgotPassword()

        // Android collapses every reset failure to one code (`SignInViewModel.kt:170`): the user
        // can do nothing different about a network error than about a rejected address.
        #expect(model.state.error == .passwordResetFailed)
        #expect(model.state.isLoading == false)
    }

    @Test func aSuccessfulResetSetsTheSentFlag() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        model.email = "student@fitrah.test"

        await model.forgotPassword()

        #expect(model.state.passwordResetSent)
        #expect(model.state.error == nil)
        #expect(model.state.isLoading == false)
    }

    // MARK: - Capability filtering

    @Test func theVisibleProvidersFollowTheCapabilities() {
        func visible(_ capabilities: SignInCapabilities) -> [SignInProvider] {
            make(auth: FakeAuthClient(state: .signedOut), capabilities: capabilities).model.visibleProviders
        }
        #expect(visible(Self.allCapabilities) == [.emailPassword, .google, .apple])
        #expect(visible(SignInCapabilities(emailPassword: true, google: false, apple: true))
                == [.emailPassword, .apple])
        #expect(visible(SignInCapabilities(emailPassword: true, google: true, apple: false))
                == [.emailPassword, .google])
        // Stage 1 / B9: `visibleProviders` no longer re-derives the F11 rule a second time — an
        // inconsistent value is one only a test can construct, because `SignInCapabilities.current()`
        // ANDs both federated flags onto `emailPassword` (pinned in `SignInCapabilitiesTests`). What
        // this screen still guarantees is the RUNTIME leg: an unavailable provider is never asked.
        #expect(visible(SignInCapabilities(emailPassword: false, google: false, apple: false)) == [])
    }

    // MARK: - Provider sign-in

    /// Task 5 fix round I2 gave `AppleAuthProvider.presentSignIn()` a re-entrancy guard that THROWS.
    /// That guard must never be the user-visible path: the screen refuses the second tap itself, so
    /// the provider is asked exactly once and no error is ever surfaced for a double-tap.
    @Test func aSecondProviderTapWhileOneIsInFlightIsRefusedSilently() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        let gate = Gate()
        let provider = FakeOAuthProvider(gate: gate)
        let inFlight = Task { await model.signIn(with: provider) }
        await gate.waitUntilBlocked()

        await model.signIn(with: provider)
        #expect(provider.presentCount == 1, "the provider was asked twice")
        #expect(model.state.error == nil)

        await gate.release()
        await inFlight.value
    }

    /// Part B gate, Cubic round 5 P1: the provider SDK signed its user in and Firebase then refused
    /// the credential. That SDK session must not outlive the failed sign-in — `GoogleYouTubeAuthorizer
    /// .isAvailable` reads the SDK's keychain, and the next account on the device would otherwise be
    /// offered the stranger's YouTube library.
    @Test func aFirebaseRefusalAfterAProviderSuccessForgetsTheProviderSession() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.network])
        let (model, _, _) = make(auth: auth)
        let provider = FakeOAuthProvider()

        await model.signIn(with: provider)

        #expect(provider.presentCount == 1)
        #expect(provider.signOutCount == 1, "the SDK session outlived the failed sign-in")
        #expect(model.state.error != nil)
        #expect(model.landed == false)
    }

    /// Ruling: a cancel is the user's own choice, so it is SILENT — back to idle, no banner.
    @Test func aCancelledProviderSignInReturnsToIdleWithNoError() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        let provider = FakeOAuthProvider(error: .cancelled)

        await model.signIn(with: provider)

        #expect(provider.presentCount == 1)
        #expect(model.state.error == nil, "a cancel must never surface a banner")
        #expect(model.state.isLoading == false)
        #expect(model.landed == false)
        #expect(await auth.currentUser() == nil)
    }

    @Test func aFailedProviderSignInSurfacesItsCode() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        let provider = FakeOAuthProvider(error: .failed(.appleSignInFailed))

        await model.signIn(with: provider)

        #expect(model.state.error == .appleSignInFailed)
        #expect(model.state.isLoading == false)
    }

    /// Ruling F11: an unavailable provider is never asked. `FakeOAuthProvider` FAILS when it is
    /// asked anyway, so a view model that skipped this guard would surface an error here.
    @Test func anUnavailableProviderIsNeverAsked() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _, _) = make(auth: auth)
        let provider = FakeOAuthProvider(isAvailable: false)

        await model.signIn(with: provider)

        #expect(provider.presentCount == 0)
        #expect(model.state.error == nil)
        #expect(model.state.isLoading == false)
    }

    // MARK: - The landing decision (spec §13, pinned against Task 8's matrix)
    //
    // Stage 1 / B7: `landed` is a Bool — the model only ever announces THAT the sign-in landed, and
    // `RootView` recomputes where. So each row below drives the matrix through `SplashRouter` over
    // the session THIS sign-in produced, which is the same fact the stored `SplashOutcome` carried
    // without pretending the view model routes anything.

    /// Where `RootView` would send the account this sign-in just produced.
    private func destination(_ session: AccountSession,
                             verified: Bool, password: Bool = true) -> SplashDestination {
        SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                             hasPasswordProvider: password, isEmailVerified: verified,
                             status: session.state.me?.status).destination
    }

    @Test func anUnverifiedPasswordUserLandsOnEmailVerification() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: false, providerIDs: ["password"]))
        let (model, _, session) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landed)
        #expect(destination(session, verified: false) == .emailVerification)
        #expect(model.state.isLoading == false)
        #expect(model.state.error == nil)
    }

    @Test func aVerifiedUserWithAPendingProfileLandsOnProfileBootstrap() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: true, providerIDs: ["password"]))
        let (model, _, session) = make(auth: auth, responses: [.json(200, Self.meJSON("pending_profile"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landed)
        #expect(destination(session, verified: true) == .profileBootstrap)
    }

    @Test func aVerifiedUserOnAnActiveAccountLandsOnTheShell() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: true, providerIDs: ["password"]))
        let (model, _, session) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landed)
        let outcome = SplashRouter.outcome(onboardingCompleted: true, signedIn: true,
                                           hasPasswordProvider: true, isEmailVerified: true,
                                           status: session.state.me?.status)
        #expect(outcome.destination == .main)
        #expect(outcome.alert == nil)
    }

    // MARK: - EmailShape (`EmailShape.kt:9-15`, ported verbatim)

    @Test func theEmailShapeTable() {
        let table: [(String, Bool)] = [
            ("a@b.c", true), ("a@b", false), ("@b.c", false), ("a@.c", false),
            ("a@b.", false), ("a@@b.c", false), ("", false), ("a.b@c.d", true),
        ]
        for (input, expected) in table {
            #expect(EmailShape.isValid(input) == expected, "\(input)")
        }
    }

    // MARK: - Stage 4 / I2: the sign-in screen is not an account-existence oracle

    /// "No account found with that email" beside "Email or password is incorrect" told an attacker
    /// with an email list which addresses hold FitrahTube accounts — and "is this address registered
    /// with an Islamic-content app" is not a neutral fact for this audience. The password-reset path
    /// already collapses every failure to one code and says so; this is the same rule on the leg
    /// that was inconsistent.
    @Test func aSignInAgainstAnUnknownEmailRendersTheWrongPasswordCopy() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.userNotFound])
        let fixture = make(auth: auth)

        fixture.model.email = "someone@fitrah.test"
        fixture.model.password = "hunter2"
        await fixture.model.submit()

        #expect(fixture.model.state.error == .wrongPassword)
        #expect(fixture.model.state.error?.messageKey == "auth_error_wrong_password")
    }

    /// The CODE is untouched — `.userNotFound` still arrives distinctly, so a leg that wanted to
    /// branch on it still can. Only what this screen renders collapses.
    @Test func onlyThePresentedCodeCollapsesNotTheTable() {
        #expect(SignInViewModel.presented(.userNotFound) == .wrongPassword)
        #expect(SignInViewModel.presented(.wrongPassword) == .wrongPassword)
        #expect(SignInViewModel.presented(.userDisabled) == .userDisabled)
        // Task 27 re-review nit: with email-enumeration protection on, a mistyped password comes
        // back as 17004 -> `.invalidCredential`, whose copy is "Sign in again to continue" — a
        // no-op instruction on the screen you sign in FROM. Both re-auth sheets already collapse
        // the same pair; this is the leg that was still inconsistent.
        #expect(SignInViewModel.presented(.invalidCredential) == .wrongPassword)
        #expect(SignInViewModel.presented(.invalidCredential).messageKey == "auth_error_wrong_password")
        // The CODE is untouched: `.invalidCredential` still arrives distinctly, and the two edit
        // sheets and Suggest's 401 arm all still branch on it.
        #expect(AuthErrorCode.invalidCredential.messageKey == "auth_error_invalid_credential")
        #expect(AuthErrorCode(firebaseCode: 17004) == .invalidCredential)
        // Stage 8 / S6: the distinct MESSAGE is gone, because no leg ever rendered it — the two
        // edit sheets map everything but `.wrongPassword`/`.invalidCredential` to `.network` and
        // the delete confirmation renders its own keys, so `auth_error_user_not_found` was
        // unreachable copy, not a reserve for the re-auth legs.
        #expect(AuthErrorCode.userNotFound.messageKey == "auth_error_wrong_password")
    }
}
