import Foundation
import SwiftData
import Testing
import UIKit
@testable import FitrahTube

@Suite(.perTest)
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

    /// Cubic P1: a background-events relaunch calls the AppDelegate hook and renders NO scene, so
    /// RootView's `.task` — the only other builder of `offlineManager` — never runs; without this
    /// wiring the background session/delegate is never recreated, queued delegate events go
    /// undelivered, the chunk walk stalls, and the parked completion handler is never called. The
    /// hook must reach the App's one container (the `AppContainer.current` seam, set at
    /// `FitrahTubeApp.init` — which the test host's launch already ran) and schedule a `reattach()`.
    /// Asserted at flag level; the real background relaunch is device territory.
    @Test func theBackgroundSessionRelaunchHookReachesTheManagerAndSchedulesReattach() async throws {
        let container = try #require(AppContainer.current, "FitrahTubeApp.init must set AppContainer.current")
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
