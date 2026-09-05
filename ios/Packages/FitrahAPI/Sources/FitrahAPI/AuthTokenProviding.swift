import Foundation

/// The app's ONE token source, seen from the transport side (ruling F12). `AppContainer.auth`
/// conforms to this by declaration — `AuthClient` refines it — so `AuthorizedTransport` (Task 7)
/// takes that object with no adapter between them.
///
/// It lives in the package, not the app, because its consumers do: the hand-written `HTTPTransport`
/// and the generated `Client`'s middleware chain are both here.
public protocol AuthTokenProviding: Sendable {
    /// nil = send unsigned and let the backend's 401 drive the refresh
    /// (`FirebaseAuthInterceptor.kt:95-103`, a 3 s budget on the fetch).
    func idToken(forceRefresh: Bool) async -> BearerToken?
}

/// A bearer and the account it was minted for, read TOGETHER. The identity exists for exactly one
/// reason (Task 6 review I2): `BearerRetry` must not replay account A's in-flight request signed
/// with account B's bearer when a sign-out plus sign-in as somebody else lands between the two
/// attempts — `FirebaseAuthInterceptor.kt:131-143` calls that a P0 identity leak and refuses the
/// replay. Asking the source for the uid *separately* from the token would race with exactly the
/// event being guarded against, so the pair is atomic at the source instead.
public struct BearerToken: Sendable, Equatable {
    public let value: String
    /// nil when the source has no notion of identity. Two nils compare equal, so such a source
    /// never blocks its own retry.
    public let identity: String?

    public init(value: String, identity: String?) {
        self.value = value
        self.identity = identity
    }
}

/// Spec §8: the Bearer never leaves the configured API host. Scope is per-HOST — port and scheme
/// are deliberately ignored (`FirebaseAuthInterceptor.kt:54-59`; Debug points at
/// `http://localhost:8080/` and those requests must still be signed). ONE copy, used by
/// `AuthorizedTransport` (against each request URL), and by any future middleware against its
/// `baseURL`.
public nonisolated enum BearerScope {
    public static func allows(_ url: URL, apiHost: String) -> Bool {
        url.host()?.lowercased() == apiHost.lowercased()
    }
}
