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
    /// Stage 9 round 2 / P1: the SECOND account, for the cross-account window. `/me` for somebody
    /// who is not `FakeAuthClient.defaultUser`, so "whose record is on screen" is an assertion.
    private static let meBJSON = #"{"uid":"uid-b","email":"other@fitrah.test","status":"active","role":"user"}"#
    private static let accountB = AuthUser(uid: "uid-b", email: "other@fitrah.test",
                                           isEmailVerified: true, providerIDs: ["password"])

    /// A `UserScoped` spy that records the request count at the moment it was scoped — which is what
    /// makes "stores first, request second" an assertion instead of a hope.
    @MainActor final class SpyStore: UserScoped {
        func reload() {}
        private let requestCount: () -> Int
        private(set) var scopes: [(uid: String, requestsSoFar: Int)] = []
        var currentUserId: String = "" {
            didSet { scopes.append((currentUserId, requestCount())) }
        }
        init(requestCount: @escaping () -> Int) { self.requestCount = requestCount }
    }

    private func make(auth: FakeAuthClient, responses: [HTTPResponse], sleeps: SleepRecorder = SleepRecorder(),
                      wipe: @escaping @MainActor @Sendable () async -> Error? = { nil })
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

    /// The stage-7 shape: a session with no spy stores, over an explicit transport, with the two
    /// new seams (`providers`, `status`) reachable.
    private func makeSession(auth: FakeAuthClient, transport: ScriptedTransport,
                             status: AccountStatusCenter = AccountStatusCenter(),
                             providers: [any OAuthSignInProvider] = [],
                             sleeps: SleepRecorder = SleepRecorder(),
                             blockingSleep: (@Sendable () async -> Void)? = nil) -> AccountSession {
        AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: status,
            sleep: { await sleeps.record($0); await blockingSleep?() }, wipe: { nil },
            providers: providers)
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

        #expect(session.state.me?.uid == "fake-uid")
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
        #expect(session.state.me?.uid == nil)
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

    /// Task 17's `apply(_:)` writes the record a profile `PUT` just answered with, and it is a
    /// SECOND public writer of `state` beside `fetch`/`signOut` — so its uid guard does not get to
    /// ship less pinned than the `adopt(_:)` guard it was modelled on (fix round 1 / M1).
    @Test func applyingARecordForADifferentUidLeavesTheAccountAlone() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let (session, stores, _, _) = make(auth: auth, responses: [.json(200, Self.meJSON)])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }
        let before = session.state.me
        #expect(before?.uid == "fake-uid")

        session.apply(AccountMe(uid: "someone-else", email: "other@fitrah.test",
                                displayName: "Someone Else", dateOfBirth: nil, phoneNumber: nil,
                                status: .active, role: "user"))

        #expect(session.state.me == before, "a foreign account record was applied")
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
        // Stage 3 / M6: RESTORED, not parked at `.loading`. The old end state made cancellation
        // contagious — every follower, `start()` included, returned having observed `.loading`, and
        // `RootView` then rendered a signed-in user as a guest with nothing left to re-drive `/me`.
        #expect(session.state == .signedOut, "cancelled, not failed — no banner over an abandoned screen")
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
    ///
    /// Fix round 1 / I3: with a counting `wipe:`, because "never wiped" was an assertion MESSAGE on
    /// the store scopes while the wiper itself was invisible to this test (`make` defaults it to a
    /// no-op) — on the phase's most destructive path. The yields are deliberate: a wipe kicked off
    /// asynchronously would pass a synchronous zero.
    @Test func handleBlockedSignsOutAndLeavesLocalDataIntact() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let wipes = Mutex<Int>(0)
        let (session, stores, transport, _) = make(auth: auth, responses: [.json(200, Self.meJSON)],
                                                   wipe: { wipes.withLock { $0 += 1 }; return nil })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.handle(.blocked)
        #expect(session.state == .signedOut)
        #expect(await auth.currentUser() == nil)
        #expect(stores.allSatisfy { $0.currentUserId == "" })
        #expect(stores[0].scopes.map(\.uid) == ["fake-uid", ""], "re-scoped, never wiped")
        #expect(transport.sent.count == 1, "signing out makes no request of its own")
        for _ in 0..<200 { await Task.yield() }
        #expect(wipes.withLock { $0 } == 0, "a REVERSIBLE block erased the device's library")
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
                                           wipe: { wipes.withLock { $0 += 1 }; return nil })
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
    ///
    /// The plain sign-out keeps the library too (fix round 1 / I3): only `.deleted` wipes.
    @Test func signingOutPostsSignedOutExactlyOnce() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let wipes = Mutex<Int>(0)
        let (session, _, _, status) = make(auth: auth, responses: [.json(200, Self.meJSON)],
                                           wipe: { wipes.withLock { $0 += 1 }; return nil })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        session.signOut()
        for _ in 0..<200 where status.pending == nil { await Task.yield() }
        let signedOut = status.consume()
        #expect(signedOut?.event == .signedOut)
        // Task 33 / cold review: the session's own announcements must stay UNATTRIBUTED, and
        // nothing pinned it. Not because a uid would be refused the moment the session ends —
        // `lastKnownUid` deliberately keeps the departed account admissible — but because the uid
        // such a post could carry is whatever `user` happened to hold, and `handle` would then
        // measure THAT against a session it no longer describes. Nil is the honest value for an
        // announcement that is about the teardown itself rather than about an account.
        #expect(signedOut?.uid == nil, "the session attributed its own sign-out announcement")

        session.signOut()
        for _ in 0..<200 { await Task.yield() }
        #expect(status.pending == nil)
        #expect(wipes.withLock { $0 } == 0, "an ordinary sign-out erased the device's library")
    }

    /// `AuthorizedTransport` posts from whatever isolation the request ran on — never the main
    /// actor. One delivery, and `consume()` clears it.
    @Test func anEventPostedOffTheMainActorIsDeliveredOnceAndConsumed() async {
        let status = AccountStatusCenter()
        await Task.detached { status.post(.blocked) }.value
        for _ in 0..<200 where status.pending == nil { await Task.yield() }

        #expect(status.consume()?.event == .blocked)
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

    // MARK: - Stage 3 / M6: a cancelled leader must not park the session

    /// The coalescer makes cancellation contagious: only the FIRST caller's cancellation reaches
    /// the shared task, and every follower — `start()` included — returns having observed whatever
    /// state it left. Leaving `.loading` standing meant `RootView` read `status == nil` and rendered
    /// a signed-in user as a guest, with nothing left to re-drive `/me`.
    @Test func aCancelledRefreshRestoresTheStateItFound() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let transport = ScriptedTransport([.json(200, Self.meJSON), .failing(URLError(.timedOut))])
        let gate = Gate()
        let session = makeSession(auth: auth, transport: transport,
                                  sleeps: SleepRecorder(), blockingSleep: { await gate.block() })

        await session.refresh()
        let loaded = session.state
        #expect(loaded.me != nil)

        // Cancelled while the SECOND attempt's sleep is in flight, through the rendezvous actor —
        // a yield count would be a scheduling assumption, and the injected sleep returns instantly.
        let task = Task { await session.refresh() }
        await gate.waitUntilBlocked()
        task.cancel()
        await gate.release()
        await task.value

        #expect(session.state == loaded,
                "a cancelled leader parked the session at .loading for every other observer")
    }

    // MARK: - Stage 7 fix 2 / I1: a refresh is not a blank screen

    /// The account already on screen STAYS on screen while its own refresh runs. `fetch` used to
    /// write `.loading` unconditionally, and the foreground refresh (S5-C2.1) then drove every
    /// return to foreground through `.loaded -> .loading -> .loaded` — which the new third arm
    /// (`MeTabRoot.arm`) rendered as "Something went wrong" with a Retry button, in the Me tab and
    /// in Settings' Account section, tearing `MeSignedInView` down and re-running its `.task` blocks
    /// each time. A `.failed` result still replaces the value (that is the row the arm is for) and a
    /// DIFFERENT uid still resets, because the identity `start()` just set is what is compared.
    ///
    /// Observed through the injected sleep's rendezvous — the first attempt fails with a transport
    /// error, so the state is inspectable while the retry is genuinely in flight, with no clock.
    @Test func aRefreshForTheSameAccountKeepsTheAccountRendered() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON),
                                           .failing(URLError(.timedOut)),
                                           .json(200, Self.meJSON)])
        let session = makeSession(auth: auth, transport: transport,
                                  sleeps: SleepRecorder(), blockingSleep: { await gate.block() })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }
        let loaded = session.state
        #expect(loaded.me?.uid == "fake-uid")

        let refreshing = Task { await session.refresh() }
        await gate.waitUntilBlocked()

        #expect(session.state == loaded, "the account on screen was blanked to .loading by its own refresh")
        #expect(MeTabRoot.arm(signedIn: true, state: session.state) == .signedIn,
                "the Me tab and Settings' Account section rendered an error card over a live account")

        await gate.release()
        await refreshing.value
        #expect(session.state.me?.uid == "fake-uid")
    }

    // MARK: - Stage 9 round 2 / P1: a late answer for an identity that is gone

    /// `state = .loaded(try await account.me())` had no identity check, so an answer that came back
    /// AFTER the account it was asked for had gone was published anyway. The Firebase listener is
    /// the honest way to lose an identity mid-round — a revoked token, a sign-out on another device
    /// — and it cancels nothing, so the round runs to completion and writes `.loaded(A)` over
    /// `.signedOut`. `MeTabRoot.arm` then rendered the signed-in Me screen for a guest.
    ///
    /// Parked at the injected sleep's rendezvous between attempt 1 and attempt 2, so the round is
    /// genuinely in flight with no clock — the transport has no park of its own.
    @Test func aSignOutMidRefreshIsNotOverwrittenByTheLateAnswer() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON),
                                           .failing(URLError(.timedOut)),
                                           .json(200, Self.meJSON)])
        let session = makeSession(auth: auth, transport: transport, blockingSleep: { await gate.block() })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        let refreshing = Task { await session.refresh() }
        await gate.waitUntilBlocked()

        try auth.signOut()
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }
        #expect(session.state == .signedOut)

        await gate.release()
        await refreshing.value

        #expect(session.state == .signedOut,
                "the /me answer for the account that had just signed out was published anyway")
        #expect(MeTabRoot.arm(signedIn: false, state: session.state) == .guest,
                "the signed-in Me screen rendered for a guest")
    }

    /// Shape two of the same defect, and the expensive one: sign out, then sign in as somebody else
    /// inside the one in-flight `/me`. `dropSession()` left `inFlight` standing, so `start()`'s
    /// `.signedIn(B)` arm found it and AWAITED account A's round instead of asking for B's record —
    /// B never fetched, `user == B` while A's record was on screen, and a Profile save prefilled
    /// from A would `PUT` A's name under B's bearer.
    @Test func aSwitchToAnotherAccountMidRefreshFetchesItsOwnRecord() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON),
                                           .failing(URLError(.timedOut)),
                                           .json(200, Self.meBJSON)])
        let session = makeSession(auth: auth, transport: transport, blockingSleep: { await gate.block() })
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        let refreshing = Task { await session.refresh() }
        await gate.waitUntilBlocked()

        session.signOut()
        auth.user = Self.accountB
        _ = try await auth.signIn(email: "other@fitrah.test", password: "p")
        for _ in 0..<500 where session.state.me?.uid != "uid-b" { await Task.yield() }

        // Asserted BEFORE the release: "started its own round" means B's request is on the wire
        // while A's is still parked. Joining A's round leaves the count at two and B's record
        // unfetched until A's answer lands, which is the whole bug.
        #expect(transport.sent.count == 3, "account B joined account A's in-flight round")
        #expect(session.state.me?.uid == "uid-b", "account B never fetched its own record")

        await gate.release()
        await refreshing.value

        #expect(session.state.me?.uid == "uid-b", "account A's late answer landed under account B")
        #expect(transport.sent.count == 3, "the dropped account's round asked again after the switch")
    }

    // MARK: - Stage 9 round 3 / NB1 + NB3 + NB2

    /// NB1. The identity guard round 2 added compares `user?.uid` with the uid the round STARTED
    /// for — and `SignInViewModel.land()` starts a round with no identity at all, on purpose: it
    /// refreshes on the auth transition `start()` has not necessarily observed yet, which is the
    /// only reason it exists. The strict comparison therefore dropped the answer to the app's
    /// primary sign-in path, leaving a signed-in account at `.loading` (a spinner with no Retry)
    /// with nothing left to re-drive `/me`, and `RootView` reading `status == nil` — the
    /// pending-profile mis-route `land()` is there to prevent.
    ///
    /// Parked INSIDE `account.me()` (the transport's own park), which is what makes `sent.count`
    /// an assertion: `start()`'s `.signedIn` arm must JOIN this round, not fetch again.
    @Test func aSignInThatLandsBeforeTheListenerStillPublishesTheAccount() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON)], park: { _ in await gate.block() })
        let session = makeSession(auth: auth, transport: transport)
        let running = Task { await session.start() }
        defer { running.cancel() }

        // `land()`'s shape: the refresh leads the listener.
        let landing = Task { await session.refresh(maxAttempts: 1) }
        await gate.waitUntilBlocked()
        #expect(session.user == nil, "the round under test must start with no identity")

        _ = try await auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where session.user == nil { await Task.yield() }
        #expect(session.user?.uid == "fake-uid")

        await gate.release()
        await landing.value

        #expect(session.state.me?.uid == "fake-uid",
                "the account the sign-in had just landed was dropped by its own identity guard")
        #expect(MeTabRoot.arm(signedIn: true, state: session.state) == .signedIn,
                "a just-signed-in account was parked at a spinner with no Retry")
        #expect(transport.sent.count == 1,
                "start()'s .signedIn arm fetched a second time instead of joining the round")
    }

    /// NB3. The SUCCESS arm's guard, on its own. Both round-2 pins park at the injected sleep, so
    /// the identity change lands between two attempts and the post-sleep guard returns first — the
    /// success arm is never reached and removing its guard alone stays green. Here the round runs
    /// at `maxAttempts: 1` (no sleep, no retry) and parks inside `account.me()`, so the answer
    /// arrives AFTER the sign-out and the only thing that can refuse it is the guard on the write.
    @Test func aSignOutWhileTheAnswerIsOnTheWireIsNotPublished() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON), .json(200, Self.meJSON)],
                                          park: { index in if index == 2 { await gate.block() } })
        let session = makeSession(auth: auth, transport: transport)
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        let refreshing = Task { await session.refresh(maxAttempts: 1) }
        await gate.waitUntilBlocked()

        // The LISTENER takes the identity away: nothing is cancelled, so the round runs to its
        // success arm with the answer for an account that is no longer signed in.
        try auth.signOut()
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }
        #expect(session.state == .signedOut)

        await gate.release()
        await refreshing.value

        #expect(session.state == .signedOut,
                "the /me answer on the wire when the account signed out was published anyway")
        #expect(MeTabRoot.arm(signedIn: false, state: session.state) == .guest,
                "the signed-in Me screen rendered for a guest")
    }

    /// NB2. A superseded round must not clear the slot its replacement is using. `dropSession()`
    /// cancels and clears, cancellation takes several async hops to surface, and `start()`'s
    /// `.signedIn(B)` arm installs B's round inside that window — so the old round's unconditional
    /// release wiped B's slot and the next caller started a SECOND concurrent round for B instead
    /// of joining. Two rounds for one identity both pass the identity guards, so the loser's
    /// `.failed` could land over the winner's `.loaded` (fix round 1 / I2, by another route).
    ///
    /// Two parks: A's round is held while B's is installed and held in turn, so "who owns the
    /// slot" is decided while both are genuinely in flight.
    @Test func aSupersededRoundDoesNotClearTheSlotItsReplacementIsUsing() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gateA = Gate()
        let gateB = Gate()
        let transport = ScriptedTransport(
            [.json(200, Self.meJSON), .json(200, Self.meJSON),
             .json(200, Self.meBJSON), .json(200, Self.meBJSON)],
            park: { index in
                if index == 2 { await gateA.block() }
                if index == 3 { await gateB.block() }
            })
        let session = makeSession(auth: auth, transport: transport)
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        let dropped = Task { await session.refresh(maxAttempts: 1) }
        await gateA.waitUntilBlocked()

        session.signOut()
        auth.user = Self.accountB
        _ = try await auth.signIn(email: "other@fitrah.test", password: "p")
        await gateB.waitUntilBlocked()

        // A's cancelled round resumes and drops its answer — and must leave B's slot alone.
        await gateA.release()
        await dropped.value

        let third = Task { await session.refresh(maxAttempts: 1) }
        for _ in 0..<500 where transport.sent.count == 3 { await Task.yield() }
        #expect(transport.sent.count == 3,
                "a third caller started a second concurrent round for account B")

        await gateB.release()
        await third.value
        #expect(session.state.me?.uid == "uid-b")
        #expect(transport.sent.count == 3)
    }

    /// NB-B. A CANCELLED round must never publish, even when its response is delivered anyway.
    /// `dropSession()` cancels the in-flight round, but cancellation is advisory: nothing on the
    /// `/me` path polls it (`AccountClient` → `BearerRetry` → the transport all run to completion),
    /// so a request already on the wire answers 200 regardless. For a round that started with NO
    /// identity — `SignInViewModel.land()`'s shape, and the one NB1 deliberately re-opened —
    /// `startedFor == nil` made `publishable()` true, so account A's cancelled answer landed as
    /// `.loaded(A)` under whoever is signed in now: `MeTabRoot.arm` reports `.signedIn`, Settings
    /// says "Signed in as A", and a Profile save would `PUT` A's name under the current bearer.
    ///
    /// Cancel while PARKED inside `account.me()`, then release: the answer is delivered after the
    /// cancellation, which is the only ordering that can reach the success arm.
    @Test func aCancelledRoundDoesNotPublishAnAnswerDeliveredAfterTheCancel() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON)], park: { _ in await gate.block() })
        let session = makeSession(auth: auth, transport: transport)
        let before = session.state

        // `land()`'s shape: the refresh leads the listener, so the round carries no identity.
        let landing = Task { await session.refresh(maxAttempts: 1) }
        await gate.waitUntilBlocked()
        #expect(session.user == nil, "the round under test must start with no identity")

        landing.cancel()
        await gate.release()
        await landing.value

        #expect(session.state.me == nil,
                "a cancelled round published its answer, adopting an identity the session had dropped")
        // And the OTHER half (Stage 3 / M6): it does not leave its own `.loading` standing either.
        #expect(session.state == before, "the cancelled round parked the session at a spinner with no Retry")
        #expect(transport.sent.count == 1)
    }

    /// Stage 9 round 5 / NB-C. The `defer` restore must put back only the `.loading` THIS round
    /// wrote. `matchesIdentity()` short-circuits true whenever `startedFor == nil` — NB1's shape,
    /// deliberately — so for a nil-started round cancellation was the ONLY remaining guard, and the
    /// restore could write `previousState` over a value another writer put there. Here:
    /// `dropSession()` cancels A and frees the slot, B signs in and its own round publishes
    /// `.loaded(B)`, and A's straggler then reverted the screen to `.signedOut` — a signed-in B
    /// rendered as a guest, with nothing left to re-drive `/me` until the next foreground hook.
    @Test func aCancelledNilStartedRoundDoesNotRestoreOverANewerIdentitysAnswer() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON), .json(200, Self.meBJSON)],
                                          park: { index in if index == 1 { await gate.block() } })
        let session = makeSession(auth: auth, transport: transport)
        let running = Task { await session.start() }
        defer { running.cancel() }

        // `land()`'s shape: the round leads the listener, so `startedFor` is nil and the state it
        // found — the `previousState` the `defer` would put back — is `.signedOut`.
        let landing = Task { await session.refresh(maxAttempts: 1) }
        await gate.waitUntilBlocked()
        #expect(session.user == nil, "the round under test must start with no identity")

        // The drop that cancels A's round AND frees the slot, so B's sign-in leads a round of its
        // own rather than joining this one.
        session.signOut()
        auth.user = Self.accountB
        _ = try await auth.signIn(email: "other@fitrah.test", password: "p")
        for _ in 0..<500 where session.state.me?.uid != "uid-b" { await Task.yield() }
        #expect(session.state.me?.uid == "uid-b", "account B never published its own record")

        await gate.release()
        await landing.value

        #expect(session.state.me?.uid == "uid-b",
                "a cancelled nil-started round restored .signedOut over the account that signed in after it")
        #expect(MeTabRoot.arm(signedIn: true, state: session.state) == .signedIn)
    }

    /// Cubic round 6 / P2. The auth stream's own `.signedOut` arm must free the coalescing slot,
    /// exactly as `dropSession()` does. Firebase force-signs a user out INSIDE a refused forced
    /// mint (`signOutIfTokenIsInvalid`), so `.signedOut` arrives through the LISTENER with account
    /// A's round still parked in the slot — and `start()`'s `.signedIn(B)` arm then reached
    /// `refresh()`, whose first statement joins whatever is in flight. B never asked for its own
    /// record, A's answer was correctly dropped as stale, and `MeTabRoot.arm(signedIn: true,
    /// state: .signedOut)` is `.unreachable`: the "Something went wrong" Retry card, in the Me tab
    /// and in Settings' Account section, for an account that had just signed in.
    @Test func aListenerSignOutFreesTheSlotSoTheNextIdentityFetchesItsOwnRecord() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let gate = Gate()
        let transport = ScriptedTransport([.json(200, Self.meJSON), .json(200, Self.meJSON),
                                           .json(200, Self.meBJSON)],
                                          park: { index in if index == 2 { await gate.block() } })
        let session = makeSession(auth: auth, transport: transport)
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        let parked = Task { await session.refresh(maxAttempts: 1) }
        await gate.waitUntilBlocked()

        // The LISTENER, not `session.signOut()` — that path already cancels, which is the whole
        // asymmetry this closes.
        try auth.signOut()
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }
        #expect(session.state == .signedOut)

        auth.user = Self.accountB
        _ = try await auth.signIn(email: "other@fitrah.test", password: "p")
        for _ in 0..<500 where session.state.me?.uid != "uid-b" { await Task.yield() }

        #expect(transport.sent.count == 3,
                "account B joined the previous identity's parked round instead of fetching its own record")
        #expect(session.state.me?.uid == "uid-b")
        #expect(MeTabRoot.arm(signedIn: true, state: session.state) == .signedIn,
                "a just-signed-in account landed on the Retry card")

        await gate.release()
        await parked.value
    }

    // MARK: - Stage 9 round 2 / P2: offline keeps the account

    /// `fetch` keeps a loaded account rendered across its own refresh, but the FAILURE write was
    /// unguarded: at the foreground hook's `maxAttempts: 1` the retry arm cannot fire (`1 < 1`), so
    /// ONE transient error on a return to the app replaced the account with "No internet
    /// connection" — a Retry card in the Me tab and in Settings' Account section, and the Me-tab
    /// route to Favorites/Saved gone exactly when the device is offline. The cold-start row
    /// (nothing loaded → `.failed`) is `aTransportErrorRetriesTwiceMoreOnLinearBackoff`, unchanged.
    @Test func aTransientNetworkFailureOnForegroundKeepsTheAccount() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let transport = ScriptedTransport([.json(200, Self.meJSON),
                                           .failing(URLError(.notConnectedToInternet))])
        let session = makeSession(auth: auth, transport: transport)
        let running = try await signedIn(auth, session)
        defer { running.cancel() }
        let loaded = session.state
        #expect(loaded.me?.uid == "fake-uid")

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(transport.sent.count == 2, "the foreground refresh did not reach the network")
        #expect(session.state == loaded,
                "an offline foreground replaced the loaded account with an error banner")
        #expect(MeTabRoot.arm(signedIn: true, state: session.state) == .signedIn)
    }

    // MARK: - Stage 5 / C1.3: a refused sign-out is not a sign-out

    /// `Auth.signOut()` assigns `_currentUser = nil` only when the Keychain write succeeded, so a
    /// swallowed throw left the app reporting signed-out while `idToken(forceRefresh:)` still minted
    /// bearers for the previous account — and the next launch restored it.
    @Test func aFailedFirebaseSignOutDoesNotReportSignedOut() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let status = AccountStatusCenter()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  status: status)
        await session.refresh()
        #expect(session.state.me != nil)

        auth.nextError = .unknown
        session.signOut()

        #expect(session.state != .signedOut, "the app said signed out over a live Firebase session")
        #expect(await auth.currentUser() != nil)
        #expect(status.pending == nil, "a .signedOut was announced for a sign-out that did not happen")
    }

    /// Stage 9 / P2a's direction on the one path that deliberately keeps the session ALIVE. The
    /// provider loop is unconditional and sits ABOVE `dropSession()`'s Firebase sign-out, so a
    /// Keychain-refused sign-out still asks the SDKs to forget: the user asked to sign out, and
    /// forgetting an SDK session never leaves a credential alive (worst case, the next Google
    /// sign-in is interactive instead of silent). Nothing pinned that either way — the row above
    /// passes no providers — so the re-review flagged it as a behaviour change with no test.
    @Test func aFailedFirebaseSignOutStillForgetsTheProviderSdkSession() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let google = FakeOAuthProvider()
        let status = AccountStatusCenter()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  status: status, providers: [google])
        await session.refresh()
        #expect(session.state.me != nil)

        auth.nextError = .unknown
        session.signOut()

        #expect(google.signOutCount == 1,
                "a Keychain-refused sign-out left the provider SDK session alive")
        #expect(session.state != .signedOut, "the app said signed out over a live Firebase session")
        #expect(await auth.currentUser() != nil)
        #expect(status.pending == nil, "a .signedOut was announced for a sign-out that did not happen")
    }

    // MARK: - Stage 8 / S7 + Stage 9 / P2a: the age-ineligible teardown

    /// The pair `AgeIneligibleScreen.acknowledge()` and `ProfileViewModel
    /// .confirmAgeIneligibleSignOut()` each used to spell out — delete the credential the server has
    /// permanently refused, THEN drop the session — folded into one method, run once.
    @Test func theAgeIneligibleTeardownDeletesTheCredentialThenDropsTheSession() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let google = FakeOAuthProvider()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  providers: [google])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        await session.terminateAgeIneligible()

        #expect(auth.operations == [.deleteUser],
                "the credential the server permanently refused outlived the verdict")
        #expect(session.state == .signedOut)
        #expect(google.signOutCount == 1)
    }

    /// Stage 9 / P2a, the half that is not about the provider SDK. `auth.deleteUser()` fires the
    /// Firebase listener across its own suspension, so `start()`'s stream arm can reach `.signedOut`
    /// before the teardown does — and `signOut()`'s guard then skipped the announcement entirely, so
    /// the profile path told the per-account holders nothing. Forced here rather than raced: the
    /// listener has ALREADY won when the teardown runs.
    @Test func theAgeIneligibleTeardownAnnouncesSignedOutEvenWhenTheListenerWonTheRace() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let status = AccountStatusCenter()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  status: status)
        let running = try await signedIn(auth, session)
        defer { running.cancel() }
        try auth.signOut()
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }
        #expect(session.state == .signedOut)

        await session.terminateAgeIneligible()
        // `AccountStatusCenter.post` hops to the main actor, so the event lands a turn later.
        for _ in 0..<500 where status.pending == nil { await Task.yield() }

        let terminal = status.consume()
        #expect(terminal?.event == .signedOut,
                "the terminal announcement depended on who got to .signedOut first")
        // Task 33 / cold review: unattributed, for the reason above — this one posts after
        // `dropSession()` has already cleared `user`, so a uid could only ever be a stale one.
        #expect(terminal?.uid == nil, "the age-ineligible teardown attributed its announcement")
    }

    // MARK: - Stage 4 / I1: the provider SDK's own session

    /// `GIDSignIn` keeps an access token AND a refresh token for the user's Google account in this
    /// app's Keychain, and nothing ever asked it to sign out — so that credential outlived both the
    /// user's own sign-out and the ruling-C13 device wipe, whose dialog says the account is gone.
    @Test func signingOutForgetsTheProviderSdkSession() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let google = FakeOAuthProvider()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  providers: [google])
        await session.refresh()

        session.signOut()

        #expect(google.signOutCount == 1, "the Google refresh token outlived the sign-out")
    }

    /// Stage 9 / P2a. `dropSession()` opened with `guard state != .signedOut`, and the provider
    /// sign-out sat INSIDE it — so every path where something else reached `.signedOut` first
    /// returned before asking the SDK to forget. `performDeletion` awaits `auth.deleteUser()`,
    /// which fires the Firebase listener across that suspension; the blocked/deleted refusal path
    /// is the non-racy version of the same thing. Google's Keychain refresh token survived both.
    @Test func aListenerFirstSignOutStillForgetsTheProviderSdkSession() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let google = FakeOAuthProvider()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  providers: [google])
        let running = try await signedIn(auth, session)
        defer { running.cancel() }

        // The listener wins: `start()`'s stream arm is what writes `.signedOut`, with nothing
        // having gone through `dropSession()`.
        try auth.signOut()
        for _ in 0..<500 where session.state != .signedOut { await Task.yield() }
        #expect(session.state == .signedOut)

        session.signOut()

        // AT LEAST once. Since the Part B gate the listener arm and `dropSession()` share one
        // `tearDown()`, and a drop the listener already performed costs one extra idempotent
        // call — one spelling of the teardown is worth more than an exact count here.
        #expect(google.signOutCount >= 1,
                "the Google refresh token outlived a sign-out the Firebase listener got to first")
    }

    /// Stage 9 / P2b. The `.active` scene-phase hook refreshed unconditionally, and `AccountClient
    /// .me()` has no token guard — so a signed-out user's every return to the foreground sent TWO
    /// unsigned `GET /api/account/me` (the 401, then `BearerRetry`'s re-send with `token(true)`
    /// nil) and, at `maxAttempts: 1`, ended on `.failed`: an error banner over a guest.
    @Test func aGuestForegroundRefreshSendsNothing() async {
        let transport = ScriptedTransport([.json(200, Self.meJSON)])
        let session = makeSession(auth: FakeAuthClient(state: .signedOut), transport: transport)

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(transport.sent.isEmpty, "a guest foreground asked the backend who it was")
        #expect(session.state == .signedOut, "a guest was left on an error state by its own foreground")
    }

    @Test func theDeletionPathAlsoForgetsTheProviderSdkSession() async throws {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let google = FakeOAuthProvider()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  providers: [google])
        await session.refresh()

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(google.signOutCount == 1, "the Google refresh token outlived the device wipe")
    }
}
