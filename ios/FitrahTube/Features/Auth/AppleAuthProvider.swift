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

    /// ONE flow at a time. `pending` is a single continuation slot, so a second `presentSignIn()`
    /// while one is in flight used to overwrite it and orphan the first — never resumed,
    /// `SWIFT TASK CONTINUATION MISUSE`, and a double-tap on the Apple button is all it takes.
    /// `@MainActor` prevents a data race but not re-entrancy across the flow's suspension.
    ///
    /// Internal, not private, and a plain `Bool` rather than `pending != nil`: it is the only
    /// observable an in-gate test has. With no `GoogleService-Info.plist` `presentSignIn()` stops at
    /// its configure guard and never reaches the flow, so the latch cannot be claimed from outside.
    var isPresenting = false

    /// The in-flight flow. `ASAuthorizationController` must be retained until it calls back, and the
    /// continuation must be resumed exactly once — `finish` is the single resume site.
    private var controller: ASAuthorizationController?
    private var pending: CheckedContinuation<ASAuthorization, any Error>?
    /// The window the sheet is presented over. Set from the guard below before every
    /// `performRequests()`, so `presentationAnchor(for:)` never has to invent one.
    private var anchor: ASPresentationAnchor!

    func presentSignIn() async throws(OAuthSignInFailure) -> OAuthCredential {
        // BEFORE the claim below, so a refused re-entrant call never runs the release path and
        // clears the first flow's latch. `SignInViewModel` refuses the second tap itself, so this
        // is the backstop, not the user-visible path.
        guard !isPresenting else { throw .failed(.appleSignInFailed) }
        isPresenting = true
        defer { isPresenting = false }
        // Same configure-first rule as Google's: the credential is only worth anything if Firebase
        // is there to redeem it, and `AuthClient` is unavailable otherwise. The key window is
        // guarded here rather than substituted in `presentationAnchor(for:)`: a DETACHED anchor
        // does not guarantee Apple ever calls back, which is the same orphaned-continuation hang by
        // another route. Refuse before `performRequests()`, never during.
        guard FirebaseBootstrap.configureIfPossible(), let window = Self.keyWindow else {
            throw .failed(.appleSignInFailed)
        }
        anchor = window
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
                throw OAuthSignInFailure.failed(.appleSignInFailed)
            }
            return OAuthCredential(providerID: "apple.com", idToken: idToken, accessTokenOrNonce: rawNonce)
        } catch let failure as OAuthSignInFailure {
            throw failure
        } catch {
            // The ONE case worth telling apart: dismissing Apple's sheet is the user's own choice,
            // and an error banner over it is wrong. The domain is checked alongside the code so a
            // `1001` from another `NSError` can never read as a cancel. Everything else is one app
            // code — this is the pre-Firebase leg, with no Firebase domain to map.
            let nsError = error as NSError
            if nsError.domain == ASAuthorizationError.errorDomain,
               nsError.code == ASAuthorizationError.canceled.rawValue {
                throw .cancelled
            }
            throw .failed(.appleSignInFailed)
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

    private static var keyWindow: UIWindow? {
        UIApplication.shared.connectedScenes
            .compactMap { $0 as? UIWindowScene }
            .flatMap(\.windows)
            .first(where: \.isKeyWindow)
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

    /// `ASPresentationAnchor` is `UIWindow` on iOS. Non-nil for the life of a flow: `presentSignIn()`
    /// refuses before `performRequests()` when there is no key window, and this is only called
    /// during a flow it started — so there is no detached-window arm to hang on.
    func presentationAnchor(for controller: ASAuthorizationController) -> ASPresentationAnchor {
        anchor
    }
}
