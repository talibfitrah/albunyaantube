import Testing
@testable import FitrahAPI

/// The 401 dance lives in exactly ONE place (ruling F12), so this is its whole specification.
/// Task 7's `AuthorizedTransport` is a thin adapter over it and pins only its
/// own two facts each — neither re-tests the state machine.
///
/// The request/response pair is deliberately trivial and transport-agnostic, per the brief: a
/// request is an `Int` (`0` = unsigned, otherwise the token it carries) and a response is a `Bool`
/// (`true` = the server answered 401). Nothing about HTTP is needed to pin the machine, which is
/// the point of it being generic.
@Suite(.perTest)
struct BearerRetryTests {

    /// Records what the state machine did. `@unchecked Sendable` for the reason `RecordingTransport`
    /// is: one task, sequential awaits, every read after the single `await` completes.
    private final class Recorder: @unchecked Sendable {
        /// The `forceRefresh` flag of each `token(_:)` call, in order.
        var tokenCalls: [Bool] = []
        var signCalls = 0
        /// Every request handed to `send`, in order.
        var sent: [Int] = []
    }

    /// `tokens` and `responses` are consumed one per call, in order; running off the end yields
    /// `nil` / "not a 401". `identities` is consumed the same way and defaults to ONE account
    /// throughout, so only the cross-account test has to say anything about it.
    ///
    /// Stage 8 / S4: there used to be a `challenge: Bool` modelling "the caller's predicate said
    /// this 401 is not a bearer rejection". No adapter in the tree can produce that any more —
    /// `AuthorizedTransport.isUnauthorizedBearer` is the status alone — and what it pinned
    /// (predicate false -> no retry) is test (1)'s `responses: [false]`.
    private func run(
        allowed: Bool = true,
        tokens: [String?] = ["1"],
        identities: [String?] = [],
        responses: [Bool] = [false],
        into recorder: Recorder
    ) async -> Bool {
        await BearerRetry.send(
            signed: 0,
            allowed: allowed,
            token: { forceRefresh in
                recorder.tokenCalls.append(forceRefresh)
                let index = recorder.tokenCalls.count - 1
                guard index < tokens.count, let value = tokens[index] else { return nil }
                let identity = index < identities.count ? identities[index] : "uid-A"
                return BearerToken(value: value, identity: identity)
            },
            sign: { _, token in
                recorder.signCalls += 1
                return Int(token)!
            },
            isUnauthorizedBearer: { $0 },
            send: { request in
                recorder.sent.append(request)
                let index = recorder.sent.count - 1
                return index < responses.count ? responses[index] : false
            }
        )
    }

    /// (1) allowed + a token → signed once, sent once.
    @Test func anAllowedHostWithATokenSignsOnceAndSendsOnce() async {
        let recorder = Recorder()
        _ = await run(into: recorder)
        #expect(recorder.tokenCalls == [false])
        #expect(recorder.signCalls == 1)
        #expect(recorder.sent == [1])
    }

    /// (2) `allowed == false` → `sign` is never called and the request goes out untouched. This is
    /// `BearerScope` saying no; the token is not even fetched.
    ///
    /// Task 6 review I1: `responses: [true]` is what makes this test reach the retry branch at all.
    /// With the helper's default (`[false]`) the guard's `allowed` term was unreachable and deleting
    /// it left every test green — the one behaviour Task 6 flagged as a judgement call had no
    /// assertion behind it. `sent == [0]` now pins BOTH halves: unsigned, and not retried.
    @Test func aDisallowedHostNeverSignsAndNeverAsksForAToken() async {
        let recorder = Recorder()
        _ = await run(allowed: false, responses: [true], into: recorder)
        #expect(recorder.tokenCalls.isEmpty)
        #expect(recorder.signCalls == 0)
        #expect(recorder.sent == [0])
    }

    /// (3) `token(false) == nil` → unsigned, one send, no failure. Android's 3 s token budget can
    /// expire (`FirebaseAuthInterceptor.kt:95-103`); the request still goes.
    @Test func noTokenSendsUnsignedOnceWithoutFailing() async {
        let recorder = Recorder()
        let response = await run(tokens: [nil], into: recorder)
        #expect(recorder.tokenCalls == [false])
        #expect(recorder.signCalls == 0)
        #expect(recorder.sent == [0])
        #expect(response == false)
    }

    /// (4) a 401 the caller classified as a bearer rejection → exactly one forced refresh and
    /// exactly two sends, the second carrying the refreshed token. (There is no (5): Stage 8 / S4
    /// retired it with the `challenge` knob — see `run`.)
    @Test func aBearerChallengeRefreshesOnceAndResendsWithTheNewToken() async {
        let recorder = Recorder()
        let response = await run(tokens: ["1", "2"], responses: [true, false], into: recorder)
        #expect(recorder.tokenCalls == [false, true])
        #expect(recorder.sent == [1, 2])
        #expect(response == false)
    }

    /// (6) a second 401 after the retry → returned as-is, never a third send.
    @Test func aSecondChallengeAfterTheRetryIsReturnedAsIs() async {
        let recorder = Recorder()
        let response = await run(tokens: ["1", "2"], responses: [true, true], into: recorder)
        #expect(recorder.tokenCalls == [false, true])
        #expect(recorder.sent == [1, 2])
        #expect(response == true)
    }

    /// (7) the refresh returning nil → the **signed** original is re-sent, never an unsigned one,
    /// so the 401 surfaces honestly instead of looking like an anonymous request
    /// (`FirebaseAuthInterceptor.kt:161-175`).
    @Test func aFailedRefreshResendsTheSignedOriginalNotAnUnsignedOne() async {
        let recorder = Recorder()
        let response = await run(tokens: ["1", nil], responses: [true, true], into: recorder)
        #expect(recorder.tokenCalls == [false, true])
        #expect(recorder.signCalls == 1)
        #expect(recorder.sent == [1, 1])
        #expect(response == true)
    }

    /// (8) Task 6 review I2 — the cross-account leak guard, fixed HERE so both adapters inherit it.
    /// A sign-out plus sign-in as a different account landing between the two attempts must NOT
    /// replay account A's in-flight request signed with account B's bearer
    /// (`FirebaseAuthInterceptor.kt:131-143`, which Android calls a P0 identity leak). The original
    /// 401 is returned instead, and the caller re-drives the request under the new account.
    @Test func aRefreshUnderADifferentAccountDropsTheRetry() async {
        let recorder = Recorder()
        let response = await run(tokens: ["1", "2"], identities: ["uid-A", "uid-B"],
                                 responses: [true, false], into: recorder)
        #expect(recorder.tokenCalls == [false, true])
        #expect(recorder.sent == [1], "account A's request must not be replayed with account B's token")
        #expect(response == true, "the original 401 surfaces instead")
    }

    /// (9) Task 7 review I1 — the cross-account guard must NOT also kill the unsigned-first retry.
    /// When `token(false)` returned nil (a transient `getIDToken` failure — the case the file's doc
    /// comment at `:6-9` promises) attempt 1 went out unsigned, so there is no account A to leak
    /// FROM and nothing for the guard to protect. Comparing `nil` against the refreshed identity
    /// dropped the single most valuable retry in the machine.
    @Test func anUnsignedFirstAttemptStillRetriesWithTheRefreshedToken() async {
        let recorder = Recorder()
        let response = await run(tokens: [nil, "2"], responses: [true, false], into: recorder)
        #expect(recorder.tokenCalls == [false, true])
        #expect(recorder.sent == [0, 2], "the unsigned first attempt must be retried, signed")
        #expect(response == false)
    }
}
