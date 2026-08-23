import Foundation

/// `HTTPTransport` backed by a dedicated `URLSession` (`ios-app-plan.md` §6.3): ephemeral
/// configuration, cookies fully disabled (a cookie jar would replay a stale
/// `VISITOR_INFO1_LIVE`/`YSC` next to a rotated `visitorData`), 15 s per-request timeout, no
/// waiting for connectivity. Per-client headers already ride on `HTTPRequest.headers`
/// (`InnerTubeContext.headers(context:visitorData:)`), so nothing further is fixed at the
/// session level. `InnerTube` (the composition root) creates one instance per pipeline — its own
/// `URLSession`, not a shared one — for resolver, browse, remote config, and atom feed calls.
public struct URLSessionTransport: HTTPTransport {
    private let session: URLSession

    public init() {
        let configuration = URLSessionConfiguration.ephemeral
        configuration.httpCookieAcceptPolicy = .never
        configuration.httpShouldSetCookies = false
        configuration.timeoutIntervalForRequest = 15
        configuration.waitsForConnectivity = false
        session = URLSession(configuration: configuration)
    }

    public func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        urlRequest.allHTTPHeaderFields = request.headers
        urlRequest.httpBody = request.body

        let (data, response) = try await session.data(for: urlRequest)
        guard let httpResponse = response as? HTTPURLResponse else {
            throw ExtractionError.transport("non-HTTP response")
        }

        var headers: [String: String] = [:]
        for (key, value) in httpResponse.allHeaderFields {
            headers[String(describing: key)] = String(describing: value)
        }
        return HTTPResponse(status: httpResponse.statusCode, headers: headers, body: data)
    }
}
