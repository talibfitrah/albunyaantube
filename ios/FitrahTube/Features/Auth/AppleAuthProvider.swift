import AuthenticationServices
import CryptoKit
import UIKit

/// One of the five app files allowed to name a Google/Firebase/Apple sign-in SDK type (plan Global
/// Constraints). Above this file the Apple flow is `any OAuthSignInProvider` and an
/// `OAuthCredential`.
///
/// **The nonce is used twice, in two forms** (Firebase's replay defence): Apple is handed the
/// SHA-256 HASH of a fresh random value, and Firebase is handed the RAW value, which it re-hashes
/// and compares against the hash baked into Apple's identity token. Sending the same form to both,
/// or reusing one across flows, defeats the point — so it is generated per `presentSignIn()`.
@MainActor final class AppleAuthProvider: NSObject, OAuthSignInProvider {

    /// Both halves must hold: a Team ID signed this build (`FITRAH_APPLE_SIGNIN`, the only signal
    /// available — there is no runtime entitlement API) AND Firebase can redeem the credential.
    var isAvailable: Bool { SignInCapabilities.current().apple }

    /// The in-flight flow. `ASAuthorizationController` must be retained until it calls back, and the
    /// continuation must be resumed exactly once — `finish` is the single resume site.
    private var controller: ASAuthorizationController?
    private var pending: CheckedContinuation<ASAuthorization, any Error>?

    func presentSignIn() async throws(AuthErrorCode) -> OAuthCredential {
        // Same configure-first rule as Google's: the credential is only worth anything if Firebase
        // is there to redeem it, and `AuthClient` is unavailable otherwise.
        guard FirebaseBootstrap.configureIfPossible() else { throw .appleSignInFailed }
        let rawNonce = Self.randomNonce()
        do {
            let request = ASAuthorizationAppleIDProvider().createRequest()
            request.requestedScopes = [.fullName, .email]
            request.nonce = Self.sha256(rawNonce)
            let authorization = try await withCheckedThrowingContinuation { continuation in
                pending = continuation
                let controller = ASAuthorizationController(authorizationRequests: [request])
                controller.delegate = self
                controller.presentationContextProvider = self
                self.controller = controller
                controller.performRequests()
            }
            guard let credential = authorization.credential as? ASAuthorizationAppleIDCredential,
                  let identityToken = credential.identityToken,
                  let idToken = String(data: identityToken, encoding: .utf8) else {
                throw AuthErrorCode.appleSignInFailed
            }
            return OAuthCredential(providerID: "apple.com", idToken: idToken, accessTokenOrNonce: rawNonce)
        } catch {
            // Cancellation included: this is the pre-Firebase leg, so there is no Firebase NSError
            // domain to map and `.appleSignInFailed` is the one code Task 4's table carries for it.
            throw AuthErrorCode.appleSignInFailed
        }
    }

    private func finish(_ result: Result<ASAuthorization, any Error>) {
        controller = nil
        pending?.resume(with: result)
        pending = nil
    }

    /// Apple requires an unguessable value per request. `Int.random(in:)` draws from
    /// `SystemRandomNumberGenerator`, i.e. the platform CSPRNG — no `SecRandomCopyBytes` ceremony.
    /// The character set is the URL-safe one Apple's own sample uses.
    private static func randomNonce(length: Int = 32) -> String {
        let charset = Array("0123456789ABCDEFGHIJKLMNOPQRSTUVXYZabcdefghijklmnopqrstuvwxyz-._")
        return String((0..<length).map { _ in charset[Int.random(in: 0..<charset.count)] })
    }

    private static func sha256(_ input: String) -> String {
        SHA256.hash(data: Data(input.utf8)).map { String(format: "%02x", $0) }.joined()
    }
}

extension AppleAuthProvider: ASAuthorizationControllerDelegate, ASAuthorizationControllerPresentationContextProviding {
    func authorizationController(controller: ASAuthorizationController,
                                 didCompleteWithAuthorization authorization: ASAuthorization) {
        finish(.success(authorization))
    }

    func authorizationController(controller: ASAuthorizationController, didCompleteWithError error: any Error) {
        finish(.failure(error))
    }

    /// `ASPresentationAnchor` is `UIWindow` on iOS. The fallback is an empty window rather than a
    /// trap: a sign-in sheet with nowhere to appear should fail the flow, not the process.
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow) ?? ASPresentationAnchor()
    }
}
