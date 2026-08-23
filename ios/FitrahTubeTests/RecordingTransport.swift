import Foundation
import HTTPTypes
import OpenAPIRuntime

// Test-only double; each test awaits send(_:) before reading the recorded request, so no synchronization is needed.
nonisolated final class RecordingTransport: ClientTransport, @unchecked Sendable {
    private(set) var lastRequest: HTTPRequest?
    var responseBody: Data

    init(responseBody: Data) { self.responseBody = responseBody }

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
        lastRequest = request
        var response = HTTPResponse(status: .ok)
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(responseBody))
    }
}
