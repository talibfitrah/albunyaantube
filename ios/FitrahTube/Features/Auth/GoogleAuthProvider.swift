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

    func presentSignIn() async throws(OAuthSignInFailure) -> OAuthCredential {
        // Configure-first, and not merely "read the client id": the `GIDConfiguration` hand-off
        // lives INSIDE `FirebaseBootstrap`'s configure latch, so a path that read `googleClientID`
        // and skipped the latch would reach a `GIDSignIn` whose `configuration` is still nil.
        guard FirebaseBootstrap.configureIfPossible(), let presenter = Self.presenter else {
            throw .failed(.googleSignInFailed)
        }
        do {
            let result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter)
            guard let idToken = result.user.idToken?.tokenString else { throw OAuthSignInFailure.failed(.googleSignInFailed) }
            // `google.com` — `FirebaseAuth.GoogleAuthProvider.id`, spelled literally so this file's
            // output stays readable without importing FirebaseAuth.
            return OAuthCredential(providerID: "google.com", idToken: idToken,
                                   accessTokenOrNonce: result.user.accessToken.tokenString)
        } catch let failure as OAuthSignInFailure {
            throw failure
        } catch {
            // The ONE case worth telling apart: the user backed out. `NS_ERROR_ENUM` bridges
            // `kGIDSignInErrorCodeCanceled` to `GIDSignInError.canceled`, and the domain is checked
            // too so a `-5` from some other `NSError` can never read as a cancel. Everything else is
            // one app code — this is the pre-Firebase leg, with no domain worth mapping further.
            let nsError = error as NSError
            if nsError.domain == kGIDSignInErrorDomain, nsError.code == GIDSignInError.canceled.rawValue {
                throw .cancelled
            }
            throw .failed(.googleSignInFailed)
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
