import FitrahAPI
import Foundation
import InnerTubeKit
import Synchronization
import Testing
@testable import FitrahTube

/// Task 9. Two things nothing else in the app can pin: every per-user store is re-scoped to the new
/// uid BEFORE the first `/me` request goes out, and the retry budget is a function of an injected
/// sleep rather than the clock.
@Suite(.perTest)
struct AccountSessionTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#

    /// A `UserScoped` spy that records the request count at the moment it was scoped — which is what
    /// makes "stores first, request second" an assertion instead of a hope.
    @MainActor final class SpyStore: UserScoped {
        private let requestCount: () -> Int
        private(set) var scopes: [(uid: String, requestsSoFar: Int)] = []
        var currentUserId: String = "" {
            didSet { scopes.append((currentUserId, requestCount())) }
        }
        init(requestCount: @escaping () -> Int) { self.requestCount = requestCount }
    }

    /// The injected sleep: records the duration asked for and returns immediately. No clock, no
    /// wall-clock waiting — the gate is hermetic.
    final class SleepRecorder: Sendable {
        private let durations = Mutex<[Duration]>([])
        var recorded: [Duration] { durations.withLock { $0 } }
        func record(_ duration: Duration) async { durations.withLock { $0.append(duration) } }
    }

    private func make(auth: FakeAuthClient, responses: [HTTPResponse], sleeps: SleepRecorder = SleepRecorder())
        -> (session: AccountSession, stores: [SpyStore], transport: ScriptedTransport, status: AccountStatusCenter) {
        let transport = ScriptedTransport(responses)
        let stores = (0..<3).map { _ in SpyStore(requestCount: { transport.sent.count }) }
        let status = AccountStatusCenter()
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: stores, status: status, sleep: { await sleeps.record($0) })
        return (session, stores, transport, status)
    }

    /// Drives `start()` to a loaded account. Bounded `Task.yield()` loops, never a sleep.
    private func signedIn(_ auth: FakeAuthClient, _ session: AccountSession) async throws -> Task<Void, Never> {
        let running = Task { await session.start() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        return running
    }

    // MARK: - Re-scoping

    @Test func aUidChangeScopesEveryStoreBeforeTheFirstRequest() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, transport, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        #expect(session.uid == "fake-uid")
        #expect(transport.sent.count == 1)
        for store in stores {
            #expect(store.currentUserId == "fake-uid")
            #expect(store.scopes.map(\.requestsSoFar) == [0],
                    "the store was scoped after the first /me request went out")
        }
    }

    @Test func signingOutScopesEveryStoreBackToAnon() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.signOut()
        #expect(session.state == .signedOut)
        #expect(session.uid == "")
        #expect(stores.allSatisfy { $0.currentUserId == "" })
        #expect(stores[0].scopes.map(\.uid) == ["fake-uid", ""])
    }

    // MARK: - The retry budget

    /// `MAX_ATTEMPTS = 3`, linear backoff `1 s * attempt` (`AccountRepositoryImpl.kt:111-147`) —
    /// three requests, two sleeps of 1 s and 2 s, and not one real second spent.
    @Test func aTransportErrorRetriesTwiceMoreOnLinearBackoff() async {
        let sleeps = SleepRecorder()
        let failure = { HTTPResponse.failing(URLError(.notConnectedToInternet)) }
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [failure(), failure(), failure()], sleeps: sleeps)
        await session.refresh()

        #expect(transport.sent.count == 3)
        #expect(sleeps.recorded == [.seconds(1), .seconds(2)])
        #expect(session.state == .failed(code: nil, message: String(localized: "auth_error_network")))
    }

    /// 4xx and 5xx are NEVER retried — only a transport error is. A second canned response is queued
    /// so a retry would be silently satisfied instead of throwing `exhausted`.
    @Test(arguments: [400, 404, 422, 500, 503])
    func aServerStatusIsNeverRetried(status: Int) async {
        let sleeps = SleepRecorder()
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [.json(status, "{}"), .json(200, Self.meJSON)],
                                              sleeps: sleeps)
        await session.refresh()

        #expect(transport.sent.count == 1)
        #expect(sleeps.recorded.isEmpty)
        #expect(session.state.me == nil)
    }

    /// Task 7 addendum: a bare 401 survives `BearerRetry` only when the signed-in identity changed
    /// between the first attempt and the retry. That is "re-drive under the current account" — never
    /// an error banner, and never a retry against the identity that just went away.
    @Test func aCrossAccount401IsNotAnErrorBanner() async {
        let sleeps = SleepRecorder()
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [.json(401, "{}"), .json(200, Self.meJSON)],
                                              sleeps: sleeps)
        await session.refresh()

        #expect(transport.sent.count == 1)
        #expect(sleeps.recorded.isEmpty)
        #expect(session.state == .loading, "a 401 leaves the session waiting for the auth stream, not failed")
    }

    /// A terminal 403 envelope reaching the client directly drops the session. `AuthorizedTransport`
    /// posts the same event, but "Something went wrong" over a dead account is the wrong end state
    /// even for one request that missed the post.
    @Test func aTerminal403DropsTheSession() async {
        let (session, stores, _, _) = make(auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)),
                                           responses: [.json(403, #"{"code":"ACCOUNT_BLOCKED"}"#)])
        stores.forEach { $0.currentUserId = "fake-uid" }
        await session.refresh()

        #expect(session.state == .signedOut)
        #expect(stores.allSatisfy { $0.currentUserId == "" })
    }

    // MARK: - Terminal events

    /// A block is REVERSIBLE, and an ordinary sign-out deliberately keeps the library
    /// (`AccountRepositoryImpl.kt:44-49`) — so the stores are re-scoped, never cleared, and no
    /// request is made.
    @Test func handleBlockedSignsOutAndLeavesLocalDataIntact() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, transport, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.handle(.blocked)
        #expect(session.state == .signedOut)
        #expect(await auth.currentUser() == nil)
        #expect(stores.allSatisfy { $0.currentUserId == "" })
        #expect(stores[0].scopes.map(\.uid) == ["fake-uid", ""], "re-scoped, never wiped")
        #expect(transport.sent.count == 1, "signing out makes no request of its own")
    }

    /// Task 18 adds the wipe; in THIS task `.deleted` is sign-out and nothing else, so the local rows
    /// are still there for Task 18's test to watch disappear.
    @Test func handleDeletedSignsOutAndWipesNothingYet() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.handle(.deleted)
        #expect(session.state == .signedOut)
        #expect(stores[0].scopes.map(\.uid) == ["fake-uid", ""])
    }

    /// `.signedOut` on the center is the user's OWN sign-out, posted so per-account holders can
    /// release state without depending on the auth client. Posted exactly once: a second `signOut()`
    /// drops nothing and announces nothing, which is what stops `RootView`'s
    /// consume -> handle -> signOut path from looping.
    @Test func signingOutPostsSignedOutExactlyOnce() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, _, _, status) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.signOut()
        for _ in 0..<200 where status.pending == nil { await Task.yield() }
        #expect(status.consume() == .signedOut)

        session.signOut()
        for _ in 0..<200 { await Task.yield() }
        #expect(status.pending == nil)
    }

    /// `AuthorizedTransport` posts from whatever isolation the request ran on — never the main
    /// actor. One delivery, and `consume()` clears it.
    @Test func anEventPostedOffTheMainActorIsDeliveredOnceAndConsumed() async {
        let status = AccountStatusCenter()
        await Task.detached { status.post(.blocked) }.value
        for _ in 0..<200 where status.pending == nil { await Task.yield() }

        #expect(status.consume() == .blocked)
        #expect(status.consume() == nil)
    }
}
