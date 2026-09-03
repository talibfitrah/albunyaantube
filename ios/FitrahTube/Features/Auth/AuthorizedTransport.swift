import FitrahAPI
import Foundation
import InnerTubeKit

/// The hand-written clients' half of ruling F12: same host rule, same retry, different transport
/// world. `AuthMiddleware` (`FitrahAPI`) is the generated client's half; the state machine itself is
/// `BearerRetry` and lives in exactly one place, which this ADAPTS — it does not re-implement the
/// 401 loop. Also the ONE place a 403 account-lifecycle envelope becomes an event.
nonisolated struct AuthorizedTransport: HTTPTransport {
    private let base: any HTTPTransport
    private let apiHost: String
    private let tokens: any AuthTokenProviding
    private let onStatusEvent: @Sendable (AccountStatusEvent) -> Void

    init(base: any HTTPTransport, apiHost: String, tokens: any AuthTokenProviding,
         onStatusEvent: @escaping @Sendable (AccountStatusEvent) -> Void) {
        self.base = base
        self.apiHost = apiHost
        self.tokens = tokens
        self.onStatusEvent = onStatusEvent
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let base = base
        let tokens = tokens
        let response = try await BearerRetry.send(
            signed: request,
            // The stricter half of the host rule: `AuthMiddleware` scopes against its `baseURL`
            // because a `ClientMiddleware` only ever sees its own server, but this transport is
            // handed whatever URL the caller built, so the PER-REQUEST URL decides.
            allowed: BearerScope.allows(request.url, apiHost: apiHost),
            token: { await tokens.idToken(forceRefresh: $0) },
            sign: { request, token in
                var signed = request
                signed.headers["Authorization"] = "Bearer \(token)"
                return signed
            },
            isUnauthorizedBearer: { response in
                // RFC 7235 makes the challenge scheme case-insensitive and the header a
                // comma-separated list, so a substring match, not `==` — Android's semantics
                // exactly (`FirebaseAuthInterceptor.kt:185`).
                response.status == 401
                    && response.header("WWW-Authenticate")?.lowercased().contains("bearer") == true
            },
            // `BearerRetry` re-sends the SAME request on retry, which only replays for an in-memory
            // body. Every body reaching this transport is `HTTPRequest.body`, i.e. `Data`, so it
            // replays byte-identically (pinned by `aRetriedRequestCarriesTheIdenticalBody`).
            send: { try await base.send($0) }
        )
        postStatusEvent(for: request, response)
        return response
    }

    /// Cubic R7 P0 + R9 P1 + R-final4 P2 (`AccountStatusInterceptor.kt:74-78`): the envelope check
    /// runs ONLY on this backend's four namespaces. Matching the envelope on ANY 403 would let a
    /// third party (or a proxy) answering the same JSON shape sign the user out of the whole app.
    private static let envelopePaths = ["/api/admin/", "/api/v1/", "/api/account/", "/api/share-metadata/"]
    /// `MAX_PEEK_BYTES` (`AccountStatusInterceptor.kt:137`): a misbehaving server must not be able
    /// to make the client read an unbounded error body. 1 KiB is far larger than the two-field
    /// envelope.
    private static let maxPeekBytes = 1024

    /// The response is returned to the caller REGARDLESS — this observes, it never swallows, so
    /// `AccountClient` still sees the 403 and maps it to `.blocked`/`.deletedAccount`.
    private func postStatusEvent(for request: HTTPRequest, _ response: HTTPResponse) {
        guard response.status == 403 else { return }
        // Task 7 review I2: the path prefix is only half the rule. This transport is shared and
        // handed arbitrary URLs, so a foreign host answering the same envelope on an honoured path
        // could otherwise sign the user out. Same `BearerScope` as the signing decision above —
        // never a second host rule.
        guard BearerScope.allows(request.url, apiHost: apiHost) else { return }
        let path = request.url.path()
        guard Self.envelopePaths.contains(where: path.hasPrefix) else { return }
        let peek = response.body.prefix(Self.maxPeekBytes)
        if ApiErrorEnvelope.hasCode("ACCOUNT_BLOCKED", in: peek) {
            onStatusEvent(.blocked)
        } else if ApiErrorEnvelope.hasCode("ACCOUNT_DELETED", in: peek) {
            onStatusEvent(.deleted)
        }
    }
}

nonisolated extension HTTPResponse {
    /// Case-insensitive header lookup. `URLSessionTransport` copies `HTTPURLResponse.allHeaderFields`
    /// verbatim and HTTP/2 delivers every field name lowercased, so `headers["WWW-Authenticate"]`
    /// silently misses on a real connection.
    func header(_ name: String) -> String? {
        headers.first { $0.key.caseInsensitiveCompare(name) == .orderedSame }?.value
    }
}
