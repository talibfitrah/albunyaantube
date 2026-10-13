import GoogleSignIn
import UIKit

/// The SIXTH and last app file allowed to name a Google/Firebase SDK type (plan Global
/// Constraints). Above this file the import flow sees `any YouTubeAuthorizer` and a `String`.
///
/// **Authorization, and nothing else.** A Google user who already exists gets the scope added
/// (`addScopes`, `GIDGoogleUser.h:88`); with none — an email/password or Apple account — Google's
/// own account picker signs one in WITH the scope (`GIDSignIn.h:191-193`), Android's
/// `AuthorizationClient` parity. Either way the consent screen is Google's own, not a YouTube
/// redirect (owner directive 2026-08-27), and the result is an access token and nothing more: it
/// never reaches Firebase, links no provider and never changes the signed-in account.
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

    /// The two prerequisites Google sign-in itself needs (a client id from the plist AND a matching
    /// callback scheme in this bundle) — and nothing about who is signed in: every account may
    /// import (`MeFragment.kt:295-299`), because `authorize()` raises the picker when there is no
    /// Google user to extend.
    var isAvailable: Bool { SignInCapabilities.current().google }

    /// The signed-in account's own Google identity (`AuthUser.googleUserID`), read at
    /// `authorize()` time; nil for an account with no Google provider.
    private let accountGoogleUserID: () -> String?

    init(accountGoogleUserID: @escaping () -> String?) { self.accountGoogleUserID = accountGoogleUserID }

    /// The SDK's session is DEVICE-wide and can belong to another Google identity (the app killed
    /// mid Google sign-in, a sheet that completed after sign-out), so it is reused only when it IS
    /// this account's own — `google.com` among the providers is not enough.
    func reusesGoogleSession(of sessionUserID: String?) -> Bool {
        sessionUserID != nil && sessionUserID == accountGoogleUserID()
    }

    func authorize() async throws -> String {
        // Configure-first, `GoogleAuthProvider.presentSignIn`'s reason: the `GIDConfiguration`
        // hand-off lives inside `FirebaseBootstrap`'s configure latch.
        guard isAvailable, FirebaseBootstrap.configureIfPossible(),
              let presenter = Self.presenter else {
            throw YouTubeAuthorizerError.unavailable
        }
        // A Google account: its own session. There is no `currentUser` after a relaunch until
        // somebody asks for one (the SDK never restores it at launch, `GIDSignIn.m:510-540`).
        // "Attempts to restore a previous user sign-in without interaction … refreshes tokens if
        // they have expired" (`GIDSignIn.h:118-124`) — no UI, so it cannot be a hidden consent
        // prompt. Any session that is not this account's own Google identity — or any session at
        // all for an account without one — is cleared, and the picker below asks Google who this is.
        var user: GIDGoogleUser?
        if accountGoogleUserID() != nil {
            user = GIDSignIn.sharedInstance.currentUser
            if user == nil { user = try? await GIDSignIn.sharedInstance.restorePreviousSignIn() }
        }
        // Signed out or revoked while the restore ran: the restore re-saved the session that cleared
        // (`addSaveAuthCallback`, `GIDSignIn.m:917-930`). Drop it, and raise no sheet over what follows.
        if Task.isCancelled {
            GIDSignIn.sharedInstance.signOut()
            throw CancellationError()
        }
        if !reusesGoogleSession(of: user?.userID) {
            GIDSignIn.sharedInstance.signOut()
            user = nil
        }
        do {
            if let user, user.grantedScopes?.contains(Self.scope) == true {
                // Already granted (a previous import, or a re-launch): the SDK's persisted access
                // token can still be hours old, so refresh before handing it to three paginators.
                let refreshed = try await user.refreshTokensIfNeeded()
                return refreshed.accessToken.tokenString
            }
            let result: GIDSignInResult
            if let user {
                result = try await user.addScopes([Self.scope], presenting: presenter)
            } else {
                // No Google user of this account's (every email/password or Apple import, a
                // forgotten grant, or a session of another Google identity cleared above):
                // Google's picker signs one in for this scope. Its result stays in this file — no
                // Firebase credential is made from it. A Google account's own id is the hint ("the
                // user's ID or email address", `GIDSignIn.h:186-187`), so the identity it reuses next
                // time is the one preselected; nil for an account without one.
                result = try await GIDSignIn.sharedInstance.signIn(withPresenting: presenter,
                                                                   hint: accountGoogleUserID(),
                                                                   additionalScopes: [Self.scope])
            }
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
    /// every scope the user ever granted (sign-in included). Consequence, deliberate: the next
    /// `authorize()` finds no Google user and asks Google again (picker and consent), the same
    /// state the Google permissions page's remedy leaves, so the confirmation's "no longer has
    /// access" is true either way.
    func forget() { GIDSignIn.sharedInstance.signOut() }

    /// Presentation context for `ASWebAuthenticationSession` only — the key window's root is
    /// enough (`GoogleAuthProvider.presenter`).
    private static var presenter: UIViewController? {
        UIApplication.shared.fitrahKeyWindow?.rootViewController
    }
}
