import Foundation
import HTTPTypes
import OpenAPIRuntime

/// Signs the generated client's requests with the Firebase ID token and runs the 401 refresh
/// dance. Installed BESIDE `DeviceIdMiddleware`, never instead of it, and only when
/// `FitrahAPIClient.make` is given a `tokens:` — see the note there for why production passes nil.
///
/// This is a thin adapter: the whole state machine is `BearerRetry` (pinned by `BearerRetryTests`)
/// and the host rule is `BearerScope` (pinned by `BearerScopeTests`). What lives here is only the
/// mapping onto OpenAPI's `intercept` shape.
public struct AuthMiddleware: ClientMiddleware {
    private let apiHost: String
    private let tokens: any AuthTokenProviding

    public init(apiHost: String, tokens: any AuthTokenProviding) {
        self.apiHost = apiHost
        self.tokens = tokens
    }

    public func intercept(
        _ request: HTTPRequest,
        body: HTTPBody?,
        baseURL: URL,
        operationID: String,
        next: @Sendable (HTTPRequest, HTTPBody?, URL) async throws -> (HTTPResponse, HTTPBody?)
    ) async throws -> (HTTPResponse, HTTPBody?) {
        let tokens = tokens
        return try await BearerRetry.send(
            signed: request,
            // Scope is checked against `baseURL`, not the request path: a `ClientMiddleware` only
            // ever sees requests the generated client is sending to its own server, and the URL is
            // assembled downstream. `AuthorizedTransport` checks each request URL instead.
            allowed: BearerScope.allows(baseURL, apiHost: apiHost),
            token: { await tokens.idToken(forceRefresh: $0) },
            sign: { request, token in
                var request = request
                request.headerFields[.authorization] = "Bearer \(token)"
                return request
            },
            isUnauthorizedBearer: { response in
                response.0.status == .unauthorized
                    && response.0.headerFields[.wwwAuthenticate]?.lowercased().contains("bearer") == true
            },
            // The retry re-sends `body`. Every generated operation's body is built from bytes in
            // memory (`.multiple` iteration), so it replays; a streaming upload would not, and the
            // spec has none.
            send: { try await next($0, body, baseURL) }
        )
    }
}
