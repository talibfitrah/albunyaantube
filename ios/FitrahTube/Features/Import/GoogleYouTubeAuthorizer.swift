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
/// **Nothing is cached above the SDK** (Part B gate: stage 3 I-6, stage 4 S1, Codex 3). The first
/// version kept the last access token in a field and returned it before looking at anything else,
/// which meant two things at once: a token older than the hour was handed to the paginators forever
/// (three 403s, a Retry that could never succeed), and it survived sign-out — the next Google account
/// on the device imported the previous one's library. The SDK already holds the current user's
/// tokens and refreshes them; every call goes through it, so the token is always the CURRENT Google
/// user's and always fresh. Android's `YouTubeAuthManager` caches nothing either. CF-A-10 is
/// closed by construction.
@MainActor final class GoogleYouTubeAuthorizer: YouTubeAuthorizer {

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
                return refreshed.accessToken.tokenString
            }
            let result = try await user.addScopes([Self.scope], presenting: presenter)
            // The user can dismiss the sheet without granting; the SDK answers success either way.
            guard result.user.grantedScopes?.contains(Self.scope) == true else {
                throw YouTubeAuthorizerError.cancelled
            }
            return result.user.accessToken.tokenString
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

    /// F9, the keychain half: `signOut()` clears the SDK's LOCAL session — the persisted user, its
    /// tokens and the scope it holds — and nothing server-side. NEVER `disconnect()`, which revokes
    /// every scope the user ever granted (sign-in included). Consequence, deliberate: `isAvailable`
    /// reads false until the next Google sign-in, which is the same state the Google permissions
    /// page's remedy leaves (a revoked grant fails `restorePreviousSignIn`), so the confirmation's
    /// "no longer has access" is true either way.
    func forget() { GIDSignIn.sharedInstance.signOut() }

    /// Presentation context for `ASWebAuthenticationSession` only — the key window's root is
    /// enough (`GoogleAuthProvider.presenter`).
    private static var presenter: UIViewController? {
        UIApplication.shared.fitrahKeyWindow?.rootViewController
    }
}
