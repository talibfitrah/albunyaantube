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

    public static func defaultTransport() -> URLSessionTransport {
        URLSessionTransport(configuration: .init(session: URLSession(configuration: defaultSessionConfiguration)))
    }

    /// - Parameter baseURL: host root such as `https://app.fitrahtube.com/`; the spec's servers
    ///   end in `/api`, so it is appended here.
    public static func make(
        baseURL: URL,
        deviceId: DeviceId,
        transport: any ClientTransport = defaultTransport(),
        extraMiddlewares: [any ClientMiddleware] = []
    ) -> Client {
        Client(
            serverURL: baseURL.appending(path: "api"),
            transport: transport,
            middlewares: [DeviceIdMiddleware(deviceId: deviceId)] + extraMiddlewares
        )
    }
}
