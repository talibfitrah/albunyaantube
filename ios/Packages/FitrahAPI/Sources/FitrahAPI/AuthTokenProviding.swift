import Foundation

/// The app's ONE token source, seen from the transport side (ruling F12). `AppContainer.auth`
/// conforms to this by declaration — `AuthClient` refines it — so `AuthMiddleware` (Task 6) and
/// `AuthorizedTransport` (Task 7) take the same object with no adapter between them.
///
/// It lives in the package, not the app, because both consumers do: the generated `Client`'s
/// middleware chain and the hand-written `HTTPTransport` are both here.
public protocol AuthTokenProviding: Sendable {
    /// nil = send unsigned and let the backend's 401 drive the refresh
    /// (`FirebaseAuthInterceptor.kt:95-103`, a 3 s budget on the fetch).
    func idToken(forceRefresh: Bool) async -> String?
}

/// Spec §8: the Bearer never leaves the configured API host. Scope is per-HOST — port and scheme
/// are deliberately ignored (`FirebaseAuthInterceptor.kt:54-59`; Debug points at
/// `http://localhost:8080/` and those requests must still be signed). ONE copy, used by
/// `AuthMiddleware` (against its `baseURL`) and by `AuthorizedTransport` (against each request URL).
public nonisolated enum BearerScope {
    public static func allows(_ url: URL, apiHost: String) -> Bool {
        url.host()?.lowercased() == apiHost.lowercased()
    }
}
