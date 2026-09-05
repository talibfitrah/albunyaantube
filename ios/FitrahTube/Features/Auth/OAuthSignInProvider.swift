import Foundation

/// One federated sign-in button's worth of behaviour: say whether you can run at all, and if asked,
/// run your own SDK's UI and hand back an opaque `OAuthCredential` for `AuthClient.signIn(with:)`.
///
/// `@MainActor` because both conformers drive UIKit presentation; `AnyObject` because both hold
/// per-flow state (Apple's nonce and its in-flight continuation) across a suspension.
@MainActor protocol OAuthSignInProvider: AnyObject {
    var isAvailable: Bool { get }
    func presentSignIn() async throws(OAuthSignInFailure) -> OAuthCredential
    /// Forgets whatever the provider's OWN SDK persisted, on top of Firebase's sign-out.
    ///
    /// Stage 4 / I1: `GIDSignIn` keeps its `currentUser` — an access token **and a refresh token
    /// for the user's Google account** — in the app's Keychain, and nothing in the app ever asked it
    /// to sign out. That credential outlived both `AccountSession.signOut()` and the ruling-C13
    /// device wipe, whose own dialog tells the user the account is gone. `signOut()`, never
    /// `disconnect()` (ruling C6): revoking the grant is not what the user asked for.
    func signOutProvider()
}

/// Why a provider leg ended without a credential. Task 5 shipped a bare `AuthErrorCode`, which
/// collapsed **the user dismissing the sheet** into `.googleSignInFailed`/`.appleSignInFailed` — so
/// deliberately backing out of Apple's dialog raised an error banner over a screen the user had just
/// decided not to use. A cancel is not a failure and gets no copy at all (Task 10 ruling).
///
/// A dedicated type, rather than a 14th `AuthErrorCode` case: every code in that table is something
/// a screen RENDERS, and `.cancelled` renders nothing — it would be the one case with a `messageKey`
/// no user can ever see.
nonisolated enum OAuthSignInFailure: Error, Sendable, Equatable {
    /// The user dismissed the provider's sheet. Silent: back to idle, never a banner.
    case cancelled
    case failed(AuthErrorCode)
}

/// The sign-in affordances a screen may render, in the order Task 10 renders them. Deliberately a
/// UI-free value: it names no provider TYPE, so the SDK types stay in their two files.
nonisolated enum SignInProvider: Sendable, Equatable, CaseIterable {
    case emailPassword, google, apple
}

/// F11: a button renders only when its prerequisite exists. Google needs TWO — a client id from the
/// plist AND a callback scheme in this bundle that matches it (`googleCallbackSchemeMatches`).
/// Apple's entitlement CANNOT be read at runtime — there is no public API, and an unsigned
/// simulator build (`CODE_SIGNING_ALLOWED[sdk=iphonesimulator*]: NO`, `project.yml:26`) carries no
/// embedded entitlements at all — so `apple` is a BUILD-TIME flag: Task 2 writes
/// `FITRAH_APPLE_SIGNIN: $(FITRAH_TEAM_ID)` into Info.plist, and a non-empty value means a Team ID
/// signed this build and the App ID capability could be present. With no Team ID it is empty and
/// the button is hidden — the honest answer. First real verification: Tier 3 item 6.
nonisolated struct SignInCapabilities: Sendable, Equatable {
    var emailPassword: Bool
    var google: Bool
    var apple: Bool

    /// Both federated flags are ANDed onto `emailPassword`: every provider credential is redeemed
    /// through Firebase (`AuthClient.signIn(with:)`), so a Google or Apple button with no Firebase
    /// options file is a button that cannot possibly finish — a trap, not a degraded path.
    static func current() -> SignInCapabilities {
        let emailPassword = FirebaseBootstrap.optionsFileExists
        return SignInCapabilities(
            emailPassword: emailPassword,
            google: emailPassword && googleCallbackSchemeMatches(clientID: FirebaseBootstrap.googleClientID,
                                                                 schemes: bundleURLSchemes),
            apple: emailPassword && appleSignInIsConfigured)
    }

    /// Google's OTHER prerequisite, as a pure table test (Stage 9 round 5 / R5-P1).
    ///
    /// The client id and the OAuth callback scheme come from two independent, separately-untracked
    /// sources — `GoogleService-Info.plist` via `copy-firebase-plist.sh`, and
    /// `GID_REVERSED_CLIENT_ID` via an `#include?`d `Local.xcconfig` — and either can land without
    /// the other. Both tracked xcconfigs ship the placeholder
    /// `com.googleusercontent.apps.fitrahtube-no-client-id`, so the FIRST build to carry a real
    /// plist renders a Google button whose tap reaches `GIDSignIn.signIn(withPresenting:)`, which
    /// raises `NSInvalidArgumentException` over the schemes it cannot find — an Objective-C
    /// exception, not a Swift `Error`, so `GoogleAuthProvider`'s `do/catch` cannot see it and the
    /// app terminates.
    ///
    /// The rule is the SDK's own, verified against the pinned checkout (GoogleSignIn 8.0.0,
    /// `GIDSignInCallbackSchemes.m:51-56`):
    ///
    ///     NSArray *clientIdentifierParts = [_clientIdentifier componentsSeparatedByString:@"."];
    ///     NSString *reversedClientIdentifier =
    ///         [[clientIdentifierParts reverseObjectEnumerator].allObjects componentsJoinedByString:@"."];
    ///     return reversedClientIdentifier.lowercaseString;
    ///
    /// `components(separatedBy:)`, not `split(separator:)`: the SDK keeps empty components, and a
    /// rule that silently disagrees with the SDK on an edge case is the defect this closes.
    /// `relevantURLSchemes` (`:30-41`) lowercases the bundle's side too, so the comparison is
    /// case-insensitive on both.
    nonisolated static func googleCallbackSchemeMatches(clientID: String?, schemes: [String]) -> Bool {
        guard let clientID else { return false }
        let expected = clientID.components(separatedBy: ".").reversed().joined(separator: ".").lowercased()
        return schemes.contains { $0.lowercased() == expected }
    }

    /// Every `CFBundleURLSchemes` entry in this bundle, flattened across URL types — the same list
    /// GoogleSignIn reads (`GIDSignInCallbackSchemes.m:30-41`).
    static var bundleURLSchemes: [String] {
        (Bundle.main.object(forInfoDictionaryKey: "CFBundleURLTypes") as? [[String: Any]] ?? [])
            .flatMap { $0["CFBundleURLSchemes"] as? [String] ?? [] }
    }

    /// The ONE reader of the build-time Apple flag. There is no runtime entitlement API to consult
    /// instead, so it is a build setting: `FITRAH_APPLE_SIGNIN_REGISTERED` records that the App ID
    /// `com.albunyaan.tube` actually has the Sign in with Apple capability enabled in the developer
    /// portal. EMPTY in both tracked xcconfigs; set once, in the untracked `Local.xcconfig`, by
    /// whoever created that portal entry.
    ///
    /// Stage 5 / M6 added it because `FITRAH_APPLE_SIGNIN` (= `FITRAH_TEAM_ID`) alone was true in
    /// every build while the App ID was not registered — the F11 trap exactly, a button that
    /// renders and then fails `performRequests()` on the first signed device build.
    ///
    /// Stage 8 / S9: and it stands ALONE. The Team-ID term it was ANDed onto is `true` in every
    /// tracked configuration, and the portal entry this flag records cannot exist without a Team ID
    /// in the first place, so the conjunction only ever restated its own second half.
    /// `FITRAH_APPLE_SIGNIN` stays in `project.yml`/the Info.plist (build-config removal is out of
    /// scope) and keeps one reader: `SignInCapabilitiesTests`' M6 row.
    static var appleSignInIsConfigured: Bool { info("FITRAH_APPLE_SIGNIN_REGISTERED") }

    private static func info(_ key: String) -> Bool {
        (Bundle.main.object(forInfoDictionaryKey: key) as? String)?.isEmpty == false
    }

    /// The pure table Task 10 renders.
    ///
    /// Stage 1 / B9: the `guard capabilities.emailPassword else { return [] }` that used to open
    /// this function is gone. It re-validated what `current()` above had just produced — three
    /// spellings of one rule, and this was the middle one. `current()` remains the single producer
    /// (both federated flags are ANDed onto `emailPassword` there) and `SignInViewModel`'s
    /// `guard provider.isAvailable` remains the runtime defence, which earns its keep because a
    /// provider can lose its prerequisite between render and tap. This layer only ever defended
    /// against a hand-constructed inconsistent value, which only tests build.
    static func visibleProviders(_ capabilities: SignInCapabilities) -> [SignInProvider] {
        SignInProvider.allCases.filter {
            switch $0 {
            case .emailPassword: capabilities.emailPassword
            case .google: capabilities.google
            case .apple: capabilities.apple
            }
        }
    }
}
