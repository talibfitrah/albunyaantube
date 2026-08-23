import Foundation
import HTTPTypes
import OpenAPIRuntime

/// Test transport: records the outgoing request and answers with a fixed response.
/// `nonisolated`: `ClientTransport.send` is a nonisolated requirement.
nonisolated final class RecordingTransport: ClientTransport, @unchecked Sendable {
    private(set) var lastRequest: HTTPRequest?
    private(set) var lastBaseURL: URL?
    var status: HTTPResponse.Status = .ok
    var responseBody: Data = Data("[]".utf8)

    init() {}

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
        lastRequest = request
        lastBaseURL = baseURL
        var response = HTTPResponse(status: status)
        response.headerFields[.contentType] = "application/json"
        return (response, HTTPBody(responseBody))
    }
}
