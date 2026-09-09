import GoogleSignIn
import UIKit

/// The SIXTH and last app file allowed to name a Google/Firebase SDK type (plan Global
/// Constraints). Above this file the import flow sees `any YouTubeAuthorizer` and a `String`.
///
/// **Incremental authorization, and nothing else.** `addScopes` lives on `GIDGoogleUser`, not on
/// `GIDSignIn` (`GIDGoogleUser.h:88`) — so there must already be a signed-in Google user, which is
/// exactly the precondition the flow wants: Import is offered to a signed-in account, and the
/// consent screen it raises is Google's own, not a YouTube redirect (owner directive 2026-08-27).
///
/// **Main actor throughout**, `GoogleAuthProvider`'s reason: `GIDSignIn.sharedInstance` is
/// main-thread-affine and carries no actor annotation.
///
/// The token is held **in memory only** — CF-A-10. An access token expires within the hour, so the
/// Keychain would buy persistence nothing here wants and would leave a live credential on disk
/// after the import that needed it finished.
@MainActor final class GoogleYouTubeAuthorizer: YouTubeAuthorizer {

    private var token: String?

    /// Same two prerequisites Google sign-in itself needs (a client id from the plist AND a
    /// matching callback scheme in this bundle), plus a Google grant to extend.
    ///
    /// **`currentUser` alone would be wrong, and silently so** (review C1). The SDK never restores
    /// it at launch: its initializer reads the bundle configuration and migrates keychain state but
    /// assigns `_currentUser` nowhere (`GIDSignIn.m:510-540`), and the only two assignments are the
    /// interactive sign-in completion (`:936`) and a restore. Reading it alone would make Import
    /// available for the session that signed in and unavailable in every session after — hidden,
    /// per RULING 28, with no error and nothing to debug from. `hasPreviousSignIn` is the
    /// keychain-backed half of the same fact ("Checks if there is a previous user sign-in saved in
    /// keychain", `GIDSignIn.h:113-116`, implemented over `loadAuthState` at `GIDSignIn.m:213-219`)
    /// and it survives relaunch. It stays FALSE for an Apple or email/password account, so the
    /// hiding rule for those accounts is preserved.
    var isAvailable: Bool {
        SignInCapabilities.current().google
            && (GIDSignIn.sharedInstance.currentUser != nil || GIDSignIn.sharedInstance.hasPreviousSignIn())
    }

    func authorize() async throws -> String {
        if let token { return token }
        // Configure-first, `GoogleAuthProvider.presentSignIn`'s reason: the `GIDConfiguration`
        // hand-off lives inside `FirebaseBootstrap`'s configure latch.
        guard isAvailable, FirebaseBootstrap.configureIfPossible(),
              let presenter = Self.presenter else {
            throw YouTubeAuthorizerError.unavailable
        }
        // The async half of `hasPreviousSignIn`, and the reason `isAvailable` can promise anything
        // after a relaunch: there is no `currentUser` until somebody asks for one. "Attempts to
        // restore a previous user sign-in without interaction … refreshes tokens if they have
        // expired" (`GIDSignIn.h:118-124`) — no UI, so it cannot be a hidden consent prompt. A
        // restore that fails means there is no Google grant left to extend, which is `.unavailable`
        // and not `.failed`: the affordance should not have been offered.
        var user = GIDSignIn.sharedInstance.currentUser
        if user == nil { user = try? await GIDSignIn.sharedInstance.restorePreviousSignIn() }
        guard let user else { throw YouTubeAuthorizerError.unavailable }
        do {
            if user.grantedScopes?.contains(Self.scope) == true {
                // Already granted (a previous import, or a re-launch): the SDK's persisted access
                // token can still be hours old, so refresh before handing it to three paginators.
                let refreshed = try await user.refreshTokensIfNeeded()
                return store(refreshed.accessToken.tokenString)
            }
            let result = try await user.addScopes([Self.scope], presenting: presenter)
            // The user can dismiss the sheet without granting; the SDK answers success either way.
            guard result.user.grantedScopes?.contains(Self.scope) == true else {
                throw YouTubeAuthorizerError.cancelled
            }
            return store(result.user.accessToken.tokenString)
        } catch let failure as YouTubeAuthorizerError {
            throw failure
        } catch {
            // The ONE case worth telling apart, `GoogleAuthProvider`'s check verbatim: the domain
            // is compared too, so a `-5` from some other `NSError` can never read as a cancel.
            let nsError = error as NSError
            if nsError.domain == kGIDSignInErrorDomain, nsError.code == GIDSignInError.canceled.rawValue {
                throw YouTubeAuthorizerError.cancelled
            }
            throw YouTubeAuthorizerError.failed
        }
    }

    /// F9. Drops the token this object holds and NOTHING else — never `GIDSignIn.disconnect()`,
    /// which revokes every scope the user ever granted (sign-in included) server-side, and never
    /// `signOut()`, which is `GoogleAuthProvider.signOutProvider`'s job on a different trigger.
    /// Revoking the grant itself is the user's to do, on Google's account-permissions page.
    func forget() { token = nil }

    private func store(_ value: String) -> String {
        token = value
        return value
    }

    /// Presentation context for `ASWebAuthenticationSession` only — the key window's root is
    /// enough (`GoogleAuthProvider.presenter`).
    private static var presenter: UIViewController? {
        UIApplication.shared.fitrahKeyWindow?.rootViewController
    }
}
