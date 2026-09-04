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

/// F11: a button renders only when its prerequisite exists. Google needs a client id from the
/// plist. Apple's entitlement CANNOT be read at runtime — there is no public API, and an unsigned
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
        return SignInCapabilities(emailPassword: emailPassword,
                                  google: emailPassword && FirebaseBootstrap.googleClientID != nil,
                                  apple: emailPassword && appleSignInIsConfigured)
    }

    /// The ONE reader of the build-time Apple flags (`AppleAuthProvider` asks through `current()`).
    /// There is no runtime entitlement API to consult instead, so BOTH halves are build settings:
    ///
    ///  * `FITRAH_APPLE_SIGNIN` (= `FITRAH_TEAM_ID`) — a Team ID signed this build at all;
    ///  * `FITRAH_APPLE_SIGNIN_REGISTERED` — the App ID `com.albunyaan.tube` actually has the Sign
    ///    in with Apple capability enabled in the developer portal.
    ///
    /// Stage 5 / M6: the Team ID is committed in both tracked xcconfigs, so the first flag alone was
    /// true in every build while the entitlements file's own header records that the App ID is NOT
    /// registered — the F11 trap exactly, a button that renders and then fails `performRequests()`
    /// on the first signed device build. The second flag is EMPTY in both tracked xcconfigs and is
    /// set once, in the untracked `Local.xcconfig`, by whoever created the portal entry.
    static var appleSignInIsConfigured: Bool {
        info("FITRAH_APPLE_SIGNIN") && info("FITRAH_APPLE_SIGNIN_REGISTERED")
    }

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
