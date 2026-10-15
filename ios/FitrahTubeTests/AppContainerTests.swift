import Foundation
import InnerTubeKit
import SwiftData
import SwiftUI
import Testing
import UIKit
@testable import FitrahTube

/// `.serialized` (Cubic R5-7): the background-events test swaps the process-global
/// `AppContainer.current` for a fixture container across its own awaits, and the cast test below
/// reads that same global — parallel MainActor tests interleave at exactly those suspensions.
@Suite(.perTest, .serialized)
struct AppContainerTests {

    /// Gate A-I1: a store SwiftData cannot open used to `preconditionFailure` on the launch path,
    /// i.e. a permanent crash loop with no recovery short of delete-and-reinstall. It must now
    /// delete the store and rebuild instead -- favorites are lost, the app is not.
    @Test func corruptStoreIsRecreatedInsteadOfTrapping() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        try Data("this is not a SQLite database".utf8).write(to: url)
        // Gate wave-2 W1: SQLite's sidecars are `<file>-shm`/`<file>-wal`, and recovery deleted
        // `<file>.shm`/`<file>.wal` instead, so the real stale WAL survived the rebuild. Seeded
        // here with a sentinel the recovery must not leave behind.
        let stale = Data("stale sidecar".utf8)
        let sidecars = ["-shm", "-wal"].map { URL(fileURLWithPath: url.path + $0) }
        for sidecar in sidecars { try stale.write(to: sidecar) }
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)

        // Usable, not merely non-nil: the recreated store must accept a write.
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "v1", title: "T", channelName: "C", thumbnailUrl: nil, durationSeconds: 1))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<FavoriteVideo>()) == 1)

        // A sidecar recreated by the rebuilt store is fine; the *stale* bytes surviving is not.
        for sidecar in sidecars {
            let survived = (try? Data(contentsOf: sidecar))?.starts(with: stale) ?? false
            #expect(!survived, "\(sidecar.lastPathComponent) survived the corrupt-store recovery")
        }
    }

    // MARK: - 1.0.3 R5 (L1): a store the device has not unlocked yet is never deleted

    /// Before the first unlock after a reboot, a background launch (a download-session event, say)
    /// cannot open the store: its class is "until first user authentication", and the
    /// open fails (SQLITE_AUTH). Two failed opens used to DELETE it as if corrupt — the Saved
    /// library, favorites, subscriptions. The simulator has no Data Protection, so a store that
    /// cannot open stands in for the locked one, and the seam reports protected data unavailable.
    @Test func aStoreThatCannotOpenWhileProtectedDataIsUnavailableIsKept() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        let bytes = Data("a locked store, unreadable here".utf8)
        try Self.writeLocked(bytes, to: url)
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url, protectedDataAvailable: { false })

        #expect(Self.unlockedBytes(url) == bytes, "the store was deleted while protected data was unavailable")
        // Fail soft: this launch runs on a usable stand-in, never a trap.
        let inMemory = container.configurations.allSatisfy { $0.isStoredInMemoryOnly }
        #expect(inMemory, "the fallback reopened the on-disk store")
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "v1", title: "T", channelName: "C", thumbnailUrl: nil, durationSeconds: 1))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<FavoriteVideo>()) == 1)
    }

    /// The simulator has no Data Protection, so a store the device has not unlocked yet is stood in
    /// for by one whose `open(2)` is refused the same way (`EACCES` for a file with no permissions;
    /// a locked one answers `EPERM`).
    private static func writeLocked(_ bytes: Data, to url: URL) throws {
        try bytes.write(to: url)
        try FileManager.default.setAttributes([.posixPermissions: 0], ofItemAtPath: url.path)
    }

    private static func unlockedBytes(_ url: URL) -> Data? {
        try? FileManager.default.setAttributes([.posixPermissions: 0o600], ofItemAtPath: url.path)
        return try? Data(contentsOf: url)
    }

    /// Stage 5 P2: ONE definition of "unreadable" for the store guard and the readiness gate. On an
    /// unlocked device the gate lets a refused store through, and the guard used to call the same
    /// store unreadable: an in-memory stand-in on every launch, nothing persisted, nothing repaired.
    /// With protected data available a store that will not open is corrupt, and is rebuilt.
    @Test func anUnlockedDeviceRebuildsAStoreItCannotOpen() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        try Self.writeLocked(Data("not a store".utf8), to: url)
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url, protectedDataAvailable: { true })

        #expect(container.configurations.allSatisfy { !$0.isStoredInMemoryOnly }, "the guard parked the store in memory")
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "v1", title: "T", channelName: "C", thumbnailUrl: nil, durationSeconds: 1))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<FavoriteVideo>()) == 1)
    }

    /// The store opens on first USE, never while the container is built: `FitrahTubeApp` builds it
    /// on every launch, one before the first unlock included, where an open can only fail.
    @Test func buildingTheContainerDoesNotOpenTheStore() {
        var opened = false
        let container = AppContainer(catalog: FakeCatalogClient(),
                                     userDefaults: UserDefaults(suiteName: "fitrahtube.lazy-store") ?? .standard,
                                     modelContainer: { opened = true; return AppContainer.makeModelContainer(inMemory: true) }(),
                                     apiBaseURL: URL(string: "https://api.fitrah.test/")!,
                                     gateTransport: FixedStatusTransport(status: 503))
        #expect(!opened, "the store opened while the container was built")
        _ = container.offlineStore
        #expect(opened)
    }

    /// A readability switch the tests flip (the store's `open(2)` answer, stood in for).
    @MainActor final class ReadableFlag { var value = false }

    private static func readiness(_ flag: ReadableFlag, _ center: NotificationCenter) -> StoreReadiness {
        StoreReadiness(isReadable: { flag.value }, center: center)
    }

    /// The retry: a launch before the first unlock waits for `protectedDataDidBecomeAvailable`
    /// until the store is readable, then goes ahead.
    @Test func theStoreWaitRetriesOnProtectedDataDidBecomeAvailable() async {
        let center = NotificationCenter(), flag = ReadableFlag()
        var done = false
        let waiting = Task {
            await Self.readiness(flag, center).wait()
            done = true
        }
        for _ in 0..<100 { await Task.yield() }
        #expect(!done, "it went ahead while the store was unreadable")

        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        for _ in 0..<100 { await Task.yield() }
        #expect(!done, "a notification alone is not a readable store")

        flag.value = true
        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        await waiting.value
        #expect(done)
    }

    /// Round 1 / M5: the unlock notification is not the only way back. A process suspended at the
    /// unlock may never see it, so the wait re-checks whenever the app becomes active as well.
    @Test func theStoreWaitRechecksWhenTheAppBecomesActive() async {
        let center = NotificationCenter(), flag = ReadableFlag()
        var done = false
        let waiting = Task {
            await Self.readiness(flag, center).wait()
            done = true
        }
        for _ in 0..<100 { await Task.yield() }
        #expect(!done)

        flag.value = true
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        for _ in 0..<500 where !done { await Task.yield() }
        #expect(done, "becoming active with a readable store did not end the wait")
        waiting.cancel()
    }

    /// The direction that could be wrong: a readable store never waits (every launch after the
    /// first unlock, locked or not — the store's class stays readable while the screen is locked).
    @Test func aReadableStoreNeverWaits() async {
        let flag = ReadableFlag()
        flag.value = true
        await Self.readiness(flag, NotificationCenter()).wait()
        let missing = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("no-store-\(UUID().uuidString)")
        #expect(AppContainer.storeIsReadable(missing, protectedDataAvailable: { false }), "a store not created yet is no reason to wait")
    }

    /// Stage 3c S3-2: the App body builds the gate on every pass (scene phase, connectivity), and
    /// the live check opened the SQLite file each time. Readable once is readable for the life of
    /// the process, so the check is asked until it first says yes and never after.
    @Test func theReadinessCheckStopsAskingOnceTheStoreIsReadable() {
        let flag = ReadableFlag()
        var probes = 0
        let readiness = StoreReadiness.once { probes += 1; return flag.value }
        #expect(!readiness.isReadable())
        #expect(!readiness.isReadable())
        flag.value = true
        #expect(readiness.isReadable())
        #expect(probes == 3)

        flag.value = false
        #expect(readiness.isReadable(), "readable once is readable for good")
        #expect(probes == 3, "the store was probed again after it was readable")
    }

    /// Stage 3c S3-1: a process that had to wait for the first unlock started Firebase before its
    /// Keychain could be read, and Firebase re-reads it only on `protectedDataDidBecomeAvailable`,
    /// which such a process may never be handed (the M5 premise). Its first "signed out" is stale
    /// even once protected data is available, so the session must not clear the offline record on
    /// it. A process that never waited keeps the rule as it was.
    @Test func aProcessThatWaitedForTheFirstUnlockKeepsTheRecordOnFirebasesSignedOut() async {
        let container = AppContainer.fake()
        let center = NotificationCenter(), flag = ReadableFlag()
        container.storeReadiness = Self.readiness(flag, center)
        #expect(container.signedOutMayClearRecord(), "a process that never waited trusts Firebase")

        let waiting = Task { await container.storeReadiness.wait() }
        for _ in 0..<100 { await Task.yield() }
        flag.value = true
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        await waiting.value

        #expect(!container.signedOutMayClearRecord(), "Firebase's stale signed-out would delete the record")
    }

    /// Counts one notification name on one center.
    @MainActor final class PostCounter {
        private(set) var count = 0
        private var token: NSObjectProtocol?
        init(_ name: Notification.Name, on center: NotificationCenter) {
            token = center.addObserver(forName: name, object: nil, queue: nil) { [weak self] _ in
                MainActor.assumeIsolated { self?.count += 1 }
            }
        }
    }

    /// Stage 7 r2 N1: Firebase re-reads its Keychain only on `protectedDataDidBecomeAvailable`, and
    /// a process suspended across the first unlock may never be handed it (the M5 premise). A wait
    /// that really blocked posts it ONCE when it ends, before the gate opens, so Firebase restores
    /// the user instead of leaving the sign-in wall up for the life of the process. Copies of one
    /// readiness share the once (the scene gate and the background hook can both be waiting).
    @Test func aWaitThatBlockedAnnouncesTheUnlockOnce() async {
        let center = NotificationCenter(), flag = ReadableFlag()
        let readiness = Self.readiness(flag, center)
        let posts = PostCounter(UIApplication.protectedDataDidBecomeAvailableNotification, on: center)
        let gate = Task { await readiness.wait() }
        let hook = Task { await readiness.wait() }
        for _ in 0..<100 { await Task.yield() }
        #expect(posts.count == 0, "announced while the store was still locked")

        flag.value = true
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)   // the M5 wake
        await gate.value
        await hook.value
        #expect(posts.count == 1)

        await readiness.wait()   // readable: no block, nothing more to say
        #expect(posts.count == 1)
    }

    /// The other direction: a readiness that never blocked (every launch after the first unlock)
    /// posts nothing, so the extra notification exists only in a process that waited.
    @Test func aWaitThatNeverBlockedAnnouncesNothing() async {
        let center = NotificationCenter(), flag = ReadableFlag()
        flag.value = true
        let posts = PostCounter(UIApplication.protectedDataDidBecomeAvailableNotification, on: center)
        await Self.readiness(flag, center).wait()
        #expect(posts.count == 0)
    }

    /// Our own observers ignore the extra post: the only one in the app is `StoreReadiness.wait()`'s,
    /// and it treats the notification as "check again", never as "readable". Another readiness still
    /// locked on the same center stays parked through the announcement.
    @Test func anotherWaitStillLockedIgnoresTheAnnouncement() async {
        let center = NotificationCenter(), unlocked = ReadableFlag(), locked = ReadableFlag()
        var lockedDone = false
        let parked = Task {
            await Self.readiness(locked, center).wait()
            lockedDone = true
        }
        let announcing = Task { await Self.readiness(unlocked, center).wait() }
        for _ in 0..<100 { await Task.yield() }
        unlocked.value = true
        center.post(name: UIApplication.didBecomeActiveNotification, object: nil)
        await announcing.value
        for _ in 0..<200 { await Task.yield() }
        #expect(!lockedDone, "the announcement was taken for a readable store")
        parked.cancel()
    }

    /// Stage 7 r2 N2: a prewarm can run `App.init` before the first unlock with no scene and no
    /// session event, so no wait ever blocks. The live container records it at construction: one
    /// `open(2)` of the store, refused with a data-protection error, means "started locked", and
    /// Firebase's first no-user "signed out" in that process must not clear the record.
    @Test func aContainerBuiltBeforeTheFirstUnlockNeverTrustsFirebasesSignedOut() throws {
        let locked = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        try Self.writeLocked(Data("locked".utf8), to: locked)
        defer { _ = Self.unlockedBytes(locked); try? FileManager.default.removeItem(at: locked) }
        let missing = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("no-store-\(UUID().uuidString)")

        let prewarmed = AppContainer.live(storeURL: locked)
        #expect(prewarmed.storeReadiness.startedLocked)
        #expect(!prewarmed.signedOutMayClearRecord(), "a prewarm before the unlock would delete the record")

        let normal = AppContainer.live(storeURL: missing)
        #expect(!normal.storeReadiness.startedLocked, "a store not created yet is not a locked one")
        #expect(normal.signedOutMayClearRecord())
    }

    /// Round 1 / I1: the SCENE path. Before the first unlock a scene can connect in the background
    /// (`AccountSession`'s locked-launch arm exists for exactly that), and everything under
    /// `RootView` opens the store. Nothing under the gate may render until the store is readable;
    /// then it renders on the real store.
    @Test func theSceneRendersNothingStoreBackedUntilTheStoreIsReadable() async throws {
        @MainActor final class Probe { var appeared = false }
        let center = NotificationCenter(), flag = ReadableFlag(), probe = Probe()
        let gated = Text("store-backed")
            .onAppear { probe.appeared = true }
            .modifier(StoreReadinessGate(readiness: Self.readiness(flag, center)))
        let scene = UIApplication.shared.connectedScenes.compactMap { $0 as? UIWindowScene }.first
        let window = scene.map(UIWindow.init(windowScene:)) ?? UIWindow()
        window.frame = CGRect(x: 0, y: 0, width: 390, height: 844)
        window.rootViewController = UIHostingController(rootView: gated)
        window.isHidden = false
        defer { window.isHidden = true }

        for _ in 0..<20 { try await Task.sleep(for: .milliseconds(20)) }
        #expect(!probe.appeared, "store-backed content rendered before the store was readable")

        flag.value = true
        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        // Stage 3c S3-4: a deadline (`OfflineLaunchTests.waitUntil`'s 10 s), not 100 fixed polls: a
        // hosted render can take longer than 2 s on a loaded machine with nothing wrong.
        let deadline = ContinuousClock.now + .seconds(10)
        while !probe.appeared, ContinuousClock.now < deadline { try await Task.sleep(for: .milliseconds(20)) }
        #expect(probe.appeared, "the content never rendered once the store was readable")
    }

    /// Round 1 / M3: the AppDelegate's background-session hook waits where it is hooked in, and
    /// reattaches (the first store use) only once the store is readable.
    @Test func theBackgroundRelaunchHookWaitsForAReadableStore() async throws {
        let previous = AppContainer.current
        defer { AppContainer.current = previous }
        let container = AppContainer.fake()
        let center = NotificationCenter(), flag = ReadableFlag()
        container.storeReadiness = Self.readiness(flag, center)
        AppContainer.current = container
        let before = await container.offlineManager.reattachCount

        AppDelegate().application(
            UIApplication.shared,
            handleEventsForBackgroundURLSession: ProgressiveEngine.backgroundSessionIdentifier) {}
        for _ in 0..<20 { try await Task.sleep(for: .milliseconds(10)) }
        #expect(await container.offlineManager.reattachCount == before, "reattached before the store was readable")

        flag.value = true
        center.post(name: UIApplication.protectedDataDidBecomeAvailableNotification, object: nil)
        var after = before
        for _ in 0..<2000 where after == before {
            try await Task.sleep(for: .milliseconds(1))
            after = await container.offlineManager.reattachCount
        }
        #expect(after > before, "the hook never reattached once the store was readable")
    }

    /// Round 1 / M3: the live container hands protected-data availability to the store guard. A
    /// store that fails to open while it reports unavailable is kept (the corrupt file stands in
    /// for the locked one, which the simulator cannot produce).
    @Test func theLiveContainerHandsProtectedDataToTheStoreGuard() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        let bytes = Data("a locked store, unreadable here".utf8)
        try Self.writeLocked(bytes, to: url)
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }

        let container = AppContainer.live(storeURL: url, protectedDataAvailable: { false })
        _ = container.offlineStore   // the first store use opens it

        #expect(Self.unlockedBytes(url) == bytes, "the live container deleted a store it could not read")
    }

    /// Cubic P1: a background-events relaunch calls the AppDelegate hook and renders NO scene, so
    /// RootView's `.task` — the only other builder of `offlineManager` — never runs; without this
    /// wiring the background session/delegate is never recreated, queued delegate events go
    /// undelivered, the chunk walk stalls, and the parked completion handler is never called. The
    /// hook must reach the App's one container (the `AppContainer.current` seam, set at
    /// `FitrahTubeApp.init` — which the test host's launch already ran) and schedule a `reattach()`.
    /// Asserted at flag level; the real background relaunch is device territory.
    ///
    /// R5-7: it used to drive the PROCESS container's real `ProgressiveEngine` (a real background
    /// session, `LiveStreamResolver` and `OfflineGateClient` behind it) over the shared fake store.
    /// The hook reads `AppContainer.current`, so pointing that at a fixture container for the
    /// test's duration proves the same wiring against a no-op engine and an empty store.
    @Test func theBackgroundSessionRelaunchHookReachesTheManagerAndSchedulesReattach() async throws {
        _ = try #require(AppContainer.current, "FitrahTubeApp.init must set AppContainer.current")
        let previous = AppContainer.current
        defer { AppContainer.current = previous }
        let container = AppContainer.fake()   // `isFixture`: a parked engine and resolver, no session
        AppContainer.current = container
        let before = await container.offlineManager.reattachCount
        AppDelegate().application(
            UIApplication.shared,
            handleEventsForBackgroundURLSession: ProgressiveEngine.backgroundSessionIdentifier) {}
        var after = before
        for _ in 0..<2000 where after == before {
            try? await Task.sleep(for: .milliseconds(1))
            after = await container.offlineManager.reattachCount
        }
        #expect(after > before, "the relaunch hook never scheduled a reattach")
    }

    /// The same hook, reached the way iOS reaches it: through the app's REAL delegate object
    /// (SwiftUI's adaptor, which Firebase's `GULAppDelegateSwizzler` proxies once Auth starts), by
    /// ObjC selector. A near-miss signature (`@MainActor @Sendable` completion) compiled and passed
    /// the direct call above but was never exported, so a background-events relaunch never
    /// rebuilt the session (Release warning "nearly matches optional requirement").
    @Test func theAppsRealDelegateForwardsTheBackgroundSessionHook() async throws {
        let delegate = try #require(UIApplication.shared.delegate)
        let previous = AppContainer.current
        defer { AppContainer.current = previous }
        let container = AppContainer.fake()
        AppContainer.current = container
        let before = await container.offlineManager.reattachCount
        delegate.application?(UIApplication.shared,
                              handleEventsForBackgroundURLSession: ProgressiveEngine.backgroundSessionIdentifier) {}
        var after = before
        for _ in 0..<2000 where after == before {
            try? await Task.sleep(for: .milliseconds(1))
            after = await container.offlineManager.reattachCount
        }
        #expect(after > before, "UIKit's delegate never reached AppDelegate's background-session hook")
    }

    /// Phase 3 Task 8: `GCKCastContext` is created exactly once, on launch, from
    /// `didFinishLaunchingWithOptions` — through the same `AppContainer.current` seam the
    /// background-events hook above uses (a unit test cannot drive the live `UIApplication`
    /// delegate, and nothing else in the app builds the controller). Asserted on the App's OWN
    /// launch, which the test host already performed: delete the hook and this goes red, because
    /// `castAvailable` is per-controller state that only `setUp()` writes. (Review Minor 8: this
    /// means a real `GCKCastContext` DOES exist in the unit-test host process — benign, since
    /// `startDiscoveryAfterFirstTapOnCastButton` stays at the SDK default, so no mDNS and no
    /// local-network prompt.)
    @Test func theLaunchHookCreatesTheCastContextThroughTheContainerSeam() throws {
        let container = try #require(AppContainer.current, "FitrahTubeApp.init must set AppContainer.current")
        #expect(container.castController.castAvailable,
                "AppDelegate.didFinishLaunchingWithOptions must call castController.setUp()")
    }

    /// R5-1: `fake()` passed `AppConfig.apiBaseURL` (Debug: `http://localhost:8080/`) to the REAL
    /// gate client, so with the documented dev backend running the launch sweep got a real 404 for
    /// every `-fitrah-seed-offline` row and deleted the whole screenshot fixture before the rig
    /// could photograph it — and every `PlayerScreen` gate fetch under a fake container hit the
    /// network. The canned transport makes the "unreachable host" the old comment assumed true by
    /// construction instead of by hoping nothing is listening on 8080.
    /// Same rule for the fire-and-forget index push: a fixture container opening a channel POSTed
    /// its rows to whatever `apiBaseURL` was, and the App Store run points that at a backend holding
    /// production credentials. A fixture writes nothing.
    @Test func theFakeContainerPushesNoIndexBatches() {
        #expect(AppContainer.fake().index.transport is FixedStatusTransport,
                "a fake container's index push reached the network")
    }

    @Test func theFakeContainerAnswersTheOfflineGateWithoutTheNetwork() async {
        let container = AppContainer.fake()
        #expect(container.gateTransport is FixedStatusTransport,
                "a fake container must not run the real gate client against the API base URL")
        #expect(await container.offlineGate.answer("xc7keR2piUM", channelId: nil) == .unreachable)
    }

    /// Offline review P1: the manager's gate closure dropped the channel, so `begin`, `retry` and
    /// the sweep never asked about it. Over a transport where the channel is pulled (410) and the
    /// video only reaches the catalog through it (404), the closure must answer `.gone` for a
    /// row that recorded the channel, and the video-only answer for one that did not.
    @Test func theManagersGateClosureAsksAboutTheRowsChannel() async {
        struct ChannelPulled: HTTPTransport {
            func send(_ request: HTTPRequest) async throws -> HTTPResponse {
                let pulled = request.method == "HEAD"
                let body = #"{"timestamp":"t","status":404,"error":"Not Found","message":"m","path":"p"}"#
                return HTTPResponse(status: pulled ? 410 : 404, headers: ["Content-Type": "application/json"],
                                    body: Data((pulled ? "" : body).utf8))
            }
        }
        let container = AppContainer(catalog: FakeCatalogClient(),
                                     userDefaults: UserDefaults(suiteName: "fitrahtube.offline-gate-closure") ?? .standard,
                                     modelContainer: AppContainer.makeModelContainer(inMemory: true),
                                     apiBaseURL: URL(string: "https://api.fitrah.test/")!,
                                     gateTransport: ChannelPulled())
        #expect(await container.offlineGateAnswer("xc7keR2piUM", "UCmMcOjsVehVlEOteyrhjI2Q") == .gone)
        #expect(await container.offlineGateAnswer("xc7keR2piUM", nil) == .allowed)
    }

    /// R5-1 again, for the account stack: a fixture container must make ZERO account requests. The
    /// same failure mode as the offline gate — a real client against `AppConfig.apiBaseURL` (Debug:
    /// `http://localhost:8080/`) reaches the documented dev backend whenever it happens to be
    /// running, and the screenshot rig's account screens would then be decided by the network.
    @Test func theFakeContainerAnswersAccountRequestsWithoutTheNetwork() async throws {
        let container = AppContainer.fake()
        #expect(container.authorizedTransport is ScriptedTransport)
        let me = try await container.account.me()
        #expect(me.uid == "fake-uid")
        #expect(me.status == .active)
    }

    /// Fix round 1 / I3, the same failure mode once more, for the Me feed: the fixture feed
    /// answered `FixedStatusTransport(status: 503)`, so every `-fitrah-seed-subscriptions` channel
    /// came back `.httpError(503)` and Task 19's `me-signed-in` screenshot would have been
    /// photographed with `me_refresh_error` painted across it. A fixture container must render the
    /// screen it is a fixture FOR — an empty feed, no error — and still make zero requests.
    @Test func theFixtureContainersFeedRefreshesWithoutPaintingTheRefreshError() async {
        let suite = "fitrahtube.me-feed-fixture-tests"
        let defaults = UserDefaults(suiteName: suite) ?? .standard
        defaults.removePersistentDomain(forName: suite)
        let container = AppContainer.fake(defaults: defaults)

        await container.meFeed.refresh(channelIds: ["UCseededFixtureChannel"], force: true)

        #expect(container.meFeed.lastError == nil,
                "a fixture feed must not paint me_refresh_error onto the screenshot rig")
        #expect(container.meFeed.weeks.isEmpty)
    }

    /// Patch round P2, the WIRING: the container's offline manager refuses a save for an account
    /// whose deletion is on record, and only for that account.
    @Test func theOfflineManagerRefusesSavesForAnAccountBeingDeleted() async throws {
        let suite = "fitrahtube.departed-save-wiring-tests"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let container = AppContainer.fake(defaults: defaults)
        UserDefaultsDeletionMarker(defaults: defaults).pendingUids = ["uid-a"]

        for (uid, video) in [("uid-a", "xc7keR2piUM"), ("uid-b", "vidOwnerB00")] {
            await container.offlineManager.save(videoId: video, quality: "360p", audioOnly: true,
                                                metadata: OfflineMetadata(title: "Lecture", userId: uid))
        }

        #expect(container.offlineStore.items.map(\.userId) == ["uid-b"],
                "a departing account's save landed, or another account's was refused")
    }

    /// Review I3, the WIRING. `AccountSession`'s `wipeRows` has a default so its 20-odd test call
    /// sites compile unchanged, and that default REFUSES — so a container that forgot to pass the
    /// real one keeps the marker forever and never deletes the departed account's rows.
    ///
    /// `try #require`, never `?? .standard`: this test writes a pending-deletion flag, and
    /// `InMemoryDeletionMarker`'s doc is that no test may put one in the app's real domain.
    @Test func aMarkerOwedToADepartedAccountReachesTheRealScopedDelete() async throws {
        let suite = "fitrahtube.scoped-delete-wiring-tests"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let container = AppContainer.fake(defaults: defaults)
        for uid in ["uid-a", "uid-b"] {
            container.favorites.currentUserId = uid
            try container.favorites.importVideo(id: "xc7keR2piUM", title: "Lecture", channelName: "Alafasy",
                                                thumbnailUrl: nil, durationSeconds: 600,
                                                approvalStatus: "APPROVED", at: Date(timeIntervalSince1970: 0))
        }
        container.favorites.currentUserId = ""
        let marker = UserDefaultsDeletionMarker(defaults: defaults)
        marker.pendingUids = ["uid-a"]
        marker.lastSignedInUid = "uid-b"

        await container.session.resumePendingDeletion()

        container.favorites.currentUserId = "uid-a"
        #expect(container.favorites.items.isEmpty, "the departed account's rows were stranded on the device")
        container.favorites.currentUserId = "uid-b"
        #expect(container.favorites.items.count == 1, "the account that holds the device lost its library")
        #expect(marker.pendingUids.isEmpty)
    }

    /// Task 36 round 2 / P2, the WIRING of the wiper's takeover check. Constructed, never raced:
    /// the fixture's `/me` account lands through `refresh()` (the seed latches it), Firebase then
    /// drops it behind the session's back, and the durable record is put back to the departed
    /// account — so the launch wipe is ENTERED on positive evidence while the session has observed
    /// somebody else. A container that handed the wiper anything but the session's own check
    /// device-wipes the account it observed.
    @Test func theWipersTakeoverCheckIsTheSessionsNotAConstant() async throws {
        let suite = "fitrahtube.wiper-takeover-wiring-tests"
        let defaults = try #require(UserDefaults(suiteName: suite))
        defaults.removePersistentDomain(forName: suite)
        defer { defaults.removePersistentDomain(forName: suite) }
        let auth = FakeAuthClient(state: .signedOut)
        let container = AppContainer.fake(defaults: defaults, auth: auth)
        let holder = FakeAuthClient.defaultUser.uid
        for uid in ["uid-a", holder] {
            container.favorites.currentUserId = uid
            try container.favorites.importVideo(id: "xc7keR2piUM", title: "Lecture", channelName: "Alafasy",
                                                thumbnailUrl: nil, durationSeconds: 600,
                                                approvalStatus: "APPROVED", at: Date(timeIntervalSince1970: 0))
        }
        container.favorites.currentUserId = ""
        _ = try await auth.signIn(email: "a@b.test", password: "p")
        await container.session.refresh()
        #expect(container.session.state.me?.uid == holder, "the precondition is an account the session has observed")
        try auth.signOut()
        let marker = UserDefaultsDeletionMarker(defaults: defaults)
        marker.pendingUids = ["uid-a"]
        marker.lastSignedInUid = "uid-a"

        await container.session.resumePendingDeletion()

        container.favorites.currentUserId = holder
        #expect(container.favorites.items.count == 1, "the device wipe ran over an account the session had observed")
        container.favorites.currentUserId = "uid-a"
        #expect(container.favorites.items.isEmpty, "the departed account's debt was not paid by uid")
        #expect(marker.pendingUids.isEmpty)
    }

    /// Fix round 1 / M3 + M5, one row for both halves of `approvals`' transport.
    ///
    /// M3: nothing pinned that this client got the SIGNED transport. `ApprovalsClient` deliberately
    /// mints no `Authorization` header of its own (`everyRequestCarriesTheDeviceIdAndNothingMints
    /// ItsOwnToken`) and delegates the Bearer to `AuthorizedTransport`, so a typo handing it a bare
    /// `URLSessionTransport()` would have failed no test and every request would have gone out
    /// unsigned. Reflected rather than exposed: `transport` is `private` on every hand-written
    /// client here, and one test is not a reason to widen three of them.
    ///
    /// M5: a FIXTURE gets the canned 503 instead, `sync`'s arm for `sync`'s reason — sharing
    /// `authorizedTransport` there means drinking from the four canned `/me` bodies the screenshot
    /// rig's account screens need.
    @Test func theApprovalsClientIsSignedInProductionAndCannedInAFixture() async throws {
        let fixture = AppContainer.fake()
        let page = try? await fixture.approvals.mySubmissions(limit: 100)
        #expect(page == nil, "a fixture must answer the canned 503, not a decoded account record")
        let me = try await fixture.account.me()
        #expect(me.uid == "fake-uid", "…and must leave the four-slot `/me` queue intact")

        let production = AppContainer(catalog: FakeCatalogClient(),
                                      modelContainer: AppContainer.makeModelContainer(inMemory: true),
                                      apiBaseURL: URL(string: "https://api.fitrah.test/")!,
                                      browse: FakeBrowseSource(),
                                      auth: FakeAuthClient(state: .signedOut))
        // Re-review nit 2: `#require`, not an optional chain. `transport` is read BY NAME, so a
        // rename would otherwise fail as a bare `nil is AuthorizedTransport` and say nothing about
        // the rename that caused it.
        let transport = try #require(Mirror(reflecting: production.approvals)
            .children.first { $0.label == "transport" }?.value,
                                     "ApprovalsClient must keep a stored property named `transport`")
        #expect(transport is AuthorizedTransport,
                "the approvals client must be built over the signed transport")
    }

    /// Task 27's twin of the row above, for ruling F1's fifth client. Same two halves and the same
    /// two reasons: `YouTubeSearchClient` mints no `Authorization` header of its own
    /// (`everyRequestCarriesTheDeviceIdAndTheBearerFromTheTransport`) so an unsigned transport would
    /// fail no test, and a fixture must answer the canned 503 rather than drink the `/me` queue.
    @Test func theYouTubeSearchClientIsSignedInProductionAndCannedInAFixture() async throws {
        let fixture = AppContainer.fake()
        let page = try? await fixture.youtubeSearch.search(q: "tafsir", type: .all, pageToken: nil)
        #expect(page == nil, "a fixture must answer the canned 503, not a decoded account record")
        let me = try await fixture.account.me()
        #expect(me.uid == "fake-uid", "…and must leave the four-slot `/me` queue intact")

        let production = AppContainer(catalog: FakeCatalogClient(),
                                      modelContainer: AppContainer.makeModelContainer(inMemory: true),
                                      apiBaseURL: URL(string: "https://api.fitrah.test/")!,
                                      browse: FakeBrowseSource(),
                                      auth: FakeAuthClient(state: .signedOut))
        let transport = try #require(Mirror(reflecting: production.youtubeSearch)
            .children.first { $0.label == "transport" }?.value,
                                     "YouTubeSearchClient must keep a stored property named `transport`")
        #expect(transport is AuthorizedTransport,
                "the search client must be built over the signed transport")
    }

    /// The transport posts from whatever isolation the request ran on; the center buffers one event
    /// and hands it over exactly once, so a re-render cannot route the user twice.
    ///
    /// Stage 3 / I4 + M2: the rule is TERMINAL PRECEDENCE, not "newest wins". The shape this
    /// replaces posted `.blocked` then `.deleted` through two independent `Task { @MainActor in }`
    /// hops and asserted the second survived — an ordering the cooperative executor never promised,
    /// and the wrong rule besides: only `.deleted` runs the device wipe, so losing it to a
    /// `.blocked` skips ruling C13 entirely. Asserted as a PURE comparison, which no scheduler can
    /// reorder, plus the post path in the order that used to be the dangerous one.
    @Test func theAccountStatusCenterKeepsTheMostTerminalEvent() async {
        #expect(AccountStatusEvent.deleted > .blocked)
        #expect(AccountStatusEvent.blocked > .signedOut)

        let center = AccountStatusCenter()
        center.post(.deleted)
        center.post(.blocked)
        center.post(.signedOut)
        // `post` hops to the main actor; yields past all three hops, and there is no clock.
        for _ in 0..<10 { await Task.yield() }
        #expect(center.pending?.event == .deleted, "a device wipe was lost behind a reversible block")
        #expect(center.consume()?.event == .deleted)
        #expect(center.consume() == nil)
    }

    /// Task 33 / Cubic round 1 P2: the TIE arm of the merge, which attribution made load-bearing and
    /// which nothing exercised — both existing merge tests post unattributed events of DISTINCT
    /// severities, so reverting the rule to `Swift.max` left the suite green.
    ///
    /// It matters because the two signals are no longer interchangeable. An unattributed signal is
    /// honoured unconditionally; an attributed one can be refused by `AccountSession.handle`. So on
    /// a tie the unattributed one must survive, or the user's own account deletion (which posts a
    /// bare `.deleted` from `performDeletion`) can lose its slot to a stale attributed verdict that
    /// is then refused — no wipe, no alert, and a confirmation screen with no exit.
    @Test func anEqualSeverityTieKeepsTheSignalThatCanActuallyBeActedOn() async {
        let center = AccountStatusCenter()
        center.post(.deleted)                 // unattributed: always honoured
        center.post(.deleted, for: "uid-a")   // attributed: refusable
        for _ in 0..<10 { await Task.yield() }
        #expect(center.consume()?.uid == nil,
                "an attributed verdict evicted the unattributed one it tied with")

        // And the other way round: an attributed signal held is replaced, because the newcomer is
        // at least as actionable — that is the pre-existing last-writer rule, kept.
        let other = AccountStatusCenter()
        other.post(.deleted, for: "uid-a")
        other.post(.deleted)
        for _ in 0..<10 { await Task.yield() }
        #expect(other.consume()?.uid == nil, "a stale attributed verdict kept the slot")
    }

    /// CF-A-55 (b) / CF-A-52: a per-uid buffer. A's attributed `.deleted` (the completion
    /// announcement is attributed since round 2 / I1) was EVICTED by a stale attributed `.deleted`
    /// for another account — last writer on a tie — which `handle` then refused, so A never saw
    /// its terminal alert. Both are held now, each to be accepted or refused on its own.
    @Test func aStaleAttributedVerdictCannotEvictAnotherAccountsSignal() async {
        let center = AccountStatusCenter()
        center.post(.deleted, for: "uid-a")
        center.post(.deleted, for: "uid-stale")
        for _ in 0..<10 { await Task.yield() }

        var drained: [AccountStatusSignal] = []
        while let signal = center.consume() { drained.append(signal) }
        #expect(Set(drained.compactMap(\.uid)) == ["uid-a", "uid-stale"], "one account's verdict evicted another's")
        #expect(center.pending == nil)
    }

    /// Patch round P3: severity ranks WITHIN a uid only. A stale, more severe `.deleted` for X used
    /// to clear every held signal, evicting B's valid `.blocked`. The same uid still collapses to
    /// its most severe event, and the drain runs least severe first so the most terminal one acts
    /// last (a `.signedOut` routed after a `.deleted` would drop the session under the wipe).
    @Test func aStaleSevereVerdictNeverEvictsAnotherAccountsSignal() async {
        let center = AccountStatusCenter()
        center.post(.blocked, for: "uid-b")
        center.post(.deleted, for: "uid-stale")
        center.post(.blocked, for: "uid-stale")
        center.post(.signedOut)
        for _ in 0..<10 { await Task.yield() }

        var drained: [AccountStatusSignal] = []
        while let signal = center.consume() { drained.append(signal) }
        #expect(drained == [AccountStatusSignal(event: .signedOut, uid: nil),
                            AccountStatusSignal(event: .blocked, uid: "uid-b"),
                            AccountStatusSignal(event: .deleted, uid: "uid-stale")])
    }

    /// The other order, for the same reason: whichever arrives second, `.deleted` is what routes.
    @Test func aLaterBlockedNeverOverwritesAPendingDeleted() async {
        let center = AccountStatusCenter()
        center.post(.blocked)
        center.post(.deleted)
        for _ in 0..<10 { await Task.yield() }
        #expect(center.consume()?.event == .deleted)
    }

    /// R5-1, fix round 1: stubbing the gate closed the deletion vector but not the constraint the
    /// item was written for — the fake container still built `LiveStreamResolver` over the real
    /// InnerTubeKit resolver, so nothing stopped a `-fitrah-seed-offline` launch resolving over the
    /// network. Every seam the offline stack can reach the network through must be a stub.
    @Test func theFakeContainersOfflineStackIsEntirelyStubbed() {
        let container = AppContainer.fake()
        #expect(container.isFixture)
        #expect(container.offlineEngine is ParkedOfflineEngine,
                "a fixture container must not open a background URLSession")
        #expect(container.offlineResolver is ParkedStreamResolver,
                "a fixture container must not resolve over InnerTubeKit")
    }

    /// Real-device finding: FitrahAPI and InnerTubeKit are `.dynamic` package products the app
    /// binary links through `@rpath`, so the app bundle must carry them. It did not — every device
    /// launch died in dyld ("Library not loaded: @rpath/FitrahAPI.framework"); the simulator only
    /// masked it because xcodebuild points DYLD_FRAMEWORK_PATH at the build products.
    @Test(arguments: ["FitrahAPI", "InnerTubeKit"])
    func theAppBundleEmbedsItsDynamicPackage(_ name: String) throws {
        let frameworks = try #require(Bundle.main.privateFrameworksURL)
        #expect(FileManager.default.fileExists(atPath: frameworks.appending(path: "\(name).framework/\(name)").path),
                "\(name).framework is not embedded in \(Bundle.main.bundleURL.lastPathComponent)")
    }

    /// Real-device check 1 (`DeviceChecks.testBackgroundDownload`): `-fitrah-debug-offline-allowed
    /// <videoId>` makes exactly ONE named lecture offline-allowed and hands the offline stack its
    /// REAL resolver and engine; every other video still gets the fixture's canned no-answer.
    /// Its rows live in memory, so its files go to the temporary directory, never the real library.
    @Test func theDebugOfflineAllowedLectureIsSaveableOverTheRealStack() async {
        let container = AppContainer.fake(offlineAllowedVideoId: "nhaGO__rxHQ")
        #expect(await container.offlineGate.answer("nhaGO__rxHQ", channelId: nil) == .allowed)
        #expect(await container.offlineGate.answer("xc7keR2piUM", channelId: nil) == .unreachable)
        #expect(container.offlineResolver is LiveStreamResolver)
        #expect(container.offlineBase == URL.temporaryDirectory)
        #expect(AppContainer.fake().offlineBase == URL.applicationSupportDirectory)
    }

    /// The behavioural half, and the one the screenshot rig actually depends on: the `.queued` seed
    /// row `seedDebugOfflineItemsIfRequested` inserts used to go straight through `schedule()` →
    /// `begin` → a real InnerTube resolve, fail, and photograph as "Failed" with Retry/Remove
    /// buttons — or stay "Waiting", depending on the network. Wait-don't-skip keeps it queued.
    @Test func aQueuedRowInAFakeContainerStaysQueuedAndNeverResolves() async throws {
        let container = AppContainer.fake()
        let item = OfflineItem(videoId: "seed-offline-0", title: "Seeded Lecture", channelName: nil,
                               thumbnailUrl: nil, qualityLabel: "360p", audioOnly: false,
                               status: OfflineStatus.queued.rawValue)
        try container.offlineStore.insert(item)

        await container.offlineManager.schedule()

        #expect(container.offlineStore.item(id: item.id)?.status == OfflineStatus.queued.rawValue,
                "the seeded row's caption and buttons must not be decided by the network")
        #expect(container.offlineStore.item(id: item.id)?.errorCode == nil)
        #expect(await container.offlineManager.pendingRetryIds == [item.id],
                "wait-don't-skip: parked on a timer, not dropped")
    }

    /// Task 4 acceptance: a fixture container provably builds NO Firebase object. `fake()` passes
    /// its own `FakeAuthClient` (the `injectedBrowse` idiom), so `auth`'s lazy initializer — the
    /// one place that could call `FirebaseBootstrap.configureIfPossible()` and construct
    /// `FirebaseAuthClient` — never runs under a fixture. Naming the DEBUG app-target type is the
    /// point: `FitrahTubeTests` cannot see a test-target double, and neither can Task 13's
    /// screenshot rig.
    @Test func theFakeContainerBuildsTheDebugFakeAuthClientAndNoFirebaseObject() {
        #expect(AppContainer.fake().auth is FakeAuthClient)
    }

    /// Task 5: the two OAuth seams are ordinary container members, not globals — Task 10's
    /// ViewModel takes them from here. Neither constructor touches Firebase, a network or an SDK
    /// singleton, which is why a fixture container needs no override for them (unlike `auth`).
    @Test func theFakeContainerExposesBothOAuthProvidersUnavailable() {
        let container = AppContainer.fake()
        #expect(container.googleSignIn is GoogleAuthProvider)
        #expect(container.appleSignIn is AppleAuthProvider)
        #expect(container.googleSignIn.isAvailable == SignInCapabilities.current().google)
        #expect(container.appleSignIn.isAvailable == SignInCapabilities.current().apple)
    }

    /// Ruling F11 at container level: with no `GoogleService-Info.plist` the sign-in screen has
    /// nothing to render at all.
    @Test func theFakeContainersCapabilitiesAreAllFalseWithNoOptionsFile() {
        let container = AppContainer.fake()
        #expect(container.capabilities == SignInCapabilities.current())
        if !FirebaseBootstrap.optionsFileExists {
            #expect(container.capabilities == SignInCapabilities(emailPassword: false, google: false, apple: false))
            #expect(SignInCapabilities.visibleProviders(container.capabilities).isEmpty)
        }
    }

    /// Fix round 1 / I1: the three sign-in dependencies must be SUBSTITUTABLE, not merely inert.
    /// `GoogleService-Info.plist` is USER-BLOCKED, so `capabilities` hard-wired to `.current()` is
    /// all-false permanently on this machine — Task 10's previews and Task 13's screenshot rig could
    /// never render a sign-in screen with a Google or Apple button. The `injectedAuth` idiom fixes
    /// it, and `FakeOAuthProvider` is what a caller injects.
    @Test func theFakeContainerTakesInjectedCapabilitiesAndProviders() {
        let google = FakeOAuthProvider()
        let apple = FakeOAuthProvider(credential: OAuthCredential(providerID: "apple.com",
                                                                  idToken: "fake-id-token",
                                                                  accessTokenOrNonce: "fake-nonce"))
        let everything = SignInCapabilities(emailPassword: true, google: true, apple: true)
        let container = AppContainer.fake(capabilities: everything, googleSignIn: google, appleSignIn: apple)

        #expect(container.capabilities == everything)
        #expect(SignInCapabilities.visibleProviders(container.capabilities) == [.emailPassword, .google, .apple],
                "a fixture container must be able to render a fully populated sign-in screen")
        #expect(container.googleSignIn === google)
        #expect(container.appleSignIn === apple)
    }

    /// The same answer from the LIVE container the app actually launched with (`AppContainer.current`,
    /// set by `FitrahTubeApp.init`) — the fixture path above could otherwise be hiding a difference.
    @Test func theLiveContainerReportsTheSameCapabilitiesAndProviders() throws {
        let container = try #require(AppContainer.current, "FitrahTubeApp.init must set AppContainer.current")
        #expect(container.capabilities == SignInCapabilities.current())
        #expect(container.googleSignIn.isAvailable == SignInCapabilities.current().google)
        #expect(container.appleSignIn.isAvailable == SignInCapabilities.current().apple)
    }

    @Test func fakeContainerServesCannedCategories() async throws {
        let container = AppContainer.fake()
        let categories = try await container.catalog.categories()
        #expect(categories.map(\.name) == ["Quran", "Lectures", "Kids"])
    }

    @Test func fakeContainerAcceptsInjectedCatalog() async throws {
        let container = AppContainer.fake(catalog: FakeCatalogClient(categories: [
            Category(id: "x", name: "Only", slug: "only", parentId: nil)
        ]))
        #expect(try await container.catalog.categories().count == 1)
    }

    @Test func apiBaseURLHasValidSchemeAndHost() {
        // Debug overrides (e.g. a LAN IP via Local.xcconfig) are allowed -- just require a real
        // http/https URL with a host, not the literal "localhost" every configuration happens to
        // use today.
        #expect(AppConfig.apiBaseURL.scheme == "http" || AppConfig.apiBaseURL.scheme == "https")
        #expect(AppConfig.apiBaseURL.host() != nil)
    }

    @Test func validateAcceptsHTTPAndHTTPSWithHost() {
        #expect(AppConfig.validate("http://localhost:8080/") != nil)
        #expect(AppConfig.validate("https://app.fitrahtube.com/") != nil)
    }

    @Test func validateRejectsSchemelessOrHostlessURLs() {
        #expect(AppConfig.validate("http:") == nil)
        #expect(AppConfig.validate("ftp://x") == nil)
    }

    /// CF-B1-13: the remote-config refresh spacing, extracted out of the side-effecting method
    /// so it is testable without a running scene.
    @Test func remoteConfigRefreshIsDueOnFirstCallAndThenOnlyAfterTheSpacing() {
        let now = Date(timeIntervalSince1970: 1_000_000)
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: nil, spacing: 900))
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-60), spacing: 900) == false)
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-901), spacing: 900))
        // T1-2: the `>=` boundary itself -- elapsed exactly equal to the spacing is DUE.
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-900), spacing: 900))
        #expect(FitrahTubeApp.isRemoteConfigRefreshDue(now: now, last: now.addingTimeInterval(-899), spacing: 900) == false)
    }

    /// RR-m6: the fixture skip on the launch path (R5-1 — the screenshot rig makes no network
    /// call) was named by no test in either target. Both arms, plus the due-gate it rides.
    @Test func theLaunchPathFetchesRemoteConfigOnlyWhenDueAndNotAFixture() {
        #expect(FitrahTubeApp.shouldFetchRemoteConfig(isFixture: false, due: true))
        #expect(FitrahTubeApp.shouldFetchRemoteConfig(isFixture: true, due: true) == false)
        #expect(FitrahTubeApp.shouldFetchRemoteConfig(isFixture: false, due: false) == false)
        #expect(FitrahTubeApp.shouldFetchRemoteConfig(isFixture: true, due: false) == false)
    }
}
