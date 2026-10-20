import Foundation

public struct HTTPRequest: Sendable {
    public var method: String
    public var url: URL
    public var headers: [String: String]
    public var body: Data?

    public init(method: String, url: URL, headers: [String: String], body: Data?) {
        self.method = method
        self.url = url
        self.headers = headers
        self.body = body
    }
}

public struct HTTPResponse: Sendable {
    public var status: Int
    public var headers: [String: String]
    public var body: Data

    public init(status: Int, headers: [String: String], body: Data) {
        self.status = status
        self.headers = headers
        self.body = body
    }
}

public protocol HTTPTransport: Sendable {
    func send(_ request: HTTPRequest) async throws -> HTTPResponse
}

/// A request whose body is read only as far as a cap (stage 6 I-C1: the loader's `sidx` GET, where a CDN that ignores
/// `Range` answers with a whole video). Its own protocol, not an `HTTPTransport` requirement, so no conformer can fall
/// back to buffering the whole body by default.
public protocol BoundedHTTPTransport: Sendable {
    /// No body byte is read for a non-2xx or a declared `Content-Length` over `maxBodyBytes`; otherwise reading stops
    /// after `maxBodyBytes + 1` bytes, so an oversize body reads as one byte too long, never as the whole of it.
    func send(_ request: HTTPRequest, maxBodyBytes: Int) async throws -> HTTPResponse
}
