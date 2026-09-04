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

    private func make(auth: FakeAuthClient, responses: [HTTPResponse], sleeps: SleepRecorder = SleepRecorder(),
                      wipe: @escaping @MainActor @Sendable () async -> Void = {})
        -> (session: AccountSession, stores: [SpyStore], transport: ScriptedTransport, status: AccountStatusCenter) {
        let transport = ScriptedTransport(responses)
        let stores = (0..<3).map { _ in SpyStore(requestCount: { transport.sent.count }) }
        let status = AccountStatusCenter()
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: stores, status: status, sleep: { await sleeps.record($0) }, wipe: wipe)
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

    /// Task 11's `adopt(_:)` REFRESHES the signed-in identity; swapping identity is `start()`'s job,
    /// because only it re-scopes every per-user store first. A foreign uid is therefore refused
    /// outright, or the new account would render against the previous one's rows (fix round 1 / M3).
    @Test func adoptingADifferentUidLeavesTheSessionIdentityAlone() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }
        let before = session.user
        #expect(before?.uid == "fake-uid")

        session.adopt(AuthUser(uid: "someone-else", email: "other@fitrah.test",
                               isEmailVerified: !(before?.isEmailVerified ?? false),
                               providerIDs: ["password"]))

        #expect(session.user == before, "a foreign identity was adopted")
        #expect(stores.allSatisfy { $0.currentUserId == "fake-uid" }, "the stores were left on the old uid")
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
        // Fix round 1 / M1: `me == nil` alone is also true of a `.loading` park, which is exactly the
        // end state the 401 arm used to reach — so the failure itself is asserted. 400/422 map to
        // `AccountError.validation`, which lands on `refresh`'s `default:` arm and carries no code;
        // 404/500/503 map to `.unknown(status:)` and carry theirs.
        let code = [400, 422].contains(status) ? nil : status
        #expect(session.state == .failed(code: code, message: String(localized: "auth_error_generic")))
    }

    /// Fix round 1 / I1 + M2. `BearerRetry` surfaces a bare 401 in THREE cases, not the one the
    /// Task 7 addendum described: the cross-account identity change, `token(true)` returning nil (the
    /// ordinary expired/failed-refresh path), and a freshly refreshed token still being rejected.
    /// Only the first is followed by an auth transition, so parking at `.loading` and waiting for the
    /// stream hung the other two forever — signed-in user, no `/me`, no banner, no escape.
    ///
    /// So the park is BOUNDED: re-drive inside the retry budget (a new token may be minted between
    /// sends), then fail. No sleep — a 401 is not a network stall, and nothing about waiting makes a
    /// rejected token acceptable.
    @Test func aBare401ParksThenFailsWithinTheBudget() async {
        let sleeps = SleepRecorder()
        let bare401 = { HTTPResponse.json(401, "{}") }
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [bare401(), bare401(), bare401()], sleeps: sleeps)
        await session.refresh()

        // 3 sends, not 2: attempts 1 and 2 re-drive, attempt 3 is the last of the budget and fails.
        #expect(transport.sent.count == 3)
        #expect(sleeps.recorded.isEmpty, "a 401 re-drives immediately — backoff is for transport errors")
        #expect(session.state == .failed(code: 401, message: String(localized: "auth_error_generic")))
    }

    /// The half of the old behaviour worth keeping: the FIRST 401 is not an error banner. A token
    /// minted between the two sends still lands the account.
    @Test func aBare401FollowedByASuccessLoadsTheAccount() async {
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [.json(401, "{}"), .json(200, Self.meJSON)])
        await session.refresh()

        #expect(transport.sent.count == 2)
        #expect(session.state.me?.uid == "fake-uid")
    }

    /// M4: a cancelled refresh must not burn its budget into a network banner. `AccountClient.send`
    /// maps `CancellationError` to `.network` by design and `realSleep`'s `try?` swallows the
    /// cancellation, so a `RootView` disappearing mid-refresh used to run three instant attempts and
    /// end on "No internet connection" — over a session nobody is watching any more.
    @Test func aCancelledRefreshStopsAtTheSleepInsteadOfBanneringTheNetwork() async {
        let sleeps = SleepRecorder()
        let failure = { HTTPResponse.failing(URLError(.notConnectedToInternet)) }
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [failure(), failure(), failure()], sleeps: sleeps)
        let running = Task { await session.refresh() }
        running.cancel()
        await running.value

        #expect(transport.sent.count == 1, "the cancelled refresh does not re-drive")
        #expect(session.state == .loading, "cancelled, not failed — no banner over an abandoned screen")
    }

    /// Fix round 1 / I2. `SignInViewModel.land()` refreshes on the same auth transition `start()`
    /// is about to refresh on. With no in-flight guard both issued `GET /me` and both wrote
    /// `state`, so a `.loaded` account could be overwritten by the loser's `.failed` — and
    /// `RootView` then read `status == nil` and routed a pending-profile account to the shell. The
    /// second caller now awaits the first's work instead of starting its own.
    @Test func twoOverlappingRefreshesShareOneRequestAndOneOutcome() async {
        let (session, _, transport, _) = make(auth: FakeAuthClient(state: .signedOut),
                                              responses: [.json(200, Self.meJSON)])
        // The out-of-band caller's budget is the splash's 1; the first caller's 3 is what survives.
        async let first = Self.refreshAndRead(session)
        async let second = Self.refreshAndRead(session, maxAttempts: 1)
        let (a, b) = await (first, second)

        #expect(transport.sent.count == 1, "the second refresh issued a /me of its own")
        #expect(a == b, "the two callers observed different states")
        #expect(a.me?.uid == "fake-uid")
    }

    private static func refreshAndRead(_ session: AccountSession, maxAttempts: Int = 3) async -> AccountState {
        await session.refresh(maxAttempts: maxAttempts)
        return session.state
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

    /// Task 18: `.deleted` is the device wipe, and it is DETACHED — the sign-out lands after the
    /// wipe has finished, so nothing here is true synchronously any more. Which of the two orders
    /// runs is not cosmetic: dropping the session first re-scopes every store and re-reads it,
    /// repopulating `items` from rows the wipe is about to delete
    /// (`DeleteAccountTests` pins that end of it).
    @Test func handleDeletedWipesTheDeviceThenSignsOut() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let wipes = Mutex<Int>(0)
        let (session, stores, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)],
                                           wipe: { wipes.withLock { $0 += 1 } })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.handle(.deleted)
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }

        #expect(wipes.withLock { $0 } == 1)
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

    // MARK: - The fixture launch barrier (fix round 1 / M1)

    /// `FitrahTubeApp.awaitFakeAccountIfSignedIn` waits here before the `-fitrah-seed-*` hooks
    /// write through per-user stores. The bound is the SAFETY NET, not the exit: a `where` clause
    /// skips an iteration rather than ending the loop, so the shape this replaces spent all 2000
    /// yields on every fixture launch, account landed or not.
    @Test func awaitAccountStopsAsSoonAsTheAccountLands() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, _, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = Task { await session.start() }
        defer { running.cancel() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")

        let yields = await session.awaitAccount(bound: 2000)
        #expect(session.state.me != nil)
        #expect(yields < 2000, "the barrier kept yielding after the account landed")
    }

    /// The other half: a fixture whose `/me` can never answer must not park the launch path.
    @Test func awaitAccountGivesUpAtTheBound() async {
        let (session, _, _, _) = make(auth: FakeAuthClient(state: .signedOut), responses: [])
        let yields = await session.awaitAccount(bound: 8)

        #expect(yields == 8)
        #expect(session.state.me == nil)
    }
}
