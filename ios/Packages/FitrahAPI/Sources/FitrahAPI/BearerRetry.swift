/// The 401 dance, ONCE (ruling F12). Both callers are a thin adapter: `AuthMiddleware` passes the
/// OpenAPI `next` closure, `AuthorizedTransport` (Task 7) passes its wrapped `HTTPTransport.send`.
/// Generic over the caller's request/response pair so neither transport world leaks into the other,
/// and so the state machine is testable with no HTTP at all (`BearerRetryTests`).
public nonisolated enum BearerRetry {
    /// `send` is called at most twice: once signed (or unsigned when `token(false)` is nil), and
    /// once more ONLY if the first answered 401 with `WWW-Authenticate: Bearer`. A refresh
    /// returning nil re-sends the SIGNED original so the 401 surfaces honestly
    /// (`FirebaseAuthInterceptor.kt:161-175`) — never unsigned, which would hide the real cause.
    ///
    /// - Parameters:
    ///   - allowed: `BearerScope.allows(...)`. False means this request never carries a token, so
    ///     it is neither signed nor retried — a resend would be byte-identical.
    ///   - request: the UNSIGNED original; `sign` is applied to it for each attempt, so the retry
    ///     carries the refreshed token rather than both tokens.
    ///
    /// A `Req` whose body can only be consumed once cannot be replayed by the second `send`; the
    /// adapters are responsible for only handing over replayable requests.
    public static func send<Req, Res>(
        signed request: Req,
        allowed: Bool,
        token: @Sendable (_ forceRefresh: Bool) async -> String?,
        sign: @Sendable (Req, String) -> Req,
        isUnauthorizedBearer: @Sendable (Res) -> Bool,
        send: @Sendable (Req) async throws -> Res
    ) async rethrows -> Res {
        let attempt: Req
        if allowed, let token = await token(false) {
            attempt = sign(request, token)
        } else {
            attempt = request
        }

        let response = try await send(attempt)
        guard allowed, isUnauthorizedBearer(response) else { return response }

        guard let refreshed = await token(true) else { return try await send(attempt) }
        return try await send(sign(request, refreshed))
    }
}
