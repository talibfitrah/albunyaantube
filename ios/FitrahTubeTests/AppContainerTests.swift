import Foundation
import SwiftData
import Testing
import UIKit
@testable import FitrahTube

/// `.serialized` (Cubic R5-7): the background-events test swaps the process-global
/// `AppContainer.current` for a fixture container across its own awaits, and the cast test below
/// reads that same global — parallel MainActor tests interleave at exactly those suspensions.
@Suite(.perTest, .serialized)
struct AppContainerTests {

    /// R5-7: no background `URLSession`, no resolver, no files. The hook's job is to REACH the
    /// manager, and `reattachCount` is where that lands; the real `ProgressiveEngine` the process
    /// container carries would open a second session on `ProgressiveEngine.backgroundSessionIdentifier`
    /// and let `reattach()` start a live InnerTube resolve for any row a sibling test seeded.
    private nonisolated final class NoOpEngine: OfflineEngine, @unchecked Sendable {
        let events: AsyncStream<OfflineDownloadEvent> = AsyncStream { _ in }
        func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async -> Data? { nil }
        func resume(id: String, resumeData: Data, allowsCellular: Bool) async {}
        func pause(id: String) async -> Data? { nil }
        func cancel(id: String) async {}
        func liveIds() async -> Set<String> { [] }
    }

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
        let container = AppContainer.fake(offlineEngine: NoOpEngine())
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
    @Test func theFakeContainerAnswersTheOfflineGateWithoutTheNetwork() async {
        let container = AppContainer.fake()
        #expect(container.gateTransport is FixedStatusTransport,
                "a fake container must not run the real gate client against the API base URL")
        #expect(await container.offlineGate.answer("xc7keR2piUM") == .unreachable)
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
}
