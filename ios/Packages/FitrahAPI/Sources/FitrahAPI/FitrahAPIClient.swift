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
    ///
    /// **The generated client sends no Bearer, and nothing needs it to.** Its one call site
    /// (`AppContainer.swift` -> `LiveCatalogClient`) serves only public `/api/v1/*` paths; every
    /// Phase 4 endpoint is a hand-written client over `AuthorizedTransport`. Stage 1 / B2: the
    /// `tokens:` parameter and the signing middleware it installed had no production caller in any
    /// build and were removed with their tests — the day a signed-in generated endpoint exists
    /// (spec §8's `POST /api/share-metadata/*`, which iOS has not built), the adapter comes back
    /// with its first consumer. `BearerRetry`, `BearerScope` and `AuthTokenProviding` STAY: they
    /// are what `AuthorizedTransport` runs, and ruling F12's "one retry state machine" is theirs.
    public static func make(
        baseURL: URL,
        deviceId: DeviceId,
        transport: any ClientTransport = defaultTransport()
    ) -> Client {
        Client(
            serverURL: baseURL.appending(path: "api"),
            transport: transport,
            middlewares: [DeviceIdMiddleware(deviceId: deviceId)]
        )
    }
}
