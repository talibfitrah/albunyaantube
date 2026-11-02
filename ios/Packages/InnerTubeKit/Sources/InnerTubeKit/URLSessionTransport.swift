import Foundation

/// `HTTPTransport` backed by a dedicated `URLSession` (`ios-app-plan.md` §6.3): ephemeral
/// configuration, cookies fully disabled (a cookie jar would replay a stale
/// `VISITOR_INFO1_LIVE`/`YSC` next to a rotated `visitorData`), 15 s per-request timeout, no
/// waiting for connectivity. Per-client headers already ride on `HTTPRequest.headers`
/// (`InnerTubeContext.headers(context:visitorData:)`), so nothing further is fixed at the
/// session level. `InnerTube` (the composition root) creates one instance per pipeline — its own
/// `URLSession`, not a shared one — for resolver, browse, remote config, and atom feed calls.
public struct URLSessionTransport: HTTPTransport, BoundedHTTPTransport {
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
        let (data, response) = try await session.data(for: Self.urlRequest(request))
        let http = try Self.http(response)
        return HTTPResponse(status: http.statusCode, headers: Self.headers(http), body: data)
    }

    /// `data(for:)` returns only once the whole body is buffered, so a size check on it runs after the allocation it was
    /// meant to prevent. `bytes(for:)` hands back the head first (the `RemoteImage` pattern, gate V7); the task is
    /// cancelled on the way out, so what was not read is never downloaded.
    public func send(_ request: HTTPRequest, maxBodyBytes: Int) async throws -> HTTPResponse {
        let (bytes, response) = try await session.bytes(for: Self.urlRequest(request))
        defer { bytes.task.cancel() }
        let http = try Self.http(response)
        let body = try await Self.boundedBody(bytes, status: http.statusCode, declaredLength: http.expectedContentLength,
                                              maxBytes: maxBodyBytes)
        return HTTPResponse(status: http.statusCode, headers: Self.headers(http), body: body)
    }

    /// The bounded read's rule (`BoundedHTTPTransport`), over any byte sequence so a test can count what was pulled.
    /// `declaredLength` -1 is an unknown (chunked) length: the loop's cap covers it, and a length that understated the body.
    static func boundedBody<Bytes: AsyncSequence>(_ bytes: Bytes, status: Int, declaredLength: Int64,
                                                  maxBytes: Int) async throws -> Data where Bytes.Element == UInt8 {
        guard (200..<300).contains(status), declaredLength <= Int64(maxBytes) else { return Data() }
        var data = Data()
        for try await byte in bytes {
            data.append(byte)
            if data.count > maxBytes { break }
        }
        return data
    }

    private static func urlRequest(_ request: HTTPRequest) -> URLRequest {
        var urlRequest = URLRequest(url: request.url)
        urlRequest.httpMethod = request.method
        urlRequest.allHTTPHeaderFields = request.headers
        urlRequest.httpBody = request.body
        return urlRequest
    }

    private static func http(_ response: URLResponse) throws -> HTTPURLResponse {
        guard let http = response as? HTTPURLResponse else { throw ExtractionError.transport("non-HTTP response") }
        return http
    }

    private static func headers(_ response: HTTPURLResponse) -> [String: String] {
        var headers: [String: String] = [:]
        for (key, value) in response.allHeaderFields {
            headers[String(describing: key)] = String(describing: value)
        }
        return headers
    }
}
