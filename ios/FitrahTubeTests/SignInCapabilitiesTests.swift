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
    /// is EMPTY in both tracked xcconfigs, so a build that does not override it reports Apple as
    /// unconfigured.
    ///
    /// Stage 7 fix 2 / M4: the RELATION, not the tracked default. Asserting `registered.isEmpty`
    /// made this go RED on the first machine whose (untracked, `#include?`d) `Local.xcconfig` sets
    /// the flag — i.e. the gate broke for the developer who did the very thing the flag exists for.
    /// What M6 is actually about is that the Team ID alone is not enough, and that holds whatever
    /// the build says. The values are read the way `SignInCapabilities` reads them, from this
    /// bundle.
    ///
    /// Stage 8 / S9: the relation is now `configured ⇔ registered non-empty`. The Team-ID term was
    /// `true` in every tracked configuration and the flag it was ANDed onto cannot be set without
    /// one, so the conjunction restated its own second half. `teamId` is still read — the M6 row
    /// below is what keeps this test saying what M6 is about.
    @Test func appleNeedsTheRegisteredFlagAndNotJustATeamId() {
        let teamId = Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN") as? String
        let registered = Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN_REGISTERED") as? String

        #expect(SignInCapabilities.appleSignInIsConfigured == (registered?.isEmpty == false),
                "configured exactly when the portal-registration flag is non-empty: FITRAH_APPLE_SIGNIN=\(teamId ?? "nil"), FITRAH_APPLE_SIGNIN_REGISTERED=\(registered ?? "nil")")
        // The half M6 added, stated as its own row: a Team ID with no portal registration is NOT
        // a configured Apple sign-in, which is the state both tracked xcconfigs ship.
        if teamId?.isEmpty == false, registered?.isEmpty != false {
            #expect(SignInCapabilities.appleSignInIsConfigured == false,
                    "a committed Team ID alone made the Apple button claim a capability the App ID lacks")
        }
    }

    /// All eight combinations of the pure table. Task 10 renders exactly this list, in this order.
    @Test func theTableDrivesAllEightCombinations() {
        func visible(_ emailPassword: Bool, _ google: Bool, _ apple: Bool) -> [SignInProvider] {
            SignInCapabilities.visibleProviders(
                SignInCapabilities(emailPassword: emailPassword, google: google, apple: apple))
        }
        #expect(visible(false, false, false) == [])
        // Stage 1 / B9: the three inconsistent rows below are no longer refused a SECOND time here.
        // `current()` is the single producer and ANDs both federated flags onto `emailPassword`
        // (pinned by `federatedProvidersAreImpossibleWithoutFirebase` below), so a value with a
        // federated flag set and `emailPassword` clear is one only a test can construct; the
        // runtime `guard provider.isAvailable` in `SignInViewModel` is the defence that earns its
        // keep, because a provider can lose its prerequisite between render and tap.
        #expect(visible(false, false, true) == [.apple])
        #expect(visible(false, true, false) == [.google])
        #expect(visible(false, true, true) == [.google, .apple])
        #expect(visible(true, false, false) == [.emailPassword])
        #expect(visible(true, false, true) == [.emailPassword, .apple])
        #expect(visible(true, true, false) == [.emailPassword, .google])
        #expect(visible(true, true, true) == [.emailPassword, .google, .apple])
    }

    /// The trap this exists to prevent: a Google or Apple button with Firebase unconfigured is a
    /// button that cannot possibly work.
    ///
    /// Stage 1 / B9: pinned at the ONE producer, not at three layers. `current()` ANDs both
    /// federated flags onto `emailPassword`, so an inconsistent `SignInCapabilities` cannot be
    /// produced by anything but a test — and `visibleProviders` no longer re-derives the rule.
    @Test func federatedProvidersAreImpossibleWithoutFirebase() {
        // The AND in `current()` itself: with no options file neither flag can be true, whatever the
        // build-time Apple flag says.
        if !FirebaseBootstrap.optionsFileExists {
            let current = SignInCapabilities.current()
            #expect(current.emailPassword == false)
            #expect(current.apple == false)
            #expect(current.google == false)
            #expect(SignInCapabilities.visibleProviders(current) == [],
                    "the rule still holds end to end: no options file, no buttons")
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
