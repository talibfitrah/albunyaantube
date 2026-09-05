/// The 401 dance, ONCE (ruling F12). Its caller is a thin adapter: `AuthorizedTransport` (Task 7)
/// passes its wrapped `HTTPTransport.send`. Generic over the caller's request/response pair so no
/// transport world leaks into it, and so the state machine is testable with no HTTP at all
/// (`BearerRetryTests`) — which is also what keeps it ready for a second adapter (Stage 1 / B2
/// removed the generated client's, which had no production caller).
public nonisolated enum BearerRetry {
    /// `send` is called at most twice: once signed (or unsigned when `token(false)` is nil), and
    /// once more ONLY if the first answer was a 401 the CALLER classified as a bearer rejection —
    /// `AuthorizedTransport.isUnauthorizedBearer` is the status alone (Stage 5 / M2: the backend
    /// sends no `WWW-Authenticate` header, and on an allowed host a 401 is a bearer rejection by
    /// construction). A refresh
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
        token: @Sendable (_ forceRefresh: Bool) async -> BearerToken?,
        sign: @Sendable (Req, String) -> Req,
        isUnauthorizedBearer: @Sendable (Res) -> Bool,
        send: @Sendable (Req) async throws -> Res
    ) async rethrows -> Res {
        let first = allowed ? await token(false) : nil
        let attempt = first.map { sign(request, $0.value) } ?? request

        let response = try await send(attempt)
        guard allowed, isUnauthorizedBearer(response) else { return response }

        guard let refreshed = await token(true) else { return try await send(attempt) }
        // Task 6 review I2 — the cross-account leak guard, here so BOTH adapters inherit it. If the
        // signed-in account changed between the two attempts (sign-out + sign-in as somebody else
        // while this request was in flight), the refreshed bearer belongs to a DIFFERENT user and
        // replaying account A's request with it would leak across accounts
        // (`FirebaseAuthInterceptor.kt:131-143`). Surface the original 401 instead and let the
        // caller re-drive the request under the new account. Task 7 review I1: only when attempt 1
        // WAS signed — an unsigned first attempt (`token(false)` nil) has no account to leak from,
        // and comparing nil against the refreshed identity killed that retry outright.
        guard first == nil || refreshed.identity == first?.identity else { return response }
        return try await send(sign(request, refreshed.value))
    }
}
