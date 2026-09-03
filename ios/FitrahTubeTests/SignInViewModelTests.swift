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
        -> (model: SignInViewModel, transport: ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in })
        return (SignInViewModel(auth: auth, session: session, capabilities: capabilities), transport)
    }

    // MARK: - Pre-network gates

    @Test func aMalformedEmailIsRefusedBeforeAnyClientCall() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, transport) = make(auth: auth)
        model.email = "not-an-email"
        model.password = "hunter2"

        await model.submit()

        #expect(model.state.error == .invalidEmail)
        #expect(model.state.isLoading == false)
        #expect(model.landing == nil)
        // The fixture signs in by TRANSITIONING to `.signedIn`, so a still-nil user is proof no
        // `signIn`/`signUp` ran — a stronger assertion than reading the error back.
        #expect(await auth.currentUser() == nil, "submit() reached the auth client")
        #expect(transport.sent.isEmpty, "submit() reached the network")
    }

    @Test func aPasswordUnderSixCharactersIsRefusedBeforeAnyClientCall() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
        model.email = "student@fitrah.test"
        model.password = "12345"

        await model.submit()

        #expect(model.state.error == .weakPassword)
        #expect(model.state.isLoading == false)
        #expect(await auth.currentUser() == nil, "submit() reached the auth client")
    }

    /// The double-tap guard. It is raised here through an in-flight PROVIDER sign-in because that is
    /// the one leg a test can hold open without a clock (`FakeOAuthProvider(gate:)`); the guard
    /// itself — `guard !state.isLoading` — is the same line `submit()` and `signIn(with:)` share.
    @Test func aSecondSubmitWhileLoadingIsANoOp() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
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
        #expect(model.landing?.destination == .main)
    }

    // MARK: - The client's own errors

    @Test func aClientErrorSurfacesItsCodeAndClearsLoading() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.wrongPassword])
        let (model, _) = make(auth: auth)
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.state.error == .wrongPassword)
        #expect(model.state.isLoading == false)
        #expect(model.landing == nil)
    }

    @Test func togglingTheModeSwitchesTheCallAndClearsTheError() async {
        let auth = FakeAuthClient(state: .signedOut, scriptedErrors: [.wrongPassword])
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"
        await model.submit()
        #expect(model.state.error == .wrongPassword)

        model.toggleMode()
        #expect(model.state.mode == .signUp)
        #expect(model.state.error == nil)

        await model.submit()
        #expect(model.landing?.destination == .main)
    }

    // MARK: - Forgot password

    @Test func forgotPasswordOnABlankEmailNeverReachesTheClient() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
        auth.nextError = .passwordResetFailed

        await model.forgotPassword()

        #expect(model.state.error == .invalidEmail)
        #expect(model.state.passwordResetSent == false)
        // `nextError` is CONSUMED by any client call, so finding it still armed is proof none ran.
        #expect(auth.nextError == .passwordResetFailed, "forgotPassword() reached the auth client")
    }

    @Test func aFailedResetSurfacesPasswordResetFailedWhateverTheClientThrew() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
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
        let (model, _) = make(auth: auth)
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
        #expect(visible(SignInCapabilities(emailPassword: false, google: true, apple: true)) == [])
    }

    // MARK: - Provider sign-in

    /// Task 5 fix round I2 gave `AppleAuthProvider.presentSignIn()` a re-entrancy guard that THROWS.
    /// That guard must never be the user-visible path: the screen refuses the second tap itself, so
    /// the provider is asked exactly once and no error is ever surfaced for a double-tap.
    @Test func aSecondProviderTapWhileOneIsInFlightIsRefusedSilently() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
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

    /// Ruling: a cancel is the user's own choice, so it is SILENT — back to idle, no banner.
    @Test func aCancelledProviderSignInReturnsToIdleWithNoError() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
        let provider = FakeOAuthProvider(error: .cancelled)

        await model.signIn(with: provider)

        #expect(provider.presentCount == 1)
        #expect(model.state.error == nil, "a cancel must never surface a banner")
        #expect(model.state.isLoading == false)
        #expect(model.landing == nil)
        #expect(await auth.currentUser() == nil)
    }

    @Test func aFailedProviderSignInSurfacesItsCode() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
        let provider = FakeOAuthProvider(error: .failed(.appleSignInFailed))

        await model.signIn(with: provider)

        #expect(model.state.error == .appleSignInFailed)
        #expect(model.state.isLoading == false)
    }

    /// Ruling F11: an unavailable provider is never asked. `FakeOAuthProvider` FAILS when it is
    /// asked anyway, so a view model that skipped this guard would surface an error here.
    @Test func anUnavailableProviderIsNeverAsked() async {
        let auth = FakeAuthClient(state: .signedOut)
        let (model, _) = make(auth: auth)
        let provider = FakeOAuthProvider(isAvailable: false)

        await model.signIn(with: provider)

        #expect(provider.presentCount == 0)
        #expect(model.state.error == nil)
        #expect(model.state.isLoading == false)
    }

    // MARK: - The landing decision (spec §13, pinned against Task 8's matrix)

    @Test func anUnverifiedPasswordUserLandsOnEmailVerification() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: false, providerIDs: ["password"]))
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landing?.destination == .emailVerification)
        #expect(model.state.isLoading == false)
        #expect(model.state.error == nil)
    }

    @Test func aVerifiedUserWithAPendingProfileLandsOnProfileBootstrap() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: true, providerIDs: ["password"]))
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("pending_profile"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landing?.destination == .profileBootstrap)
    }

    @Test func aVerifiedUserOnAnActiveAccountLandsOnTheShell() async {
        let auth = FakeAuthClient(state: .signedOut,
                                  user: AuthUser(uid: "fake-uid", email: "student@fitrah.test",
                                                 isEmailVerified: true, providerIDs: ["password"]))
        let (model, _) = make(auth: auth, responses: [.json(200, Self.meJSON("active"))])
        model.email = "student@fitrah.test"
        model.password = "hunter2"

        await model.submit()

        #expect(model.landing?.destination == .main)
        #expect(model.landing?.signOut == false)
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
}
