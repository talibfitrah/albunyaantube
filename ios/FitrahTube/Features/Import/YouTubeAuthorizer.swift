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
    /// F9: forget the token LOCALLY — the SDK's Keychain session, which is where the token lives
    /// (Part B gate, stage 4 S7: nothing is cached above the SDK, so a `forget()` that cleared only
    /// a field forgot nothing and the next tap re-minted with no consent screen under copy that
    /// said access was gone). NEVER `GIDSignIn.disconnect()`
    /// — that revokes every granted scope server-side and signs the Google user out of the app
    /// (ruling C6's rule, one scope over). The grant itself is the user's to revoke on Google's
    /// account-permissions page, which the confirmation links to.
    func forget()
}

extension YouTubeAuthorizer {
    /// ONE spelling, shared by the production authorizer and the fake, because a scope string that
    /// drifts between them is a consent screen that grants nothing and three paginators that 403 —
    /// invisible until a device run.
    static var scope: String { "https://www.googleapis.com/auth/youtube.readonly" }
}

#if DEBUG
/// The FIXTURE authorizer, in the app target for `FakeAuthClient`'s reason: `AppContainer.fake()`
/// cannot see the test bundle.
///
/// **`available: false` is the default and the honest one** — a screenshot rig has no Google grant,
/// so `isAvailable` is false, the Me kebab's Import row is ABSENT (RULING 28), and `authorize()`
/// refuses rather than inventing a token.
///
/// Task 30 adds the other setting, for ONE purpose: `-fitrah-seed-import-review` photographs the
/// import review screen, which needs a token to get past `.authorizing`. The token it hands back is
/// a synthetic string, and the only thing that ever receives it is a `ScriptedTransport` holding
/// canned googleapis pages — nothing in a fixture run reaches `googleapis.com`.
@MainActor final class UnavailableYouTubeAuthorizer: YouTubeAuthorizer {
    static let fixtureToken = "fixture-youtube-access-token"

    let isAvailable: Bool
    private(set) var forgetCount = 0

    init(available: Bool = false) { isAvailable = available }

    func authorize() async throws -> String {
        guard isAvailable else { throw YouTubeAuthorizerError.unavailable }
        return Self.fixtureToken
    }

    func forget() { forgetCount += 1 }
}
#endif
