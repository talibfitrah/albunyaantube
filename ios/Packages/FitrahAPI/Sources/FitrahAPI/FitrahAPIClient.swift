import Foundation
import OpenAPIRuntime
import OpenAPIURLSession

public enum FitrahAPIClient {
    /// Default session config per spec §8: 20 s idle (`timeoutIntervalForRequest`, = Android's
    /// 20 s read timeout) and 120 s for the whole transfer (`timeoutIntervalForResource`); no
    /// caching, no waiting for connectivity (fail fast; caller decides retry policy).
    public static var defaultSessionConfiguration: URLSessionConfiguration {
        let config = URLSessionConfiguration.default
        config.timeoutIntervalForRequest = 20
        config.timeoutIntervalForResource = 120
        config.urlCache = nil
        config.waitsForConnectivity = false
        return config
    }

    /// One shared session for the app's lifetime rather than a fresh `URLSession` (its own
    /// connection pool) on every `defaultTransport()` call; `defaultSessionConfiguration` stays
    /// exposed above for direct testing.
    private static let session = URLSession(configuration: defaultSessionConfiguration)

    public static func defaultTransport() -> URLSessionTransport {
        URLSessionTransport(configuration: .init(session: session))
    }

    /// - Parameter baseURL: host root such as `https://app.fitrahtube.com/`; the spec's servers
    ///   end in `/api`, so it is appended here.
    /// - Parameter tokens: nil installs no `AuthMiddleware`.
    ///
    /// **Production passes nil, deliberately.** The generated client has one call site
    /// (`AppContainer.swift` -> `LiveCatalogClient`) and serves only public `/api/v1/*` paths;
    /// every Phase 4 endpoint is a hand-written client over `AuthorizedTransport`. Passing
    /// `tokens:` here would also mean building `auth` before `AppContainer.init` — `make` runs
    /// inside `live()`, before the container exists. Ruling F12 requires the middleware to exist
    /// beside `DeviceIdMiddleware`; it is wired the day a signed-in endpoint is generated
    /// (spec §8's `POST /api/share-metadata/*`, which iOS has not built). See CF-A-17.
    public static func make(
        baseURL: URL,
        deviceId: DeviceId,
        tokens: (any AuthTokenProviding)? = nil,
        transport: any ClientTransport = defaultTransport()
    ) -> Client {
        // DeviceIdMiddleware stays FIRST: it is outermost, so `X-Device-Id` is already on the
        // request AuthMiddleware signs and re-sends.
        var middlewares: [any ClientMiddleware] = [DeviceIdMiddleware(deviceId: deviceId)]
        if let tokens {
            middlewares.append(AuthMiddleware(apiHost: baseURL.host() ?? "", tokens: tokens))
        }
        return Client(
            serverURL: baseURL.appending(path: "api"),
            transport: transport,
            middlewares: middlewares
        )
    }
}
