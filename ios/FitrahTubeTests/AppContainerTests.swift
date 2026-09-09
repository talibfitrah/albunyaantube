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
        let page = try? await fixture.approvals.mySubmissions(status: nil, cursor: nil, limit: 100)
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
        #expect(center.pending == .deleted, "a device wipe was lost behind a reversible block")
        #expect(center.consume() == .deleted)
        #expect(center.consume() == nil)
    }

    /// The other order, for the same reason: whichever arrives second, `.deleted` is what routes.
    @Test func aLaterBlockedNeverOverwritesAPendingDeleted() async {
        let center = AccountStatusCenter()
        center.post(.blocked)
        center.post(.deleted)
        for _ in 0..<10 { await Task.yield() }
        #expect(center.consume() == .deleted)
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
