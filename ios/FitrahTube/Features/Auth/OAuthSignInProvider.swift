import Foundation

/// One federated sign-in button's worth of behaviour: say whether you can run at all, and if asked,
/// run your own SDK's UI and hand back an opaque `OAuthCredential` for `AuthClient.signIn(with:)`.
///
/// `@MainActor` because both conformers drive UIKit presentation; `AnyObject` because both hold
/// per-flow state (Apple's nonce and its in-flight continuation) across a suspension.
@MainActor protocol OAuthSignInProvider: AnyObject {
    var isAvailable: Bool { get }
    func presentSignIn() async throws(OAuthSignInFailure) -> OAuthCredential
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

    /// The ONE reader of the build-time Apple flag (`AppleAuthProvider` asks through `current()`).
    /// Non-empty means a Team ID signed this build; empty means no signing identity, so no Apple
    /// button. There is no runtime entitlement API to consult instead.
    static var appleSignInIsConfigured: Bool {
        (Bundle.main.object(forInfoDictionaryKey: "FITRAH_APPLE_SIGNIN") as? String)?.isEmpty == false
    }

    /// The pure table Task 10 renders. The `emailPassword` guard is deliberately a SECOND defence:
    /// this struct is a plain value anyone can construct, and an inconsistent one must still not
    /// produce a federated button.
    static func visibleProviders(_ capabilities: SignInCapabilities) -> [SignInProvider] {
        guard capabilities.emailPassword else { return [] }
        return SignInProvider.allCases.filter {
            switch $0 {
            case .emailPassword: true
            case .google: capabilities.google
            case .apple: capabilities.apple
            }
        }
    }
}
