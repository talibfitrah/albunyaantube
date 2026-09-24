import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Owner 2026-09-24: an offline launch routes on the last `/me` this device saw for the account
/// Firebase still holds, and the sign-in wall always means signed out (Android `SplashFragment` D12).
@Suite(.perTest) @MainActor
struct OfflineLaunchTests {

    private static let base = URL(string: "https://api.fitrah.test/")!
    private static let meJSON = #"{"uid":"fake-uid","email":"student@fitrah.test","status":"active","role":"user"}"#
    /// A fresh one per use: `ScriptedTransport` consumes a scripted error when it throws it.
    private nonisolated static func offline() -> HTTPResponse { .failing(URLError(.notConnectedToInternet)) }

    private static func me(_ uid: String = "fake-uid", _ status: AccountStatus = .active) -> AccountMe {
        AccountMe(uid: uid, email: "student@fitrah.test", displayName: "Aisha", dateOfBirth: "2001-04-09",
                  phoneNumber: nil, status: status, role: "user")
    }

    private let records = AccountRecordStore(
        url: FileManager.default.temporaryDirectory.appending(path: "OfflineLaunchTests-\(UUID().uuidString)/account.json"))

    /// A cold start with Firebase already holding `fake-uid`, run until the launch round settles.
    private func launch(_ responses: [HTTPResponse],
                        auth: FakeAuthClient = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)))
        async -> (session: AccountSession, auth: FakeAuthClient, running: Task<Void, Never>) {
        let session = AccountSession(
            auth: auth,
            account: AccountClient(transport: ScriptedTransport(responses), baseURL: Self.base,
                                   deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil }, records: records)
        let running = Task { await session.start() }
        let deadline = ContinuousClock.now + .seconds(10)
        while session.state == .signedOut || session.state == .loading, ContinuousClock.now < deadline {
            await Task.yield()
        }
        for _ in 0..<200 { await Task.yield() }   // the drop's own `.signedOut` reaching `start()`
        return (session, auth, running)
    }

    private func outcome(_ session: AccountSession) -> SplashOutcome {
        RootView.outcome(onboardingCompleted: true, session: session)
    }

    @Test(arguments: [offline(), .json(503, "{}"), .json(200, "not json"), .json(429, "{}"), .json(408, "{}"),
                      .json(403, "{}"), .json(404, "{}")])
    func anOfflineColdStartWithAPersistedActiveRecordLandsOnMain(failure: HTTPResponse) async {
        records.save(Self.me(), uid: "fake-uid")
        let (session, _, running) = await launch([failure])
        defer { running.cancel() }

        #expect(outcome(session).destination == .main, "landed on \(outcome(session)) with \(session.state)")
        #expect(session.user?.uid == "fake-uid")
    }

    @Test func aPersistedBlockedRecordLandsOnTheWallWithTheAlert() async {
        records.save(Self.me("fake-uid", .blocked), uid: "fake-uid")
        let (session, _, running) = await launch([Self.offline()])
        defer { running.cancel() }

        #expect(outcome(session) == SplashOutcome(destination: .signIn, alert: .blocked))
    }

    @Test func noRecordOfflineLandsOnTheWallSignedOutWithTheReason() async {
        let (session, auth, running) = await launch([Self.offline()])
        defer { running.cancel() }

        #expect(outcome(session).destination == .signIn)
        #expect(session.user == nil, "the wall held a live session")
        #expect(await auth.currentUser() == nil, "Firebase still holds the account behind the wall")
        #expect(session.state == .failed(code: nil, message: String(localized: "auth_error_network")),
                "the wall lost the reason it is up")
    }

    @Test func aRejectedSessionNeverReachesForTheRecord() async {
        records.save(Self.me(), uid: "fake-uid")
        let (session, auth, running) = await launch([.json(401, "{}"), .json(401, "{}")])
        defer { running.cancel() }

        #expect(session.state.me == nil, "a 401 was answered from the offline record")
        #expect(outcome(session).destination == .signIn)
        #expect(await auth.currentUser() == nil)
    }

    @Test func anotherAccountsRecordIsNeverRead() async {
        records.save(Self.me("uid-a"), uid: "uid-a")
        #expect(records.load(uid: "fake-uid") == nil)

        let (session, _, running) = await launch([Self.offline()])
        defer { running.cancel() }

        #expect(session.state.me == nil, "fake-uid launched on uid-a's record")
        #expect(outcome(session).destination == .signIn)
    }

    @Test func aSuccessfulMeOverwritesTheRecord() async {
        records.save(Self.me("fake-uid", .pendingProfile), uid: "fake-uid")
        let (_, _, running) = await launch([.json(200, Self.meJSON)])
        defer { running.cancel() }

        #expect(records.load(uid: "fake-uid")?.status == .active)
    }

    @Test func signingOutRemovesTheRecord() async {
        let (session, _, running) = await launch([.json(200, Self.meJSON)])
        defer { running.cancel() }
        #expect(records.load(uid: "fake-uid") != nil, "the precondition: a successful /me persisted it")

        session.signOut()

        #expect(records.load(uid: "fake-uid") == nil)
    }

    @Test func deletingTheAccountRemovesTheRecord() async {
        let (session, _, running) = await launch([.json(200, Self.meJSON)])
        defer { running.cancel() }
        #expect(records.load(uid: "fake-uid") != nil)

        await session.handleDeletion().value

        #expect(records.load(uid: "fake-uid") == nil)
    }

    @Test func bothWipesRemoveTheRecordTheyOwe() async {
        let defaults = UserDefaults(suiteName: "OfflineLaunchTests.\(UUID().uuidString)")!
        let wiper = LocalAccountWiper(offline: SpyOfflineManager(), stores: [],
                                      modelContainer: AppContainer.makeModelContainer(inMemory: true),
                                      searchHistory: UserDefaultsSearchHistoryStore(defaults: defaults),
                                      defaults: defaults, records: records)

        records.save(Self.me("uid-a"), uid: "uid-a")
        _ = await wiper.wipeRows(of: "uid-b")
        #expect(records.load(uid: "uid-a") != nil, "a by-uid wipe took somebody else's record")
        _ = await wiper.wipeRows(of: "uid-a")
        #expect(records.load(uid: "uid-a") == nil)

        records.save(Self.me("uid-a"), uid: "uid-a")
        _ = await wiper.wipe(unlessTakenOver: { false })
        #expect(records.load(uid: "uid-a") == nil)
    }

    /// P0 (Android review): a user blocked or soft-deleted server-side has revoked refresh tokens,
    /// and once the ID token expires the backend answers `/me` with a plain 401 — never the 403
    /// envelope. A refusal must end the session and the offline record with it, or a blocked user
    /// keeps content access forever. Only the server being unreachable or broken keeps it.
    @Test(arguments: [401])
    func aRefusedRefreshOfALoadedAccountSignsOutAndClearsTheRecord(status: Int) async {
        let (session, auth, running) = await launch([.json(200, Self.meJSON), .json(status, "{}")])
        defer { running.cancel() }
        #expect(session.state.me != nil && records.load(uid: "fake-uid") != nil, "the precondition")

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(session.state.me == nil, "a \(status) kept the account on screen")
        #expect(session.user == nil)
        #expect(await auth.currentUser() == nil, "Firebase still holds a session the server refused")
        #expect(records.load(uid: "fake-uid") == nil, "the next offline launch would restore it")
        #expect(outcome(session).destination == .signIn)
    }

    /// The same refusal after an offline launch restored the record: the reconnect's `/me` says 401.
    @Test func aRefusedReconnectAfterAnOfflineLaunchSignsOutAndClearsTheRecord() async {
        records.save(Self.me(), uid: "fake-uid")
        let (session, auth, running) = await launch([Self.offline(), .json(401, "{}")])
        defer { running.cancel() }
        #expect(outcome(session).destination == .main, "the precondition: launched on the record")

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(session.state.me == nil)
        #expect(await auth.currentUser() == nil)
        #expect(records.load(uid: "fake-uid") == nil)
    }

    /// The foreground and the connectivity return both re-ask `/me` after an offline launch, and
    /// the session coalesces them into ONE request whose answer replaces the restored record.
    @Test func aReconnectAndAForegroundTogetherSendOneMe() async {
        records.save(Self.me("fake-uid", .pendingProfile), uid: "fake-uid")
        let gate = Gate()
        let transport = ScriptedTransport([Self.offline(), .json(200, Self.meJSON)],
                                          park: { if $0 == 2 { await gate.block() } })
        let session = AccountSession(
            auth: FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser)),
            account: AccountClient(transport: transport,
                                   baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil }, records: records)
        let running = Task { await session.start() }
        defer { running.cancel() }
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        #expect(session.state.me?.status == .pendingProfile, "the precondition: launched on the record")

        let foreground = Task { await session.refreshIfSignedIn(maxAttempts: 1) }
        await gate.waitUntilBlocked()
        let reconnect = Task { await session.refreshIfSignedIn(maxAttempts: 1) }
        for _ in 0..<50 { await Task.yield() }
        await gate.release()
        await foreground.value
        await reconnect.value

        #expect(transport.sent.count == 2, "the launch plus ONE re-check, not one per trigger")
        #expect(session.state.me?.status == .active, "the server's answer did not replace the record")
    }

    // MARK: - Review round: only a SIGNED 401 is a verdict

    /// `/me` through the real signing transport, over a fixture Firebase.
    private func signedLaunch(_ responses: [HTTPResponse], auth: FakeAuthClient)
        async -> (session: AccountSession, running: Task<Void, Never>) {
        let transport = AuthorizedTransport(
            base: ScriptedTransport(responses), apiHost: Self.base.host() ?? "", tokens: auth,
            onStatusEvent: { _, _ in }, refreshRefusal: { await auth.refreshRefusal(signedFor: $0) },
            currentUid: { await auth.currentUser()?.uid })
        let session = AccountSession(
            auth: auth, account: AccountClient(transport: transport, baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil }, records: records)
        let running = Task { await session.start() }
        for _ in 0..<500 where session.state.me == nil { await Task.yield() }
        return (session, running)
    }

    /// P1: a token older than an hour on a flaky network. The mint fails, the request goes out
    /// unsigned, the backend says 401 — about the missing bearer, not about the account.
    @Test func anUnsignedFourOhOneAfterANetworkMintFailureKeepsTheRecord() async {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let (session, running) = await signedLaunch([.json(200, Self.meJSON), .json(401, "{}")], auth: auth)
        defer { running.cancel() }
        #expect(session.state.me != nil, "the precondition")

        auth.mintFailsOnNetwork = true
        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(session.state.me != nil, "a network mint failure signed the user out")
        #expect(await auth.currentUser() != nil)
        #expect(records.load(uid: "fake-uid") != nil)
    }

    @Test func aSignedFourOhOneSignsOut() async {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let (session, running) = await signedLaunch([.json(200, Self.meJSON), .json(401, "{}"), .json(401, "{}")],
                                                    auth: auth)
        defer { running.cancel() }

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(session.state.me == nil)
        #expect(await auth.currentUser() == nil)
        #expect(records.load(uid: "fake-uid") == nil)
    }

    /// Firebase's own `signOutIfTokenIsInvalid` removes a disabled user inside the mint.
    @Test func aDisabledUserMintFailureEndsSignedOut() async {
        let auth = FakeAuthClient(state: .signedIn(FakeAuthClient.defaultUser))
        let (session, running) = await signedLaunch([.json(200, Self.meJSON), .json(401, "{}")], auth: auth)
        defer { running.cancel() }

        auth.expiredTokenRefusal = .userDisabled
        await session.refreshIfSignedIn(maxAttempts: 1)
        for _ in 0..<200 { await Task.yield() }

        #expect(session.user == nil)
        #expect(session.state.me == nil)
        #expect(records.load(uid: "fake-uid") == nil)
    }

    // MARK: - Review round: the record never outlives its account

    @Test func anUndecodableRecordIsClearedForAnyUid() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "OfflineLaunchTests-\(UUID().uuidString)/account.json")
        try FileManager.default.createDirectory(at: url.deletingLastPathComponent(), withIntermediateDirectories: true)
        try Data(#"{"uid":"uid-a","old":"format"}"#.utf8).write(to: url)

        AccountRecordStore(url: url).clear(uid: "uid-a")

        #expect(!FileManager.default.fileExists(atPath: url.path()), "an old-format record survived its account's wipe")
    }

    @Test func aTakenOverDeviceWipeStillClearsTheDepartingRecord() async {
        let defaults = UserDefaults(suiteName: "OfflineLaunchTests.\(UUID().uuidString)")!
        let wiper = LocalAccountWiper(offline: SpyOfflineManager(), stores: [],
                                      modelContainer: AppContainer.makeModelContainer(inMemory: true),
                                      searchHistory: UserDefaultsSearchHistoryStore(defaults: defaults),
                                      defaults: defaults, records: records)
        records.save(Self.me("uid-a"), uid: "uid-a")

        _ = await wiper.wipe(unlessTakenOver: { true })

        #expect(records.load(uid: "uid-a") == nil)
    }

    /// P3: a refused Firebase sign-out (the under-13 teardown included) must not keep the PII.
    @Test func aRefusedSignOutStillClearsTheRecord() async {
        let (session, auth, running) = await launch([.json(200, Self.meJSON)])
        defer { running.cancel() }
        auth.nextError = .unknown

        session.signOut()

        #expect(session.user != nil, "the precondition: Firebase refused")
        #expect(records.load(uid: "fake-uid") == nil)
    }

    @Test func aSignedOutStreamClearsTheRecordEvenWithNoUser() async {
        records.save(Self.me(), uid: "fake-uid")
        let session = AccountSession(
            auth: FakeAuthClient(state: .signedOut),
            account: AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil }, records: records)
        let running = Task { await session.start() }
        defer { running.cancel() }
        for _ in 0..<200 { await Task.yield() }

        #expect(records.load(uid: "fake-uid") == nil)
    }

    @Test func theSavedRecordIsProtectedAndOutOfBackups() throws {
        let url = FileManager.default.temporaryDirectory.appending(path: "OfflineLaunchTests-\(UUID().uuidString)/account.json")
        AccountRecordStore(url: url).save(Self.me(), uid: "fake-uid")

        // The simulator has no Data Protection and reports no class at all (probed: nil), so the
        // protection half can only be asserted on a device.
        #if !targetEnvironment(simulator)
        let protection = try FileManager.default.attributesOfItem(atPath: url.path())[.protectionKey] as? FileProtectionType
        #expect(protection == .completeUntilFirstUserAuthentication)
        #endif
        #expect(try url.resourceValues(forKeys: [.isExcludedFromBackupKey]).isExcludedFromBackup == true)
    }

    /// Final round 1: before the first unlock Firebase reports `.signedOut` for a surviving session.
    @Test func aSignedOutReportWithoutProtectedDataKeepsTheRecord() async {
        records.save(Self.me(), uid: "fake-uid")
        let session = AccountSession(
            auth: FakeAuthClient(state: .signedOut),
            account: AccountClient(transport: ScriptedTransport([]), baseURL: Self.base, deviceId: DeviceId(value: "dev-1")),
            stores: [], status: AccountStatusCenter(), sleep: { _ in }, wipe: { _ in nil }, records: records,
            protectedDataAvailable: { false })
        let running = Task { await session.start() }
        defer { running.cancel() }
        for _ in 0..<200 { await Task.yield() }

        #expect(records.load(uid: "fake-uid") != nil, "a locked-device relaunch deleted the record")
    }

    /// Final round 2: only a restored (or failed) account needs the reconnect re-check.
    @Test func aRestoredAccountNeedsARecheckUntilMeAnswers() async {
        records.save(Self.me(), uid: "fake-uid")
        let (session, _, running) = await launch([Self.offline(), .json(200, Self.meJSON)])
        defer { running.cancel() }
        #expect(session.needsRecheck, "a launch on the offline record was not marked for a re-check")

        await session.refreshIfSignedIn(maxAttempts: 1)

        #expect(!session.needsRecheck)
    }

    /// A reconnect with an account `/me` already confirmed sends nothing.
    @Test func aReconnectWithAConfirmedAccountSendsNoMe() async throws {
        let auth = FakeAuthClient(state: .signedOut)
        let container = AppContainer.fake(defaults: UserDefaults(suiteName: "OfflineLaunchTests.\(UUID().uuidString)")!,
                                          auth: auth)
        let transport = try #require(container.authorizedTransport as? ScriptedTransport)
        let running = Task { await container.session.start() }
        defer { running.cancel() }
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        for _ in 0..<500 where container.session.state.me == nil { await Task.yield() }
        let before = transport.sent.count

        container.connectivityChanged(isOnline: true)
        for _ in 0..<200 { await Task.yield() }

        #expect(transport.sent.count == before, "a reconnect re-asked /me for an account already confirmed")
    }
}
