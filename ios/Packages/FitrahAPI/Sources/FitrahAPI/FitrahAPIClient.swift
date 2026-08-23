import Foundation
import OpenAPIRuntime
import OpenAPIURLSession

public enum FitrahAPIClient {
    /// - Parameter baseURL: host root such as `https://app.fitrahtube.com/`; the spec's servers
    ///   end in `/api`, so it is appended here.
    public static func make(
        baseURL: URL,
        deviceId: DeviceId,
        transport: any ClientTransport = URLSessionTransport(),
        extraMiddlewares: [any ClientMiddleware] = []
    ) -> Client {
        Client(
            serverURL: baseURL.appending(path: "api"),
            transport: transport,
            middlewares: [DeviceIdMiddleware(deviceId: deviceId)] + extraMiddlewares
        )
    }
}
