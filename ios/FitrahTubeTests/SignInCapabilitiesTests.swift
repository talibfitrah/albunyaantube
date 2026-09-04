import Foundation
import Testing
@testable import FitrahTube

/// Ruling F11: a sign-in button renders only when its prerequisite actually exists. The table below
/// is the whole decision, and it is pure — no Firebase, no SDK, no network.
@Suite struct SignInCapabilitiesTests {

    /// Written against `FirebaseBootstrap.optionsFileExists` rather than a literal `false` (Task 2's
    /// precedent): it is `false` on this machine, on CI and in every fresh checkout, and the day a
    /// real `GoogleService-Info.plist` lands the SAME lines assert the configured behaviour with no
    /// test edit.
    @Test func currentReportsNothingConfiguredWithNoOptionsFile() {
        let capabilities = SignInCapabilities.current()
        if !FirebaseBootstrap.optionsFileExists {
            #expect(capabilities == SignInCapabilities(emailPassword: false, google: false, apple: false))
            #expect(SignInCapabilities.visibleProviders(capabilities).isEmpty)
        }
    }

    /// Stage 5 / M6: Apple needs BOTH build flags. `FITRAH_TEAM_ID` is committed in both tracked
    /// xcconfigs, so `FITRAH_APPLE_SIGNIN` alone was non-empty in every build while the App ID
    /// `com.albunyaan.tube` has no Sign in with Apple capability registered (the entitlements file's
    /// own header records the counter-fact) — the F11 trap exactly, a button that renders and then
    /// fails `performRequests()` on the first signed device build. `FITRAH_APPLE_SIGNIN_REGISTERED`
    /// is EMPTY in both tracked xcconfigs, so this build must report Apple as unconfigured.
    @Test func appleNeedsTheRegisteredFlagAndNotJustATeamId() {
        let teamId = Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN") as? String
        let registered = Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN_REGISTERED") as? String
        #expect(registered?.isEmpty != false,
                "the tracked xcconfigs must not claim a portal capability the App ID does not have")
        #expect(SignInCapabilities.appleSignInIsConfigured == false)
        // Stated so the assertion above cannot be read as "there is no Team ID either".
        #expect(teamId?.isEmpty == false || teamId == nil)
    }

    /// All eight combinations of the pure table. Task 10 renders exactly this list, in this order.
    @Test func theTableDrivesAllEightCombinations() {
        func visible(_ emailPassword: Bool, _ google: Bool, _ apple: Bool) -> [SignInProvider] {
            SignInCapabilities.visibleProviders(
                SignInCapabilities(emailPassword: emailPassword, google: google, apple: apple))
        }
        #expect(visible(false, false, false) == [])
        #expect(visible(false, false, true) == [])
        #expect(visible(false, true, false) == [])
        #expect(visible(false, true, true) == [])
        #expect(visible(true, false, false) == [.emailPassword])
        #expect(visible(true, false, true) == [.emailPassword, .apple])
        #expect(visible(true, true, false) == [.emailPassword, .google])
        #expect(visible(true, true, true) == [.emailPassword, .google, .apple])
    }

    /// The trap this exists to prevent: a Google or Apple button with Firebase unconfigured is a
    /// button that cannot possibly work. `current()` ANDs both onto `emailPassword`, and
    /// `visibleProviders` refuses the combination a second time even when handed one by hand — the
    /// struct is a plain value anybody can construct.
    @Test func noProviderIsVisibleWithoutFirebaseEvenIfTheFlagsSayOtherwise() {
        #expect(SignInCapabilities.visibleProviders(
            SignInCapabilities(emailPassword: false, google: true, apple: true)) == [])
        // The AND in `current()` itself: with no options file neither flag can be true, whatever the
        // build-time Apple flag says.
        if !FirebaseBootstrap.optionsFileExists {
            #expect(SignInCapabilities.current().apple == false)
            #expect(SignInCapabilities.current().google == false)
        }
    }

    /// The Google provider never traps: with no client id it reports unavailable, and asked anyway
    /// it fails with the Google code instead of reaching a `GIDSignIn` whose `configuration` is nil
    /// (the `GIDConfiguration` hand-off lives inside `FirebaseBootstrap`'s configure latch, so the
    /// guard on that latch is what makes the failure orderly).
    ///
    /// Also pins the URL type that carries Google's OAuth callback: an EMPTY `CFBundleURLSchemes`
    /// entry shipped in every build until `GID_REVERSED_CLIENT_ID` got a placeholder default.
    @MainActor @Test func theGoogleProviderIsUnavailableAndItsCallbackSchemeIsNeverEmpty() async {
        let provider = GoogleAuthProvider()
        #expect(provider.isAvailable == (FirebaseBootstrap.googleClientID != nil))
        if !FirebaseBootstrap.optionsFileExists {
            #expect(provider.isAvailable == false)
            await #expect(throws: OAuthSignInFailure.failed(.googleSignInFailed)) { try await provider.presentSignIn() }
        }

        let types = Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? []
        let schemes = types.flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
        #expect(schemes.contains("albunyaantube"))
        #expect(schemes.allSatisfy { !$0.isEmpty }, "an empty URL scheme is an App Store validation nit: \(schemes)")
    }

    /// Apple is hidden, and Stage 5 / M6 changed WHY: `FITRAH_APPLE_SIGNIN` still carries the Team
    /// ID, but `FITRAH_APPLE_SIGNIN_REGISTERED` is empty in both tracked xcconfigs because the App
    /// ID has no Sign in with Apple capability. Either half being false is the honest answer here;
    /// the flag assertion itself lives in `appleNeedsTheRegisteredFlagAndNotJustATeamId`.
    @MainActor @Test func theAppleProviderIsUnavailableBecauseFirebaseIsNotConfigured() async {
        #expect(Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN") as? String != "",
                "FITRAH_APPLE_SIGNIN must still carry the Team ID")
        let provider = AppleAuthProvider()
        #expect(provider.isAvailable == SignInCapabilities.current().apple)
        if !FirebaseBootstrap.optionsFileExists {
            #expect(provider.isAvailable == false)
            await #expect(throws: OAuthSignInFailure.failed(.appleSignInFailed)) { try await provider.presentSignIn() }
        }
    }

    /// Fix round 1 / I2: `presentSignIn()` used to overwrite its single continuation slot on a
    /// second call, orphaning the first — never resumed, `SWIFT TASK CONTINUATION MISUSE`, and a
    /// double-tap on Task 10's Apple button is the canonical trigger. The latch is what refuses the
    /// second call.
    ///
    /// It is asserted through `isPresenting` rather than by racing two real calls because with no
    /// `GoogleService-Info.plist` (this machine, CI, every fresh checkout) `presentSignIn()` returns
    /// at its configure guard and NEVER reaches the flow — so an in-flight state is unreachable from
    /// the outside here. Setting the latch is exactly the state a started flow leaves behind. The
    /// second expectation is the load-bearing one: a refused re-entrant call must not run the
    /// release path and clear the FIRST flow's latch, which is what makes the guard's placement
    /// (before the claim, so before the `defer`) part of the contract rather than an accident.
    @MainActor @Test func aSecondPresentSignInIsRefusedWithoutDisturbingTheFirstFlow() async {
        let provider = AppleAuthProvider()
        #expect(provider.isPresenting == false)

        provider.isPresenting = true
        await #expect(throws: OAuthSignInFailure.failed(.appleSignInFailed)) { try await provider.presentSignIn() }
        #expect(provider.isPresenting, "the refused call must leave the first flow's latch claimed")

        provider.isPresenting = false
    }

    /// The double Task 10's ViewModel tests drive: canned credential when available, and a loud
    /// failure — never a credential — when it is not, so "an unavailable provider is never asked"
    /// fails the test that asks it instead of silently succeeding.
    @MainActor @Test func theFakeProviderYieldsItsCredentialOnlyWhenAvailable() async throws {
        let available = FakeOAuthProvider()
        #expect(available.isAvailable)
        #expect(try await available.presentSignIn().providerID == "google.com")
        #expect(available.presentCount == 1)

        let unavailable = FakeOAuthProvider(isAvailable: false)
        await #expect(throws: OAuthSignInFailure.failed(.googleSignInFailed)) { try await unavailable.presentSignIn() }
    }
}
