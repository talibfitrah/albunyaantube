import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Spec §4.4 / §6.2: the app's solver is a pure HTTP mapper over the signed transport. `StreamResolver.askSolver` reads
/// an UNTYPED error as `.unavailable(nil)` (the full 120 s window), so every refusal is typed here, where `Retry-After`
/// is still readable. `ScriptedTransport` is the one canned transport (its own doc comment).
@Suite struct BackendChallengeSolverTests {
    private static let base = URL(string: "https://app.fitrahtube.com/")!

    private func scripted(_ responses: [HTTPResponse]) -> (BackendChallengeSolver, ScriptedTransport) {
        let transport = ScriptedTransport(responses)
        return (BackendChallengeSolver(transport: transport, baseURL: Self.base), transport)
    }

    /// The real signed transport over a canned base, so the account-envelope leg is exercised as it ships.
    private func signedSolver(_ responses: [HTTPResponse]) -> (BackendChallengeSolver, ScriptedTransport, Events) {
        let base = ScriptedTransport(responses)
        let events = Events()
        let authorized = AuthorizedTransport(base: base, apiHost: "app.fitrahtube.com", tokens: FixedToken(),
                                             onStatusEvent: { event, _ in events.posted.append(event) })
        return (BackendChallengeSolver(transport: authorized, baseURL: Self.base), base, events)
    }

    /// `AuthorizedTransportTests.Events`' reason: one task, sequential awaits.
    private nonisolated final class Events: @unchecked Sendable {
        var posted: [AccountStatusEvent] = []
    }

    private struct FixedToken: AuthTokenProviding {
        func idToken(forceRefresh: Bool) async -> BearerToken? { BearerToken(value: "tok-abc", identity: "uid-A") }
    }

    private func solve(_ solver: BackendChallengeSolver) async throws -> SolvedChallenges {
        try await solver.solve(playerId: "0229cd64", n: ["abcdefghij"], s: [])
    }

    @Test func theSolveBodyIsExactlyPlayerIdNAndS() async throws {   // R21
        let (solver, transport) = scripted([.json(200, #"{"n":{"abcdefghij":"xyz"},"s":{}}"#)])
        let answers = try await solve(solver)
        #expect(answers == SolvedChallenges(n: ["abcdefghij": "xyz"], s: [:]))
        let request = try #require(transport.sent.first)
        #expect(request.method == "POST")
        #expect(request.url.absoluteString == "https://app.fitrahtube.com/api/playback/solve")
        let body = try #require(request.body)
        let json = try #require(JSONSerialization.jsonObject(with: body) as? [String: Any])
        #expect(Set(json.keys) == ["playerId", "n", "s"])
    }

    @Test func thePlayerLookupReadsPlayerIdAndSts() async throws {
        let (solver, transport) = scripted([.json(200, #"{"playerId":"0229cd64","sts":20739}"#)])
        #expect(try await solver.playerInfo() == SolverPlayer(id: "0229cd64", signatureTimestamp: 20739))
        #expect(transport.sent.first?.url.absoluteString == "https://app.fitrahtube.com/api/playback/player")
    }

    /// Stage 6 I-S2 (R21: never the device locale). CFNetwork adds the device's languages as `Accept-Language` to a
    /// request that sets none, so both solver calls set a fixed `en`.
    @Test func bothCallsSendAFixedAcceptLanguage() async throws {
        let (solver, transport) = scripted([.json(200, #"{"playerId":"0229cd64","sts":20739}"#),
                                            .json(200, #"{"n":{},"s":{}}"#)])
        _ = try await solver.playerInfo()
        _ = try await solve(solver)
        #expect(transport.sent.map { $0.headers["Accept-Language"] } == ["en", "en"])
    }

    @Test func retryAfterIsDeltaSecondsOnly() {   // M16
        #expect(BackendChallengeSolver.retryAfter(["Retry-After": "120"]) == 120)
        #expect(BackendChallengeSolver.retryAfter(["retry-after": " 5 "]) == 5)
        #expect(BackendChallengeSolver.retryAfter(["Retry-After": "Fri, 17 Oct 2026 10:00:00 GMT"]) == nil)
        #expect(BackendChallengeSolver.retryAfter(["Retry-After": "-5"]) == nil)
        #expect(BackendChallengeSolver.retryAfter([:]) == nil)
    }

    @Test func refusalsMapToTheResolversTwoCases() async {
        await #expect(throws: ChallengeSolverError.unknownPlayer) {
            try await solve(scripted([.json(409, "{}")]).0)
        }
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: 3600)) {
            try await solve(scripted([.json(429, "{}", headers: ["Retry-After": "3600"])]).0)
        }
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: nil)) {
            try await scripted([.json(200, "not json")]).0.playerInfo()
        }
    }

    /// Task 7 hand-off 3: the solve limiter's 429 carries delta-seconds; a date or a negative value opens the default
    /// window rather than one the device made up.
    @Test(arguments: [("30", 30), ("Fri, 17 Oct 2026 10:00:00 GMT", nil), ("-5", nil)] as [(String, Int?)])
    func a429ReadsRetryAfterAsDeltaSecondsOnly(_ header: String, _ seconds: Int?) async {
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: seconds)) {
            try await solve(scripted([.json(429, "{}", headers: ["Retry-After": header])]).0)
        }
    }

    /// Task 7 hand-off 2: the filter's 503 (a failed account-status check) sends no `Retry-After`.
    @Test func theFilters503IsUnavailableWithNoRetryAfter() async {
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: nil)) {
            try await solve(scripted([.json(503, "{}")]).0)
        }
    }

    /// Task 7 hand-off 1: only an `ACCOUNT_*` 403 is terminal, and `AuthorizedTransport` posts that one. A bare 403
    /// (the backend's no-token answer) is the backend's unavailability and must not touch the account.
    @Test func aBare403IsUnavailableAndPostsNoAccountEvent() async {
        let (solver, _, events) = signedSolver([.json(403, "")])
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: nil)) { try await solve(solver) }
        #expect(events.posted.isEmpty)
    }

    /// Task 7 hand-off 3: a 401 that survives the transport's one forced refresh is not a verdict here.
    @Test func a401AfterTheRefreshIsUnavailable() async {
        let (solver, base, events) = signedSolver([.json(401, "{}"), .json(401, "{}")])
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: nil)) { try await solve(solver) }
        #expect(base.sent.count == 2, "the transport's refresh-and-retry ran first")
        #expect(events.posted.isEmpty)
    }

    @Test func aTransportErrorIsUnavailable() async {
        await #expect(throws: ChallengeSolverError.unavailable(retryAfterSeconds: nil)) {
            try await solve(scripted([.failing(URLError(.notConnectedToInternet))]).0)
        }
    }

    /// Task 7 hand-off 3: a cancellation is the player moving on, never the backend's failure (no skip window).
    @Test func aCancellationStaysACancellation() async {
        await #expect(throws: CancellationError.self) { try await solve(scripted([.failing(CancellationError())]).0) }
        await #expect(throws: CancellationError.self) { try await solve(scripted([.failing(URLError(.cancelled))]).0) }
    }
}
