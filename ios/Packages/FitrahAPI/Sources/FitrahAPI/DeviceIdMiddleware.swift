import Foundation
import HTTPTypes
import OpenAPIRuntime

/// Adds `X-Device-Id` to every request. The backend rejects requests without it.
public struct DeviceIdMiddleware: ClientMiddleware {
    static let headerName = HTTPField.Name("X-Device-Id")!
    private let deviceId: DeviceId

    public init(deviceId: DeviceId) { self.deviceId = deviceId }

    public func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        var request = request
        request.headerFields[Self.headerName] = deviceId.value
        return try await next(request, body, baseURL)
    }
}
