import Foundation
import HTTPTypes
import OpenAPIRuntime

// Test-only double; each test awaits send(_:) before reading the recorded request, so no synchronization is needed.
// nonisolated: also compiled into FitrahTubeTests, whose default isolation is MainActor.
nonisolated final class RecordingTransport: ClientTransport, @unchecked Sendable {
    private(set) var lastRequest: HTTPRequest?
    private(set) var lastBaseURL: URL?
    var status: HTTPResponse.Status = .ok
    var responseBody: Data = Data("[]".utf8)
    var extraHeaders: HTTPFields = [:]

    init() {}

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
        lastRequest = request
        lastBaseURL = baseURL
        var response = HTTPResponse(status: status)
        response.headerFields[.contentType] = "application/json"
        response.headerFields.append(contentsOf: extraHeaders)
        return (response, HTTPBody(responseBody))
    }
}
