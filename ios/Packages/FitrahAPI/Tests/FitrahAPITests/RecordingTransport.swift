import Foundation
import HTTPTypes
import OpenAPIRuntime

// Test-only double; each test awaits send(_:) before reading the recorded request, so no synchronization is needed.
// nonisolated: also compiled into FitrahTubeTests, whose default isolation is MainActor.
nonisolated final class RecordingTransport: ClientTransport, @unchecked Sendable {
    private(set) var lastRequest: HTTPRequest?
    private(set) var lastBaseURL: URL?
    var status: HTTPResponse.Status = .ok
    /// Answered in order, one per request, before `status` takes over.
    var statuses: [HTTPResponse.Status] = []
    private(set) var requestCount = 0
    var responseBody: Data = Data("[]".utf8)
    var extraHeaders: HTTPFields = [:]

    init() {}

    func send(_ request: HTTPRequest, body: HTTPBody?, baseURL: URL, operationID: String) async throws -> (HTTPResponse, HTTPBody?) {
        lastRequest = request
        lastBaseURL = baseURL
        requestCount += 1
        var response = HTTPResponse(status: statuses.isEmpty ? status : statuses.removeFirst())
        response.headerFields[.contentType] = "application/json"
        response.headerFields.append(contentsOf: extraHeaders)
        return (response, HTTPBody(responseBody))
    }
}
