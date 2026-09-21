import FitrahAPI
import Foundation
import Network
import SwiftData
import Synchronization
import Testing
@testable import FitrahTube

/// Task 24: the five sites that call `SyncManager`, each pinned against a recording seam.
///
/// The manager itself is Task 23's subject; nothing here re-tests a drain. What is testable ONLY
/// here is the wiring: which uid a sign-in binds and when, that the one teardown path unbinds, that
/// a local write asks for a push exactly once, that a burst of writes is ONE drain rather than one
/// per write, and that a guest, a `/me` still in flight and a terminal verdict being handled all
/// produce no trigger at all.
@Suite(.perTest)
struct SyncTriggerTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#
    private static let uid = "fake-uid"

    /// The seam. An `actor` because `SyncTriggering` is what the real manager conforms to, so the
    /// double must cross the same isolation boundary the wiring does.
    ///
    /// `gate` parks the FIRST call only: the `Gate` rendezvous holds one blocker, and a second
    /// blocker would strand the first's continuation and hang the suite instead of failing it. One
    /// parked call is exactly what both tests using it want to observe.
    actor RecordingSync: SyncTriggering {
        enum Call: Equatable, Sendable { case bind(String), unbind, push(String), syncNow(String) }
        private(set) var calls: [Call] = []
        private var gate: Gate?
        private var parked = false

        init(gate: Gate? = nil) { self.gate = gate }

        func clear() { calls = [] }

        func bind(uid: String) async { calls.append(.bind(uid)); await parkOnce() }
        func unbind() async { calls.append(.unbind) }
        func pushDirty(uid: String) async { calls.append(.push(uid)); await parkOnce() }
        func syncNow(uid: String) async { calls.append(.syncNow(uid)) }

        private func parkOnce() async {
            guard let gate, !parked else { return }
            parked = true
            await gate.block()
        }
    }

    /// A `Gate` that can be entered only ONCE. The rendezvous holds a single blocker, so a retry
    /// ladder that parks on it twice would strand the first continuation and hang the suite rather
    /// than fail it.
    final class OneShotGate: Sendable {
        let gate = Gate()
        private let used = Mutex(false)

        func parkOnce() async {
            let first = used.withLock { flag -> Bool in
                if flag { return false }
                flag = true
                return true
            }
            if first { await gate.block() }
        }
    }

    /// Bounded `Task.yield()` loops, never a sleep — this suite's whole point is that no trigger
    /// needs a clock. 500 is `AccountSessionTests`' own bound.
    private func settle(until condition: @escaping () async -> Bool) async {
        for _ in 0..<500 {
            if await condition() { return }
            await Task.yield()
        }
    }

    private func drain() async { for _ in 0..<200 { await Task.yield() } }

    private func makeSession(auth: FakeAuthClient, transport: ScriptedTransport,
                             sync: RecordingSync,
                             wipe: @escaping @MainActor @Sendable (() -> Bool) async -> Error? = { _ in nil },
                             providers: [any OAuthSignInProvider] = [])
        -> AccountSession {
        AccountSession(
            auth: auth,
            account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: wipe,
            providers: providers, sync: sync)
    }

    @discardableResult
    private func signIn(_ auth: FakeAuthClient, _ session: AccountSession) async throws -> Task<Void, Never> {
        let running = Task { await session.start() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await settle { await MainActor.run { session.state.me != nil } }
        return running
    }

    // MARK: - Sign-in binds

    /// The uid is the ACCOUNT's, and it is bound only once `/me` has landed. Binding on the
    /// Firebase identity alone would tag every anon row to a uid the backend has no record for yet.
    @MainActor @Test func signInBindsWithTheAccountUidOnceMeHasLanded() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        let running = Task { await session.start() }
        defer { running.cancel() }

        await drain()
        #expect(await sync.calls.isEmpty)

        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await settle { await !sync.calls.isEmpty }
        #expect(await sync.calls == [.bind(Self.uid)])
    }

    /// The ACCOUNT's uid, which is not the same field as the Firebase user's -- `/me` here answers
    /// for a different one on purpose. Binding on `user?.uid` would tag every anon row to an
    /// identity the backend has no record for, and the merge would then push those rows under it.
    @MainActor @Test func theBoundUidIsTheAccountsNotTheFirebaseUsers() async throws {
        let accountJSON = #"{"uid":"account-uid","email":"student@fitrah.test","status":"active","role":"user"}"#
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, accountJSON)]), sync: sync)
        let running = Task { await session.start() }
        defer { running.cancel() }

        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await settle { await !sync.calls.isEmpty }

        #expect(session.user?.uid == "fake-uid")
        #expect(await sync.calls == [.bind("account-uid")])
        #expect(session.syncableUid == "account-uid")
    }

    /// `SplashFragment.kt:129-141`: the bind runs in its own coroutine so the route decision never
    /// waits on a merge + pull + push. Held mid-`bind` by the `Gate`, the session is already
    /// `.loaded` and every reader of it is live.
    @MainActor @Test func theSessionLandsLoadedWhileTheBindIsStillInFlight() async throws {
        let gate = Gate()
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync(gate: gate)
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        let running = Task { await session.start() }
        defer { running.cancel() }

        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await gate.waitUntilBlocked()

        #expect(session.state.me?.uid == Self.uid)
        #expect(await sync.calls == [.bind(Self.uid)])
        await gate.release()
    }

    /// `refreshIfSignedIn` runs on EVERY foreground, so a bind per `/me` would be a full merge +
    /// pull + push per foreground — and the ≥15 min spacing on the foreground trigger would mean
    /// nothing. The bind belongs to the identity, not to the request.
    @MainActor @Test func aSecondMeForTheSameAccountNeverRebinds() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let transport = ScriptedTransport([.json(200, Self.meJSON), .json(200, Self.meJSON)])
        let session = makeSession(auth: auth, transport: transport, sync: sync)
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        await settle { await !sync.calls.isEmpty }

        await session.refreshIfSignedIn(maxAttempts: 1)
        await drain()

        #expect(await sync.calls == [.bind(Self.uid)])
    }

    // MARK: - Sign-out and deletion unbind

    @MainActor @Test func signOutUnbindsThroughTheOneTeardownPath() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        await settle { await !sync.calls.isEmpty }
        await sync.clear()

        session.signOut()
        await settle { await !sync.calls.isEmpty }

        #expect(await sync.calls == [.unbind])
    }

    /// Part B gate, stage 4 S2. A FIREBASE-initiated sign-out (a refused forced mint force-signs
    /// the user out) reaches the session through the auth stream, not through `signOut()`. That
    /// arm used to cancel the round only: the provider SDKs kept their sessions for the next
    /// account, and an armed push retry was free to drain A's rows under B's bearer.
    @MainActor @Test func aFirebaseInitiatedSignOutUnbindsAndForgetsTheProvidersToo() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let provider = FakeOAuthProvider()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  sync: sync, providers: [provider])
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        await settle { await !sync.calls.isEmpty }
        await sync.clear()

        try auth.signOut()                       // Firebase's own sign-out, NOT `session.signOut()`
        await settle { await !sync.calls.isEmpty }

        #expect(await sync.calls == [.unbind])
        #expect(provider.signOutCount == 1)
        #expect(session.state == .signedOut)
    }

    /// Part B gate, stage 4 S9 / Codex 7. The deletion's unbind is AWAITED before the wipe:
    /// `SyncManager.unbind()` queues behind a pull already in flight, and a page landing after
    /// the wipe would re-insert the rows the server just erased.
    @MainActor @Test func aDeletionWaitsForTheUnbindBeforeItWipes() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let unboundBeforeWipe = Mutex<Bool?>(nil)
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  sync: sync, wipe: { _ in
                                      let seen = await sync.calls.contains(.unbind)
                                      unboundBeforeWipe.withLock { $0 = seen }
                                      return nil
                                  })
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        await settle { await !sync.calls.isEmpty }
        await sync.clear()

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(unboundBeforeWipe.withLock { $0 } == true, "the wipe ran before sync had let go")
        #expect(await sync.calls == [.unbind])
    }

    /// The SAME teardown path (`dropSession`), which is why the deletion needs no wiring of its
    /// own: a queued push retry that outlived the account would otherwise fire under whatever
    /// bearer is current next.
    @MainActor @Test func aDeletionUnbindsThroughTheSameTeardownPath() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        await settle { await !sync.calls.isEmpty }
        await sync.clear()

        await session.handleDeletion(deletingFirebaseUser: false).value
        await settle { await !sync.calls.isEmpty }

        #expect(await sync.calls == [.unbind])
    }

    // MARK: - The one guard every trigger consults

    @MainActor @Test func aGuestHasNoUidToSyncAndALoadedAccountDoes() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        #expect(session.syncableUid == nil, "a guest has nothing to sync")

        let running = try await signIn(auth, session)
        defer { running.cancel() }
        #expect(session.syncableUid == Self.uid)
    }

    /// What the wipe saw. `session` is assigned after construction for the same reason
    /// `DeleteAccountTests.WipeSpy` does it: the closure the session is built with cannot name it.
    @MainActor final class SyncableAtWipe {
        var session: AccountSession?
        private(set) var observed: [String?] = []
        private(set) var accountWasStillLoaded = false

        func record() {
            accountWasStillLoaded = session?.state.me != nil
            observed.append(session?.syncableUid)
        }
    }

    /// The terminal window that MATTERS, and the only one a test can stand inside. The deletion
    /// wipe is DETACHED — it survives a foreground — and `state` is still `.loaded` for the whole
    /// of it, because `dropSession()` runs after (`handleDeletion`'s "wipe BEFORE the sign-out").
    /// A pull started here would restore the very rows the wipe is deleting.
    @MainActor @Test func aDeletionInFlightHasNoUidToSyncWhileTheAccountIsStillLoaded() async throws {
        let spy = SyncableAtWipe()
        let auth = FakeAuthClient(state: .signedOut)
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]),
                                  sync: RecordingSync(), wipe: { _ in spy.record(); return nil })
        spy.session = session
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        #expect(session.syncableUid == Self.uid)

        await session.handleDeletion(deletingFirebaseUser: false).value

        #expect(spy.accountWasStillLoaded, "the wipe runs while the account is still loaded")
        #expect(spy.observed == [nil], "a deletion in flight is terminal")
        #expect(session.syncableUid == nil)
    }

    /// `AlBunyaanApplication.kt:167-185`'s case, and the reason NOTHING waits for it here: a
    /// foreground that finds `/me` in flight is skipped outright. Android suspended on the state
    /// flow instead and accumulated one waiter per foreground; the iOS sign-in path binds the
    /// moment `/me` lands, so the run this would have waited for happens anyway.
    @MainActor @Test func aMeStillInFlightHasNoUidToSyncAndParksNoWaiter() async throws {
        let parking = OneShotGate()
        let auth = FakeAuthClient(state: .signedOut)
        let session = AccountSession(
            auth: auth,
            // An exhausted queue is a `.network` failure, so the round enters its retry sleep --
            // which is where it is held, with `state` standing at `.loading`.
            account: AccountClient(transport: ScriptedTransport([]), baseURL: Self.base,
                                   deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(),
            sleep: { _ in await parking.parkOnce() }, wipe: { _ in nil }, sync: RecordingSync())
        let running = Task { await session.start() }
        defer { running.cancel() }

        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await parking.gate.waitUntilBlocked()

        #expect(session.state == .loading)
        #expect(session.syncableUid == nil)
        await parking.gate.release()
    }

    /// The other terminal arm, which the deletion latch does not cover: the verdict is up and the
    /// session is dropped only after Firebase answers.
    @MainActor @Test func anAgeIneligibleVerdictLeavesNoUidToSync() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let sync = RecordingSync()
        let session = makeSession(auth: auth, transport: ScriptedTransport([.json(200, Self.meJSON)]), sync: sync)
        let running = try await signIn(auth, session)
        defer { running.cancel() }
        #expect(session.syncableUid == Self.uid)

        await session.terminateAgeIneligible()
        #expect(session.syncableUid == nil)
    }

    // MARK: - A local write pushes

    @MainActor private func container() -> ModelContainer { AppContainer.makeModelContainer(inMemory: true) }

    @MainActor final class Pushes {
        private(set) var uids: [String] = []
        func record(_ uid: String) { uids.append(uid) }
    }

    private func item(_ id: String) -> ContentItem {
        ContentItem(id: id, type: .video, title: "Lecture", category: nil, description: nil, thumbnailURL: nil,
                    durationSeconds: 300, uploadedDaysAgo: nil, viewCount: nil, channelTitle: "Channel",
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }

    /// Once per toggle, under the uid the ROW is tagged with (`FavoritesRepository.kt:189`). The
    /// row's own uid, not the session's: a write that lands while `/me` is still in flight is still
    /// owned by the identity `start()` already scoped the store to.
    @MainActor @Test func aFavoriteToggleAsksForOnePushPerToggleUnderTheRowsOwnUid() throws {
        let pushes = Pushes()
        let store = SwiftDataFavoritesStore(modelContainer: container(), onDirty: { pushes.record($0) })
        store.currentUserId = Self.uid

        try store.toggle(item("xc7keR2piUM"))
        try store.toggle(item("xc7keR2piUM"))

        #expect(pushes.uids == [Self.uid, Self.uid])
    }

    @MainActor @Test func aSubscriptionToggleAsksForOnePushPerToggle() throws {
        let pushes = Pushes()
        let store = SwiftDataSubscriptionsStore(modelContainer: container(), onDirty: { pushes.record($0) })
        store.currentUserId = Self.uid

        try store.toggle(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Alafasy", avatarURL: nil)
        try store.toggle(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Alafasy", avatarURL: nil)

        #expect(pushes.uids == [Self.uid, Self.uid])
    }

    @MainActor @Test func aSavedPlaylistToggleAsksForOnePushPerToggle() throws {
        let pushes = Pushes()
        let store = SwiftDataSavedPlaylistsStore(modelContainer: container(), onDirty: { pushes.record($0) })
        store.currentUserId = Self.uid

        try store.toggle(id: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Series", thumbnailURL: nil, itemCount: 4)
        try store.toggle(id: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Series", thumbnailURL: nil, itemCount: 4)

        #expect(pushes.uids == [Self.uid, Self.uid])
    }

    /// A GUEST write asks for nothing: `""` is the anon sentinel and there is no account to push to.
    @MainActor @Test func aGuestWriteAsksForNoPush() async throws {
        let recording = RecordingSync()
        let appContainer = AppContainer.fake(defaults: Self.isolatedDefaults(), sync: recording)

        appContainer.pushDirtySoon(uid: "")
        await drain()

        #expect(await recording.calls.isEmpty)
    }

    /// A private suite per test: `fake()`'s default "fitrahtube.fake" domain is shared, and
    /// `start()` redeems whatever deletion marker it finds there.
    private static func isolatedDefaults() -> UserDefaults {
        UserDefaults(suiteName: "sync-triggers.\(UUID().uuidString)") ?? .standard
    }

    // MARK: - Coalescing

    /// `SyncManager` SERIALISES but does not dedupe (Task 23), so five rapid toggles would queue
    /// five tasks, each waiting on the exclusion behind a network round trip, and the last four
    /// would drain rows the first already pushed. One drain runs, one follow-up covers whatever was
    /// dirtied while it ran, and that is all.
    @MainActor @Test func aBurstOfTogglesCoalescesIntoOneDrainAndOneFollowUp() async throws {
        let gate = Gate()
        let recording = RecordingSync(gate: gate)
        let appContainer = AppContainer.fake(defaults: Self.isolatedDefaults(), sync: recording)

        for _ in 0..<5 { appContainer.pushDirtySoon(uid: Self.uid) }
        await gate.waitUntilBlocked()
        #expect(await recording.calls == [.push(Self.uid)], "only one drain is ever in flight")

        await gate.release()
        await settle { await recording.calls.count == 2 }
        await drain()
        #expect(await recording.calls == [.push(Self.uid), .push(Self.uid)])
    }

    /// Task 24 review / M2: the follow-up carries the LATEST uid, not the first. Only reachable
    /// today across an account switch that dirties a row before the outgoing account's drain has
    /// finished; replaying the FIRST uid there would drain the wrong account's rows and leave the
    /// new one's dirty rows unpushed until something else asked. The ordering the old code relied
    /// on was never enforced by anything.
    @MainActor @Test func theCoalescedFollowUpCarriesTheLatestUidNotTheFirst() async throws {
        let gate = Gate()
        let recording = RecordingSync(gate: gate)
        let appContainer = AppContainer.fake(defaults: Self.isolatedDefaults(), sync: recording)

        appContainer.pushDirtySoon(uid: "uid-a")
        await gate.waitUntilBlocked()
        appContainer.pushDirtySoon(uid: "uid-b")
        appContainer.pushDirtySoon(uid: "uid-c")

        await gate.release()
        await settle { await recording.calls.count == 2 }
        await drain()
        #expect(await recording.calls == [.push("uid-a"), .push("uid-c")])
    }

    // MARK: - Connectivity

    /// `AlBunyaanApplication.kt:206-215`: `onAvailable` pushes, and nothing else does. An
    /// unsatisfied path has nothing useful to do with a dirty row.
    @MainActor @Test func aRestoredConnectionPushesAndAnUnsatisfiedPathPushesNothing() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let recording = RecordingSync()
        let appContainer = AppContainer.fake(defaults: Self.isolatedDefaults(), auth: auth, sync: recording)
        let running = Task { await appContainer.session.start() }
        defer { running.cancel() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await settle { await MainActor.run { appContainer.session.state.me != nil } }
        await settle { await !recording.calls.isEmpty }
        await recording.clear()

        appContainer.connectivityChanged(isOnline: NetworkMonitor.isOnline(for: .unsatisfied))
        await drain()
        #expect(await recording.calls.isEmpty)

        appContainer.connectivityChanged(isOnline: NetworkMonitor.isOnline(for: .satisfied))
        await settle { await !recording.calls.isEmpty }
        #expect(await recording.calls == [.push(Self.uid)])
    }

    // MARK: - Foreground

    /// The foreground rule, whole. `nil` uid — guest, `/me` in flight, terminal — never syncs; a
    /// signed-in foreground syncs on the SAME ≥15 min spacing decision the remote-config refresh
    /// uses, so a scene-phase flicker costs nothing.
    @Test func theForegroundRuleIsTheSameSpacingDecisionAndSkipsWhateverHasNoUid() {
        let now = Date(timeIntervalSince1970: 10_000)
        let spacing: TimeInterval = 15 * 60

        #expect(FitrahTubeApp.shouldSyncOnForeground(uid: nil, now: now, last: nil, spacing: spacing) == false)
        #expect(FitrahTubeApp.shouldSyncOnForeground(uid: Self.uid, now: now, last: nil, spacing: spacing))
        #expect(FitrahTubeApp.shouldSyncOnForeground(
            uid: Self.uid, now: now, last: now.addingTimeInterval(-60), spacing: spacing) == false)
        #expect(FitrahTubeApp.shouldSyncOnForeground(
            uid: Self.uid, now: now, last: now.addingTimeInterval(-spacing), spacing: spacing))
        #expect(FitrahTubeApp.shouldSyncOnForeground(
            uid: nil, now: now, last: now.addingTimeInterval(-spacing), spacing: spacing) == false)
    }
}
