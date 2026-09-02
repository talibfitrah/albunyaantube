import GoogleSignIn
import UIKit

/// One of the five app files allowed to name a Google/Firebase SDK type (plan Global Constraints).
/// Above this file the Google flow is `any OAuthSignInProvider` and an `OAuthCredential`.
///
/// It shadows `FirebaseAuth.GoogleAuthProvider` inside this module, which is why
/// `FirebaseAuthClient` qualifies that reference — same defence it already applies to
/// `FitrahTube.OAuthCredential`.
///
/// **Main actor throughout.** `GIDSignIn.sharedInstance` is main-thread-affine and carries no actor
/// annotation, so every touch of it here is on the MainActor by the protocol's own isolation; a
/// caller from elsewhere hops before it arrives. `FirebaseBootstrap` stays `nonisolated`.
@MainActor final class GoogleAuthProvider: OAuthSignInProvider {

    /// A missing client id DISABLES the button, never traps (`SignInFragment.kt:220-226`).
    /// Equivalent to `FirebaseBootstrap.googleClientID != nil` — the client id can only come from
    /// the options file — but asked through `SignInCapabilities` so the screen and the provider
    /// cannot disagree about who is available.
    var isAvailable: Bool { SignInCapabilities.current().google }

    func presentSignIn() async throws(AuthErrorCode) -> OAuthCredential {
        // Configure-first, and not merely "read the client id": the `GIDConfiguration` hand-off
        // lives INSIDE `FirebaseBootstrap`'s configure latch, so a path that read `googleClientID`
        // and skipped the latch would reach a `GIDSignIn` whose `configuration` is still nil.
        guard FirebaseBootstrap.configureIfPossible(), let presenter = Self.presenter else {
            throw .googleSignInFailed
        }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else { throw AuthErrorCode.googleSignInFailed }
            // `google.com` — `FirebaseAuth.GoogleAuthProvider.id`, spelled literally so this file's
            // output stays readable without importing FirebaseAuth.
            return OAuthCredential(providerID: "google.com", idToken: idToken,
                                   accessTokenOrNonce: result.user.accessToken.tokenString)
        } catch {
            // Every GIDSignIn failure — cancellation included — is one app code: this is the
            // pre-Firebase leg, so there is no `NSError` domain worth mapping (`AuthErrorCode`
            // carries `googleSignInFailed` for exactly this, Task 4's table).
            throw AuthErrorCode.googleSignInFailed
        }
    }

    /// GIDSignIn uses this only as the presentation context for `ASWebAuthenticationSession`, so the
    /// key window's root is enough — it does not need the topmost presented controller.
    private static var presenter: UIViewController? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)?
            .rootViewController
    }
}
