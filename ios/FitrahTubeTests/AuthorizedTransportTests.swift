import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Two ADAPTER facts (the Bearer's host rule and header survival) plus this file's OWN leg: the 403
/// account-lifecycle envelope. The 401 state machine itself is pinned once, in the package's
/// `BearerRetryTests`, and is deliberately not re-tested here (ruling F12 — the dance exists once).
@Suite(.perTest)
struct AuthorizedTransportTests {

    private static let apiHost = "api.fitrah.test"
    private static let apiBase = URL(string: "https://api.fitrah.test/")!

    /// Collects what the transport posted, off whatever isolation `send` happened to run on.
    /// `@unchecked Sendable` for the reason `RecordingTransport` is: one task, sequential awaits.
    private nonisolated final class Events: @unchecked Sendable {
        var posted: [AccountStatusEvent] = []
    }

    private func transport(
        _ responses: [HTTPResponse],
        token: BearerToken? = BearerToken(value: "tok-abc", identity: "uid-A"),
        into events: Events = Events()
    ) -> (AuthorizedTransport, ScriptedTransport, Events) {
        let base = ScriptedTransport(responses)
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: ScriptedTokens(token: token),
            onStatusEvent: { events.posted.append($0) })
        return (authorized, base, events)
    }

    private struct ScriptedTokens: AuthTokenProviding {
        let token: BearerToken?
        func idToken(forceRefresh: Bool) async -> BearerToken? { token }
    }

    private func request(_ path: String, host: String = apiHost, method: String = "GET",
                         headers: [String: String] = [:], body: Data? = nil) -> HTTPRequest {
        HTTPRequest(method: method, url: URL(string: "https://\(host)\(path)")!,
                    headers: headers, body: body)
    }

    // MARK: - The two adapter facts

    /// Fact 1: the Bearer reaches the configured API host and NOWHERE else. `BearerScope` scopes
    /// against its `baseURL`; this is the stricter half — the per-REQUEST URL decides, which is what
    /// a shared `HTTPTransport` needs (nothing upstream guarantees the caller stayed on one host).
    @Test func theBearerReachesTheApiHostAndNeverYouTube() async throws {
        let (authorized, base, _) = transport([.json(200, "{}"), .json(200, "{}")])
        _ = try await authorized.send(request("/api/account/me"))
        #expect(base.sent.first?.headers["Authorization"] == "Bearer tok-abc")

        _ = try await authorized.send(request("/watch?v=xc7keR2piUM", host: "www.youtube.com"))
        #expect(base.sent.last?.headers["Authorization"] == nil,
                "the Bearer must never leave the configured API host")
    }

    /// Fact 2: signing REPLACES nothing. `X-Device-Id` (and every other caller header) survives onto
    /// the signed request — `AccountClient` depends on it reaching the backend.
    @Test func theCallersHeadersSurviveSigning() async throws {
        let (authorized, base, _) = transport([.json(200, "{}")])
        _ = try await authorized.send(request("/api/account/me", headers: ["X-Device-Id": "dev-123"]))
        #expect(base.sent.first?.headers["X-Device-Id"] == "dev-123")
        #expect(base.sent.first?.headers["Authorization"] == "Bearer tok-abc")
    }

    /// Dispatcher addendum 2: `BearerRetry` re-sends the SAME request on retry, which only replays
    /// for an in-memory body. Every `AuthorizedTransport` request body is `Data`, so the retried
    /// request carries byte-identical content — pinned, not assumed.
    @Test func aRetriedRequestCarriesTheIdenticalBody() async throws {
        let body = Data(#"{"displayName":"Aisha"}"#.utf8)
        // Stage 8 / S11: a plain 401. `isUnauthorizedBearer` is the status alone, so dressing the
        // fixture with the challenge read as though the retry depended on it.
        let (authorized, base, _) = transport([.json(401, "{}"), .json(200, "{}")])
        _ = try await authorized.send(request("/api/account/profile", method: "POST", body: body))
        #expect(base.sent.count == 2)
        #expect(base.sent.map { $0.body } == [body, body])
    }

    // MARK: - The 403 envelope leg

    /// The ONE place a 403 status envelope becomes an event — and the response still reaches the
    /// caller, so `AccountClient` can map it to `.blocked` rather than seeing a swallowed request.
    @Test func aBlockedEnvelopePostsOneEventAndStillReturnsTheResponse() async throws {
        let (authorized, _, events) = transport([.json(403, #"{"code":"ACCOUNT_BLOCKED","message":"x"}"#)])
        let response = try await authorized.send(request("/api/account/me"))
        #expect(events.posted == [.blocked])
        #expect(response.status == 403, "the response must not be swallowed")
    }

    @Test func aDeletedEnvelopePostsDeleted() async throws {
        let (authorized, _, events) = transport([.json(403, #"{"code":"ACCOUNT_DELETED","message":"x"}"#)])
        _ = try await authorized.send(request("/api/account/me"))
        #expect(events.posted == [.deleted])
    }

    /// Any other 403 — including the profile screen's own `EMAIL_NOT_VERIFIED` — is somebody else's
    /// business. Posting on it would sign the user out mid-bootstrap.
    @Test func anyOther403PostsNothing() async throws {
        let (authorized, _, events) = transport([
            .json(403, #"{"code":"EMAIL_NOT_VERIFIED"}"#),
            .json(403, "not json at all"),
            .json(403, #"{"validationField":"ACCOUNT_BLOCKED_input"}"#)
        ])
        for _ in 0..<3 { _ = try await authorized.send(request("/api/account/profile")) }
        #expect(events.posted.isEmpty)
    }

    /// `MAX_PEEK_BYTES = 1024` (`AccountStatusInterceptor.kt:137`): a malicious or broken server
    /// cannot make the client read an unbounded error body, and an envelope pushed past the cap is
    /// not found. 1024 bytes of padding first, then the code.
    @Test func theBodyPeekStopsAt1024Bytes() async throws {
        let padded = #"{"padding":"# + "\"\(String(repeating: "p", count: 1024))\"," + #""code":"ACCOUNT_BLOCKED"}"#
        let (authorized, _, events) = transport([.json(403, padded)])
        _ = try await authorized.send(request("/api/account/me"))
        #expect(events.posted.isEmpty, "the peek must stop at 1024 bytes")
    }

    /// Cubic R7 P0 + R9 P1 + R-final4 P2 (`AccountStatusInterceptor.kt:74-78`): the envelope check
    /// runs ONLY on this backend's four namespaces. A third party answering the same JSON shape
    /// must not be able to sign the user out.
    @Test func theEnvelopeCheckRunsOnlyOnTheFourBackendPathPrefixes() async throws {
        let envelope = #"{"code":"ACCOUNT_BLOCKED"}"#
        let honoured = ["/api/admin/users", "/api/v1/videos", "/api/account/me", "/api/share-metadata/abc"]
        let ignored = ["/api/other/thing", "/health", "/apiaccount/me", "/"]

        for path in honoured {
            let (authorized, _, events) = transport([.json(403, envelope)])
            _ = try await authorized.send(request(path))
            #expect(events.posted == [.blocked], "\(path) must be checked")
        }
        for path in ignored {
            let (authorized, _, events) = transport([.json(403, envelope)])
            _ = try await authorized.send(request(path))
            #expect(events.posted.isEmpty, "\(path) must not be checked")
        }
    }

    /// Task 7 review I2: the path prefix is only half the rule. This transport is SHARED and handed
    /// arbitrary URLs (`theBearerReachesTheApiHostAndNeverYouTube` pushes YouTube through the same
    /// instance), so an honoured path on a foreign host answering the same envelope must post
    /// nothing — signing is per-request-host and sign-out has to be too, via the same `BearerScope`.
    @Test func theEnvelopeCheckIgnoresForeignHosts() async throws {
        let envelope = #"{"code":"ACCOUNT_BLOCKED"}"#
        for path in ["/api/admin/users", "/api/v1/videos", "/api/account/me", "/api/share-metadata/abc"] {
            let (authorized, _, events) = transport([.json(403, envelope)])
            _ = try await authorized.send(request(path, host: "evil.example"))
            #expect(events.posted.isEmpty, "\(path) on a foreign host must not sign the user out")
        }
    }

    // MARK: - Stage 5 / M1 + M2: this backend answers a terminated account with a BARE 401

    /// Stage 8 / S3: the backend DOES send `WWW-Authenticate: Bearer` since `cadd7c9b`
    /// (`FirebaseAuthFilter.java:48-49,270`) — Android's interceptor gates its retry on it. iOS
    /// deliberately does not require it: on an `allowed` host a 401 IS a bearer rejection by
    /// construction, so a header-less 401 (what M2 found this backend answering at the time) must
    /// still drive the refresh. That is the case pinned here.
    @Test func aBare401WithNoChallengeHeaderIsStillRetriedWithARefreshedBearer() async throws {
        let base = ScriptedTransport([.json(401, #"{"error":"Invalid or expired token"}"#),
                                      .json(200, "{}")])
        let tokens = VersionedTokens()
        let events = Events()
        let authorized = AuthorizedTransport(base: base, apiHost: Self.apiHost, tokens: tokens,
                                             onStatusEvent: { events.posted.append($0) })

        let response = try await authorized.send(request("/api/account/me"))

        #expect(response.status == 200)
        #expect(base.sent.count == 2, "the refresh dance never fired against a header-less 401")
        #expect(base.sent.last?.headers["Authorization"] == "Bearer tok-2")
        #expect(events.posted.isEmpty, "a recoverable 401 is not a lifecycle event")
    }

    /// The fallback verdict. `FirebaseAuthFilter` enables `checkRevoked` on `/api/account/` and both
    /// `softDeleteUser` and `blockUser` revoke FIRST, so `verifyIdToken` throws and the filter's 401
    /// arm returns before its `ACCOUNT_DELETED` 403 arm can run — the ruling-C13 device wipe had NO
    /// admin-side trigger at all. Firebase's refusal of the forced refresh is the only local
    /// evidence, and it cannot be forged by a response body.
    @Test func aBare401WhoseRefreshIsRefusedAsUserNotFoundPostsDeletedExactlyOnce() async throws {
        let base = ScriptedTransport([.json(401, "{}"), .json(401, "{}")])
        let events = Events()
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: RefusingTokens(),
            onStatusEvent: { events.posted.append($0) },
            refreshRefusal: { .userNotFound })

        _ = try await authorized.send(request("/api/account/me"))

        #expect(events.posted == [.deleted])
    }

    @Test func aRefusalOfUserDisabledPostsBlockedAndNeverWipes() async throws {
        let base = ScriptedTransport([.json(401, "{}"), .json(401, "{}")])
        let events = Events()
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: RefusingTokens(),
            onStatusEvent: { events.posted.append($0) },
            refreshRefusal: { .userDisabled })

        _ = try await authorized.send(request("/api/account/me"))

        #expect(events.posted == [.blocked])
    }

    /// A 401 the client cannot explain must leave the session alone: a stall is not a deletion.
    @Test func anUnexplainedRefusalIsNotTerminal() async throws {
        let base = ScriptedTransport([.json(401, "{}"), .json(401, "{}")])
        let events = Events()
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: RefusingTokens(),
            onStatusEvent: { events.posted.append($0) },
            refreshRefusal: { .network })

        _ = try await authorized.send(request("/api/account/me"))

        #expect(events.posted.isEmpty)
        #expect(AuthorizedTransport.terminalEvent(for: nil) == nil)
        #expect(AuthorizedTransport.terminalEvent(for: .wrongPassword) == nil)
    }

    /// Stage 9 round 3 / R3-P1: the REAL supplier, wired as `AppContainer` wires it, instead of an
    /// inline closure. Every row above hands `refreshRefusal` a literal, so the transport's
    /// handling was fully pinned while its one production source was dead: `refreshRefusal()`
    /// re-derived the verdict from `Auth.auth().currentUser`, which Firebase has ALREADY nilled
    /// inside the throw for exactly the two codes that decide anything
    /// (`User.signOutIfTokenIsInvalid` → `signOutByForce`). The refusal is now recorded where the
    /// mint fails, so this drives the contract end to end: one forced mint, the code reported once
    /// and consumed on read.
    @Test func aRefusedForcedMintIsTheVerdictAndCostsNoSecondMint() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        auth.nextMintRefusal = .userNotFound
        let base = ScriptedTransport([.json(401, "{}"), .json(401, "{}")])
        let events = Events()
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: auth,
            onStatusEvent: { events.posted.append($0) },
            refreshRefusal: { await auth.refreshRefusal() })

        _ = try await authorized.send(request("/api/account/me"))

        #expect(events.posted == [.deleted],
                "the admin-side deletion had no working trigger on the bare-401 path")
        #expect(auth.tokenRefreshes == [false, true],
                "the verdict cost a second forced mint, or none at all")
        #expect(await auth.refreshRefusal() == nil, "the recorded refusal was reported twice")
    }

    /// Stage 9 round 4 / R4-P2 + NB-A: the box's life is bounded by "until the next mint". It was
    /// written by UNFORCED mints too and cleared by nothing but a read, so an unconsumed terminal
    /// code outlived its session — `EmailVerificationViewModel.checkNow()` forces a mint and
    /// discards the result, and a `token(false)` refusal followed by a successful `token(true)` is
    /// the transport's own ordinary path. Later, with nobody signed in, a 401 takes
    /// `token(true)` → nil at the no-user guard, which records NOTHING, so `refreshRefusal()`
    /// handed back the dead account's code and `AccountSession.handle(.deleted)` wiped the GUEST
    /// library. A successful mint and the no-user guard both clear it now.
    ///
    /// Driven on `FakeAuthClient`, which is the fake half of a two-implementation contract
    /// (`AuthClient.refreshRefusal()`'s doc). `FirebaseAuthClient` carries the identical three
    /// lines and stays **NOT PINNED** — Tier 3, no Firebase in this suite.
    @Test func aSuccessfulMintClearsTheRecordedRefusal() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))

        auth.nextMintRefusal = .userNotFound
        #expect(await auth.idToken(forceRefresh: true) == nil)
        #expect(await auth.idToken(forceRefresh: true) != nil, "the scripted refusal is consume-once")
        #expect(await auth.refreshRefusal() == nil,
                "a terminal verdict outlived the successful mint that disproved it")

        // The no-user guard clears it too, so nothing is left for a LATER session's 401 to read as
        // its own verdict — the sequence that wiped the guest library.
        auth.nextMintRefusal = .userDisabled
        #expect(await auth.idToken(forceRefresh: true) == nil)
        try auth.signOut()
        #expect(await auth.idToken(forceRefresh: true) == nil)
        #expect(await auth.refreshRefusal() == nil,
                "a signed-out session read the previous account's refusal as its own")
    }

    /// A foreign host is out of bearer scope, so its 401 is neither retried nor read as a verdict.
    @Test func aForeignHosts401IsNeitherRetriedNorTerminal() async throws {
        let base = ScriptedTransport([.json(401, "{}")])
        let events = Events()
        let authorized = AuthorizedTransport(
            base: base, apiHost: Self.apiHost, tokens: RefusingTokens(),
            onStatusEvent: { events.posted.append($0) },
            refreshRefusal: { .userNotFound })

        _ = try await authorized.send(request("/api/account/me", host: "evil.test"))

        #expect(base.sent.count == 1)
        #expect(events.posted.isEmpty)
    }

    /// A token source whose forced refresh mints a NEW bearer.
    private final class VersionedTokens: AuthTokenProviding, @unchecked Sendable {
        private var minted = 0
        func idToken(forceRefresh: Bool) async -> BearerToken? {
            minted += forceRefresh ? 1 : 0
            return BearerToken(value: "tok-\(minted + 1)", identity: "uid-A")
        }
    }

    /// A token source whose FORCED refresh comes back nil — Firebase refusing to re-mint.
    private struct RefusingTokens: AuthTokenProviding {
        func idToken(forceRefresh: Bool) async -> BearerToken? {
            forceRefresh ? nil : BearerToken(value: "tok-stale", identity: "uid-A")
        }
    }
}
