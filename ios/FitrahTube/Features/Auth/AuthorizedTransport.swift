import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization

/// The hand-written clients' half of ruling F12: same host rule, same retry, different transport
/// world. `AuthMiddleware` (`FitrahAPI`) is the generated client's half; the state machine itself is
/// `BearerRetry` and lives in exactly one place, which this ADAPTS — it does not re-implement the
/// 401 loop. Also the ONE place a 403 account-lifecycle envelope becomes an event.
nonisolated struct AuthorizedTransport: HTTPTransport {
    private let base: any HTTPTransport
    private let apiHost: String
    private let tokens: any AuthTokenProviding
    private let onStatusEvent: @Sendable (AccountStatusEvent) -> Void
    /// Why the token source refused a forced refresh, as a lifecycle verdict (Stage 5 / M1).
    /// Default `nil` = "no local verdict", which is every existing caller and every non-Firebase
    /// token source.
    private let refreshRefusal: @Sendable () async -> AuthErrorCode?

    init(base: any HTTPTransport, apiHost: String, tokens: any AuthTokenProviding,
         onStatusEvent: @escaping @Sendable (AccountStatusEvent) -> Void,
         refreshRefusal: @escaping @Sendable () async -> AuthErrorCode? = { nil }) {
        self.base = base
        self.apiHost = apiHost
        self.tokens = tokens
        self.onStatusEvent = onStatusEvent
        self.refreshRefusal = refreshRefusal
    }

    /// Firebase's own verdict on a refused forced refresh, as an account-lifecycle event.
    ///
    /// Stage 5 / M1: this backend answers a terminated account with a BARE 401, not the 403
    /// envelope. `FirebaseAuthFilter` enables `checkRevoked` on `/api/account/` and `/api/admin/`,
    /// and both `softDeleteUser` and `blockUser` revoke the refresh tokens FIRST — so
    /// `verifyIdToken(token, checkRevoked)` throws and the filter's 401 arm returns before its
    /// `ACCOUNT_DELETED` / `ACCOUNT_BLOCKED` 403 arms can run. The device wipe therefore had no
    /// admin-side trigger at all. The backend's 403 carries the precise verdict wherever it is
    /// reachable; THIS is the fallback, and it is trustworthy because the verdict comes from
    /// Firebase, not from a response body a third party could shape.
    ///
    /// Anything else — a stall, a rejected password, an unmapped code — is NOT terminal: a 401 the
    /// client cannot explain must leave the session alone.
    nonisolated static func terminalEvent(for refusal: AuthErrorCode?) -> AccountStatusEvent? {
        switch refusal {
        case .userNotFound: .deleted     // the record is gone: wipe (ruling C13)
        case .userDisabled: .blocked     // reversible: drop the session, keep the library
        default: nil
        }
    }

    func send(_ request: HTTPRequest) async throws -> HTTPResponse {
        let base = base
        let tokens = tokens
        let refreshRefusal = refreshRefusal
        // Written at most once, by the `token` closure below, when a FORCED refresh came back nil.
        // A box rather than a second `refreshRefusal()` call after the fact: the refusal belongs to
        // the refresh `BearerRetry` already performed, and asking twice would mint two round trips
        // for one 401.
        let refused = Mutex<AccountStatusEvent?>(nil)
        let response = try await BearerRetry.send(
            signed: request,
            // The stricter half of the host rule: `AuthMiddleware` scopes against its `baseURL`
            // because a `ClientMiddleware` only ever sees its own server, but this transport is
            // handed whatever URL the caller built, so the PER-REQUEST URL decides.
            allowed: BearerScope.allows(request.url, apiHost: apiHost),
            token: { forceRefresh in
                if let token = await tokens.idToken(forceRefresh: forceRefresh) { return token }
                guard forceRefresh else { return nil }
                let event = Self.terminalEvent(for: await refreshRefusal())
                refused.withLock { $0 = event }
                return nil
            },
            sign: { request, token in
                var signed = request
                signed.headers["Authorization"] = "Bearer \(token)"
                return signed
            },
            isUnauthorizedBearer: { response in
                // Stage 5 / M2: NO `WWW-Authenticate` condition. `grep -rn "WWW-Authenticate"
                // backend/src/main/` returns zero hits — this backend's 401 leg writes only
                // `{"error": "Invalid or expired token"}` — so gating on the challenge header made
                // the entire refresh state machine dead in production. On an `allowed` host a 401 IS
                // a bearer rejection by construction: `BearerScope` has already decided this URL is
                // the configured API host, and `BearerRetry` only reaches this predicate when the
                // request was in scope to be signed.
                response.status == 401
            },
            // `BearerRetry` re-sends the SAME request on retry, which only replays for an in-memory
            // body. Every body reaching this transport is `HTTPRequest.body`, i.e. `Data`, so it
            // replays byte-identically (pinned by `aRetriedRequestCarriesTheIdenticalBody`).
            send: { try await base.send($0) }
        )
        postStatusEvent(for: request, response)
        // The forced refresh was refused with a terminal verdict, so the 401 above is not a stale
        // token — the account is gone or blocked. Posted exactly once per request, and only for a
        // request that was in bearer scope to begin with.
        if let event = refused.withLock({ $0 }) { onStatusEvent(event) }
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
