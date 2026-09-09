import Foundation

/// What an authorization attempt can answer with. Deliberately three arms, not an SDK error: the
/// layer above renders a message, and only "the user backed out" is worth telling apart (the
/// `OAuthSignInFailure` shape, one flow over).
nonisolated enum YouTubeAuthorizerError: Error, Sendable, Equatable {
    /// No Google account signed in, or no client id / callback scheme in this build. The import
    /// affordance should not have been offered — `isAvailable` says so before anything is asked.
    case unavailable
    /// The user dismissed the consent screen. Silent: back to idle, never a banner.
    case cancelled
    /// The SDK refused for any other reason.
    case failed
}

/// The seam Android built for exactly this reason (`YouTubeAuthManager.kt:44-71`): the layer above
/// tests against a fake with no Google SDK.
///
/// **INCREMENTAL** — the scope is requested when the user starts an import, NEVER at sign-in
/// (`:45-46`). Asking at sign-in would put "See your YouTube account" on the consent screen of
/// every user who will never touch the import flow.
@MainActor protocol YouTubeAuthorizer: AnyObject {
    static var scope: String { get }
    var isAvailable: Bool { get }
    /// An OAuth 2.0 access token, WITHOUT the "Bearer " prefix. It is a credential for
    /// `googleapis.com` only: it never reaches the FitrahTube backend and is never logged.
    func authorize() async throws -> String
    /// F9: drop the in-memory token. NEVER `GIDSignIn.disconnect()` — that revokes every granted
    /// scope and signs the Google user out of the app (ruling C6's rule, one scope over).
    func forget()
}

extension YouTubeAuthorizer {
    /// ONE spelling, shared by the production authorizer and the fake, because a scope string that
    /// drifts between them is a consent screen that grants nothing and three paginators that 403 —
    /// invisible until a device run.
    static var scope: String { "https://www.googleapis.com/auth/youtube.readonly" }
}
