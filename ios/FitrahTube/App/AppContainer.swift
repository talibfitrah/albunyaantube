import FitrahAPI
import Foundation
import InnerTubeKit
import SwiftData
import SwiftUI

nonisolated enum AppConfig {
    /// From Info.plist key `API_BASE_URL`, set per configuration in ios/Config/*.xcconfig.
    static var apiBaseURL: URL {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String,
              let url = validate(raw) else {
            preconditionFailure("API_BASE_URL missing/invalid from Info.plist — check ios/Config/*.xcconfig")
        }
        return url
    }

    /// `URL(string:)` alone accepts a value like `"http:"` -- a scheme with no host, which is
    /// exactly what an xcconfig `//`-comment typo (an unescaped `http://host/` truncated at the
    /// comment marker) parses to. Requiring http/https plus a host catches that at startup
    /// instead of silently pointing every request at a hostless URL.
    static func validate(_ raw: String) -> URL? {
        guard let url = URL(string: raw),
              url.scheme == "http" || url.scheme == "https",
              url.host() != nil else {
            return nil
        }
        return url
    }

    /// `ios-remote-config.json` at the repo root, read from `main` (spec `ios-app-design.md:76`) --
    /// the same raw.githubusercontent.com pattern Android's Available-updates screen uses for
    /// `releases-meta.json`, same repo/branch.
    // ponytail: the file doesn't exist at the repo root yet -- RemoteConfigStore.refresh() 404s
    // harmlessly and keeps serving InnerTubeKit's bundled default until it's published; swap
    // nothing here when it ships, this URL is already where it will land.
    static let innerTubeRemoteConfigURL = URL(string: "https://raw.githubusercontent.com/talibfitrah/albunyaantube/main/ios-remote-config.json")!
}

/// `UserDefaults` adapter for InnerTubeKit's `KeyValueStore` (persists the remote config's
/// last-known-good copy and the session bot-check cooldown across restarts).
private struct UserDefaultsKeyValueStore: KeyValueStore, @unchecked Sendable {
    // `UserDefaults` predates Swift concurrency and isn't annotated `Sendable`, but Apple's docs
    // guarantee it's thread-safe -- `InnerTubeKit`'s actors (`RemoteConfigStore`, `SessionStore`)
    // call `get`/`set` from their own isolation, same as every other `UserDefaults`-backed store
    // in this app (`UserDefaultsSettingsStore` et al.), just without their `@MainActor` wrapper.
    let defaults: UserDefaults

    func get(_ key: String) -> Data? { defaults.data(forKey: key) }
    func set(_ key: String, _ value: Data) { defaults.set(value, forKey: key) }
}

/// Composition root. Built once in `FitrahTubeApp`; every ViewModel receives what it needs from here
/// through its initializer (Hilt's constructor injection, without a framework).
///
/// `init`/`fake()` were `nonisolated` (spec §5) while every stored property was `Sendable`. The
/// persistence stores added in Phase 1 Task 4 are `@MainActor @Observable` classes, which are not
/// `Sendable`, so per spec §5's fallback ("wrap it behind a `@MainActor` store... or accept
/// `@MainActor init`") `init`/`fake()` are `@MainActor` here instead. The stores themselves are
/// `lazy` so building a container stays cheap and side-effect-free until something actually reads
/// settings/filters/history.
@MainActor final class AppContainer {
    /// Cubic P1: the app's ONE container, set once by `FitrahTubeApp.init` — the seam
    /// `AppDelegate.application(_:handleEventsForBackgroundURLSession:completionHandler:)` needs on
    /// a background-events relaunch, where no scene ever renders and so RootView's `.task` (the
    /// only other builder of `offlineManager`) never runs. Static-on-the-class rather than a
    /// registered closure: a closure the manager registers when built recreates the same
    /// chicken-and-egg (nothing builds the manager on that launch path). Only the App sets it;
    /// tests/previews that build their own containers leave it alone.
    static var current: AppContainer?

    let catalog: any CatalogClient
    /// Not private (Task 11): `EmailVerificationViewModel` persists its one-auto-send-per-account
    /// latch here, and a fixture container's screens must write to the fixture's suite rather than
    /// the app's real domain — the same reason `fake()` takes a `defaults:` at all.
    let userDefaults: UserDefaults
    private let modelContainer: ModelContainer
    private let apiBaseURL: URL
    /// What `offlineGate` sends over. Not private, and not a detail: a fixture container's gate
    /// client must never reach the network, so `AppContainerTests` pins which transport it got.
    let gateTransport: any HTTPTransport
    #if DEBUG
    /// A previews/tests/screenshot-rig container (`fake()`), whose offline stack must reach the
    /// network NOWHERE: the gate transport is canned, `offlineEngine`/`offlineResolver` are parked
    /// stubs, and `FitrahTubeApp` skips the remote-config fetch. DEBUG-only, with it the whole
    /// fixture surface: nothing outside `fake()` can set it, so Release has no fixture path at all.
    let isFixture: Bool
    #endif

    private(set) lazy var settings: any SettingsStore = UserDefaultsSettingsStore(defaults: userDefaults)
    private(set) lazy var filters: any FilterStore = UserDefaultsFilterStore(defaults: userDefaults)
    private(set) lazy var searchHistory: any SearchHistoryStore = UserDefaultsSearchHistoryStore(defaults: userDefaults)
    private(set) lazy var favorites: any FavoritesStore = SwiftDataFavoritesStore(
        modelContainer: modelContainer, onDirty: { [weak self] in self?.pushDirtySoon(uid: $0) })
    /// Plan C Task 4: the playlist screen's Save toggle, same container/schema as favorites.
    private(set) lazy var savedPlaylists: any SavedPlaylistsStore = SwiftDataSavedPlaylistsStore(
        modelContainer: modelContainer, onDirty: { [weak self] in self?.pushDirtySoon(uid: $0) })
    /// Plan C Task 5: the channel screen's Subscribe toggle (RULING 27, 30-channel guest cap).
    private(set) lazy var subscriptions: any SubscriptionsStore = SwiftDataSubscriptionsStore(
        modelContainer: modelContainer, onDirty: { [weak self] in self?.pushDirtySoon(uid: $0) })
    /// Phase 3 Task 3: the Save-for-offline library rows, same container/schema as favorites.
    private(set) lazy var offlineStore = OfflineStore(modelContainer: modelContainer)
    /// Task 7 follow-up: the ONE spelling of the offline files' base directory — the manager
    /// (`makeOfflineManager`) writes under it and `PlayerScreen`'s `OfflineResolver` reads from it.
    let offlineBase = URL.applicationSupportDirectory
    private(set) lazy var categories: any CategoriesCache = LiveCategoriesCache(client: catalog)
    private(set) lazy var network = NetworkMonitor()
    /// Phase 3 Task 5: the per-video `offlineAllowed` gate — ONE client shared by the player's
    /// Save button (via `PlayerScreen`) and the manager's revalidation sweep.
    private(set) lazy var offlineGate = OfflineGateClient(transport: gateTransport, baseURL: apiBaseURL,
                                                          deviceId: .persisted(in: userDefaults))

    /// Phase 3 Task 4: resolve → download → persist over `offlineStore`. One background session
    /// (`ProgressiveEngine.backgroundSessionIdentifier`); `.prefetch` lane on the ONE limiter/clock
    /// (reconciliation note 4); the cellular gate reads `settings`/`network` live (note 6); the
    /// per-video gate closure is `offlineGate` (Task 5), whose `.unreachable`-on-error keeps the
    /// sweep fail-open.
    private(set) lazy var offlineManager: OfflineManager = makeOfflineManager()

    /// The download engine and the resolver `offlineManager` gets, as their own properties so
    /// `AppContainerTests` can name what a fixture container was actually handed.
    ///
    /// A fixture's RESOLVER has to be parked as well as its gate: over the real InnerTubeKit
    /// resolver a `-fitrah-seed-offline` launch's `.queued` seed row goes straight through
    /// `schedule()` → `begin` → a REAL InnerTube resolve for `seed-offline-0`, which fails and
    /// flips the row to `.failed` — the screenshot's caption and action buttons decided by the
    /// network.
    private(set) lazy var offlineEngine: any OfflineEngine = { () -> any OfflineEngine in
        #if DEBUG
        if isFixture { return ParkedOfflineEngine() }
        #endif
        let configuration = URLSessionConfiguration.background(withIdentifier: ProgressiveEngine.backgroundSessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        return ProgressiveEngine(directory: OfflineStorage.directoryURL(base: offlineBase), configuration: configuration)
    }()
    private(set) lazy var offlineResolver: any StreamResolving = { () -> any StreamResolving in
        #if DEBUG
        if isFixture { return ParkedStreamResolver() }
        #endif
        return LiveStreamResolver(resolver: resolver)
    }()

    /// Phase 3 Task 8: the app's ONE Cast seam. `lazy` like the stores above — building it is free
    /// and side-effect-free; `setUp()` (from `AppDelegate.didFinishLaunchingWithOptions`, through
    /// the `current` seam) is what actually creates the `GCKCastContext`. A container whose
    /// `setUp()` never ran reports `castAvailable == false`, which hides every cast affordance —
    /// spec §10's "not loaded at all" clause, and the fixture containers' default.
    private(set) lazy var castController = CastController()

    /// Phase 4 Task 4: the app's ONE auth seam, and the ONE token source `AuthorizedTransport`
    /// (Task 7) is handed (ruling F12 — `AuthClient` refines `AuthTokenProviding`, so no adapter
    /// sits between them).
    ///
    /// This calls `FirebaseBootstrap.configureIfPossible()` ITSELF (through `FirebaseAuthClient`'s
    /// failable init) rather than assuming `FitrahTubeApp.init()`'s warm-up already ran: `live()` is
    /// evaluated from a stored-property initializer, which Swift runs BEFORE that body. The call is
    /// idempotent. With no `GoogleService-Info.plist` — this machine, CI and every fresh checkout —
    /// it returns nil and the app gets `UnavailableAuthClient`, i.e. a guest that cannot sign in.
    ///
    /// Stage 4 / M3: the substitution itself is `#if DEBUG`, exactly as `injectedAccountStatusJSON`
    /// already is. It was never REACHABLE in Release (`live()` passes nothing and
    /// `LaunchArguments.debug` compiles to `[]`), but a shipped binary carrying the stored seam and
    /// the ability to swap the auth client is dead weight in the one place dead weight is worst.
    ///
    /// `() -> any AuthClient` and two `return`s rather than a `??` chain: in RELEASE the DEBUG arm
    /// is gone, and `FirebaseAuthClient() ?? UnavailableAuthClient()` on its own has no common type
    /// to infer (the old spelling only compiled because `injectedAuth`'s `(any AuthClient)?` drove
    /// the inference — which is exactly the kind of thing only the Release stage catches).
    private(set) lazy var auth: any AuthClient = { () -> any AuthClient in
        #if DEBUG
        if let injectedAuth { return injectedAuth }
        #endif
        if let firebase = FirebaseAuthClient() { return firebase }
        return UnavailableAuthClient()
    }()
    #if DEBUG
    private let injectedAuth: (any AuthClient)?
    #endif

    /// Phase 4 Task 5: the two federated sign-in seams and the F11 capability answer Task 10 renders
    /// from. All three take the `injectedAuth` idiom — not because the real ones are unsafe (neither
    /// constructor touches Firebase, a network or an SDK singleton) but because SUBSTITUTABILITY is
    /// what the constraint is for: `GoogleService-Info.plist` is USER-BLOCKED, so a hard-wired
    /// `.current()` is all-false permanently here and Task 10's previews and Task 13's screenshot rig
    /// could never render a populated sign-in screen.
    private(set) lazy var googleSignIn: any OAuthSignInProvider = { () -> any OAuthSignInProvider in
        #if DEBUG
        if let injectedGoogleSignIn { return injectedGoogleSignIn }
        #endif
        return GoogleAuthProvider()
    }()
    private(set) lazy var appleSignIn: any OAuthSignInProvider = { () -> any OAuthSignInProvider in
        #if DEBUG
        if let injectedAppleSignIn { return injectedAppleSignIn }
        #endif
        return AppleAuthProvider()
    }()
    let capabilities: SignInCapabilities
    #if DEBUG
    private let injectedGoogleSignIn: (any OAuthSignInProvider)?
    private let injectedAppleSignIn: (any OAuthSignInProvider)?
    #endif
    #if DEBUG
    /// The `/me` body a FIXTURE container answers with, or nil for the default ACTIVE student.
    private let injectedAccountStatusJSON: String?
    #endif

    /// Phase 4 Task 7: where `authorizedTransport`'s 403 account-lifecycle envelopes land.
    private(set) lazy var accountStatus = AccountStatusCenter()

    /// Phase 4 Task 16: the Me tab's subscribed-channel feed, over InnerTubeKit's ONE
    /// `AtomFeedFetcher` (the same per-channel cache the degraded channel page reads) and the same
    /// `UserDefaults`-backed `KeyValueStore` every other InnerTubeKit consumer here uses.
    ///
    /// `Calendar.autoupdatingCurrent` on purpose, and only HERE: "This week" is a claim about the
    /// user's own calendar, so the device's calendar and time zone are the right boundary — while
    /// every test injects an explicit one, because `Calendar.current` on an Arabic/Gulf device is
    /// the Islamic region calendar and a suite reading it passes in one region and fails in another
    /// (the Task 12 lesson).
    ///
    /// A FIXTURE container gets a canned response instead of the live fetcher, the same reason
    /// `gateTransport` does: `-fitrah-seed-subscriptions` puts channels on the screenshot rig's Me
    /// screen, and the feed's `.task` would otherwise fan out to youtube.com from a preview.
    ///
    /// Fix round 1 / I3: an EMPTY 200, not a 503. A canned failure made every seeded channel
    /// `.httpError(503)`, which is `me_refresh_error` painted across the `me-signed-in` screenshot
    /// — a fixture that fails is not a fixture. `AtomFeedFetcher`'s parser is deliberately
    /// defensive, so a body with no `<entry>` is a feed with no videos, not an error.
    private(set) lazy var meFeed: MeFeedRepository = {
        let store = UserDefaultsKeyValueStore(defaults: userDefaults)
        #if DEBUG
        if isFixture {
            let empty = FixedStatusTransport(status: 200, body: Data("<feed/>".utf8))
            return MeFeedRepository(
                atom: AtomFeedFetcher(transport: empty, keyValueStore: store),
                refreshState: store, now: { Date() }, calendar: .autoupdatingCurrent)
        }
        #endif
        return MeFeedRepository(atom: innerTube.atom, refreshState: store,
                                now: { Date() }, calendar: .autoupdatingCurrent)
    }()

    /// The hand-written clients' signed transport. Not private and not a detail, for the same
    /// reason `gateTransport` is not: `AppContainerTests` pins which one a fixture container got.
    ///
    /// **R5-1, again.** A fixture container must make ZERO account requests. `fake()` used to hand
    /// the offline gate a real client against `AppConfig.apiBaseURL` (Debug: `http://localhost:8080/`)
    /// and, with the documented dev backend running, the launch sweep got a real 404 per seeded row
    /// and deleted the whole screenshot fixture before the rig could photograph it. `ScriptedTransport`
    /// makes "no network" true by construction rather than by hoping nothing is listening on 8080 —
    /// the `gateTransport: FixedStatusTransport(status: 503)` precedent, with a body.
    private(set) lazy var authorizedTransport: any HTTPTransport = {
        #if DEBUG
        // Sized for every request the Me flow can make in one launch: `AccountSession.start()`
        // sends one `/me`, and a sign-out-then-sign-in inside the same screenshot run sends
        // another. A dry queue throws `exhausted`, which is a fixture bug and must look like one —
        // so it is short, not endless.
        if isFixture { return ScriptedTransport(Array(repeating: .json(200, fixtureAccountJSON), count: 4)) }
        #endif
        return AuthorizedTransport(base: URLSessionTransport(), apiHost: apiBaseURL.host() ?? "",
                                   tokens: auth, onStatusEvent: { [accountStatus] in accountStatus.post($0) },
                                   // Stage 5 / M1: the fallback verdict for this backend's bare 401
                                   // on a terminated account. Same `auth` object as `tokens`, so
                                   // there is still exactly ONE token source (ruling F12).
                                   refreshRefusal: { [auth] uid in await auth.refreshRefusal(signedFor: uid) },
                                   // R9-P2: the account the request is sent FOR, read before the
                                   // first mint — the mint that loses a deleted account's session
                                   // is the one that throws the verdict.
                                   currentUid: { [auth] in await auth.currentUser()?.uid })
    }()

    /// Phase 4 Task 7: `/api/account/*`, over the signed transport above.
    private(set) lazy var account = AccountClient(transport: authorizedTransport, baseURL: apiBaseURL,
                                                  deviceId: .persisted(in: userDefaults))

    /// Phase 4 Task 25: `/api/admin/approvals/*` + the submitter-owned registry writes, over the
    /// same signed transport. Role-gated by the BACKEND (`@PreAuthorize("hasAnyRole('ADMIN',
    /// 'MODERATOR')")`) as well as by the kebab that reaches it.
    ///
    /// Fix round 1 / M5: a FIXTURE gets the canned 503, `sync`'s arm below for `sync`'s reason —
    /// `authorizedTransport` is the ScriptedTransport holding the fixture's four canned `/me`
    /// bodies, and a page load would both consume slots from that queue and decode an account
    /// record as a submissions page (`data` absent → an empty list, i.e. a screenshot of the empty
    /// state whatever the fixture holds). Harmless only while the fixture `/me` says `role: "user"`;
    /// one role change there and the screenshot rig walks this screen and throws `exhausted`.
    private(set) lazy var approvals = ApprovalsClient(transport: signedOrFixtureTransport, baseURL: apiBaseURL,
                                                       deviceId: .persisted(in: userDefaults))

    /// Phase 4 Task 27: `GET api/admin/youtube/search`, over the same signed transport. Ruling F1's
    /// fifth hand-written client (Task 26 landed it with no consumer; RULING 28 kept it unwired
    /// until the screen that reads it). Role-gated by the BACKEND
    /// (`@PreAuthorize("hasAnyRole('ADMIN','MODERATOR')")`) as well as by the kebab that reaches it.
    ///
    /// A FIXTURE gets the canned 503 (`signedOrFixtureTransport`).
    private(set) lazy var youtubeSearch = YouTubeSearchClient(transport: signedOrFixtureTransport, baseURL: apiBaseURL,
                                                              deviceId: .persisted(in: userDefaults))

    // MARK: - Phase 4 Task 29: the import flow

    /// Ruling F9's seam. `GoogleYouTubeAuthorizer` is the sixth and last app file allowed to name a
    /// Google SDK type; every reader above it sees `any YouTubeAuthorizer` and a `String`.
    ///
    /// A FIXTURE gets a fake with NEITHER Google fact, so `isAvailable` is false and the Me kebab's
    /// Import row is absent — the screenshot rig must not depend on a Google grant it cannot have,
    /// and RULING 28 says an unavailable affordance is not rendered at all.
    private(set) lazy var youtubeAuthorizer: any YouTubeAuthorizer = {
        #if DEBUG
        if isFixture { return UnavailableYouTubeAuthorizer(available: Self.seedsImportReview) }
        #endif
        return GoogleYouTubeAuthorizer()
    }()

    #if DEBUG
    /// Task 30's screenshot hook. The ONE fixture state in which the import flow is allowed past
    /// `.authorizing`, and even then it addresses a `ScriptedTransport`, never `googleapis.com`.
    static var seedsImportReview: Bool { LaunchArguments.debug.contains("-fitrah-seed-import-review") }

    /// Three canned googleapis pages — one subscription, one playlist, one liked video, in the
    /// order `YouTubeImportSource` walks `CandidateType.allCases`. Approved fixture ids only.
    private static var cannedYouTubeLibrary: [HTTPResponse] {
        [.json(200, #"{"items":[{"id":"sub-1","snippet":{"title":"Zad Channel","resourceId":{"channelId":"UCmMcOjsVehVlEOteyrhjI2Q"}}}]}"#),
         .json(200, #"{"items":[{"id":"PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc","snippet":{"title":"Tafsir series"}}]}"#),
         .json(200, #"{"items":[{"id":"xc7keR2piUM","snippet":{"title":"Seeded Lecture","channelId":"UCmMcOjsVehVlEOteyrhjI2Q"}}]}"#)]
    }
    #endif

    /// The ONE type in this app that addresses `googleapis.com`, and the one place a PLAIN
    /// transport is deliberate: `authorizedTransport` would put the FitrahTube Firebase bearer and
    /// this device's id on a request to Google. The only credential that host ever sees is the
    /// user's own OAuth token, passed per call.
    private lazy var youtubeImportSource: YouTubeImportSource = {
        #if DEBUG
        if isFixture {
            // A fixture never addresses `googleapis.com`. With the screenshot hook it replays three
            // canned pages; without it, an empty queue, whose `.exhausted` failure is what puts the
            // screen on its error arm rather than on a request.
            return YouTubeImportSource(transport: ScriptedTransport(
                Self.seedsImportReview ? Self.cannedYouTubeLibrary : []))
        }
        #endif
        return YouTubeImportSource(transport: URLSessionTransport())
    }()

    /// The write half. `ImportClient` addresses the FITRAHTUBE backend, so it takes the signed
    /// transport — the two hosts are never confused. Every row goes through a STORE, which is what
    /// marks it dirty and lets `sync` push it exactly as a manual toggle's row is pushed.
    ///
    /// A FIXTURE gets the canned 503 `approvals`/`youtubeSearch` get, and for the same reason: the
    /// fixture's `authorizedTransport` is a `ScriptedTransport` holding canned `/me` bodies, and a
    /// resolve would both spend that queue and decode an account record as a page of results.
    private lazy var importPipeline = ImportPipeline(
        client: ImportClient(transport: signedOrFixtureTransport, baseURL: apiBaseURL,
                             deviceId: .persisted(in: userDefaults)),
        favorites: favorites, subscriptions: subscriptions, playlists: savedPlaylists, now: Date.init)

    /// Part B gate (stage 3 I-5): the import run is owned HERE, not by the screen. The run
    /// deliberately outlives a back-swipe (Task 29), and a screen-owned model meant a re-pushed
    /// Import screen started a second, interleaved run of a pipeline that is not re-entrant —
    /// double budget spend, double-counted summaries. The re-pushed screen now finds `.importing`
    /// and renders it. `meFeed`'s precedent.
    var importViewModel: ImportViewModel {
        if let built = builtImportViewModel { return built }
        let built = ImportViewModel(authorizer: youtubeAuthorizer, source: youtubeImportSource,
                                    pipeline: importPipeline)
        builtImportViewModel = built
        return built
    }
    /// Stored rather than `lazy` so `endImportRun()` can reset a run without BUILDING the model on
    /// a sign-out that never opened the screen.
    private var builtImportViewModel: ImportViewModel?

    /// The session ended: the run in flight belonged to it (`ImportViewModel.reset`).
    func endImportRun() { builtImportViewModel?.reset() }

    /// The ONE spelling of "may this account import" (`MeViewModel.canImport`): the signed-in
    /// account's own providers AND the SDK session, read at render time.
    var canImportFromYouTube: Bool {
        MeViewModel.canImport(providerIDs: session.user?.providerIDs,
                              sdkSessionAvailable: youtubeAuthorizer.isAvailable)
    }

    /// The signed transport, except for a FIXTURE, which gets a canned 503 — the `gateTransport`
    /// precedent: the fixture's `authorizedTransport` is a `ScriptedTransport` holding canned `/me`
    /// bodies, and a resolve or a pull would both spend that queue and decode an account record as
    /// a page of results. Zero network either way.
    private var signedOrFixtureTransport: any HTTPTransport {
        #if DEBUG
        if isFixture { return FixedStatusTransport(status: 503) }
        #endif
        return authorizedTransport
    }

    /// Phase 4 Task 23/24: the ONE sync manager. Cheap to build (no session, no directory, no
    /// network until something triggers it), so `lazy` like every other store here.
    /// `sessionSleep` is `noSleep` for a fixture, so the bounded ladders spin out instantly instead
    /// of burning the screenshot rig's wall clock.
    ///
    /// `onWrite` (Part B gate, stage 5 I3): every committed sync write re-reads the three stores,
    /// so a library restored on a fresh device is on screen when the pull lands, not on relaunch.
    private(set) lazy var sync: any SyncTriggering = injectedSync ?? SyncManager(
        client: SyncClient(transport: signedOrFixtureTransport, baseURL: apiBaseURL,
                           deviceId: .persisted(in: userDefaults)),
        modelContainer: modelContainer, backoff: SyncBackoff(), sleep: sessionSleep,
        onWrite: { [weak self] in
            guard let self else { return }
            for store in userScopedStores { store.reload() }
        })
    private let injectedSync: (any SyncTriggering)?

    /// The ONE push-on-change entry point (Task 24): the three local stores' writes and a restored
    /// connection both land here.
    ///
    /// COALESCING, which the manager deliberately does not do. `SyncManager` serialises but never
    /// dedupes (Task 23: "ONE pull" is read as serialisation), so five rapid toggles would queue
    /// five tasks each waiting on the exclusion behind a network round trip, and the last four
    /// would drain rows the first already pushed. While a drain is in flight another request sets
    /// `pushAgain` instead of queueing, and exactly one follow-up runs after it -- so a row
    /// dirtied mid-drain is still pushed, and a burst costs two drains at most.
    ///
    /// An empty uid is a GUEST write: nothing to push, and no account to push it to.
    ///
    /// Task 24 review / M2: the follow-up carries the LATEST uid, not the one the in-flight drain
    /// started with. Reachable across an account switch that dirties a row before the outgoing
    /// account's drain has finished; replaying the first uid there would drain the wrong account's
    /// rows and leave the new one's dirty rows unpushed until something else asked.
    func pushDirtySoon(uid: String) {
        guard !uid.isEmpty else { return }
        guard pushTask == nil else { pushAgain = uid; return }
        pushTask = Task { [sync] in
            await sync.pushDirty(uid: uid)
            pushTask = nil
            if let next = pushAgain {
                pushAgain = nil
                pushDirtySoon(uid: next)
            }
        }
    }

    private var pushTask: Task<Void, Never>?
    private var pushAgain: String?

    /// Phase 4 Task 24: connectivity restored -> push the dirty rows at once
    /// (`AlBunyaanApplication.kt:206-215`, `onAvailable`). Losing the path does NOTHING: there is
    /// nothing useful to do with a dirty row while the device is offline, and a push that starts
    /// there just burns the retry ladder. Driven by `FitrahTubeApp`'s `network.isOnline` observer.
    func connectivityChanged(isOnline: Bool) {
        guard isOnline, let uid = session.syncableUid else { return }
        pushDirtySoon(uid: uid)
    }

    /// Phase 4 Task 9: the ONE holder of account state, and the only thing that writes
    /// `currentUserId`. Started by `RootView`'s `.task`.
    private(set) lazy var session = AccountSession(auth: auth, account: account, stores: userScopedStores,
                                                   status: accountStatus, sleep: sessionSleep,
                                                   // `[weak self]` breaks the retain cycle, but a
                                                   // released container would then turn ruling
                                                   // C13's wipe into a silent no-op — the one
                                                   // failure here nothing downstream can observe
                                                   // (fix round 1 / M1).
                                                   wipe: { [weak self] in
                                                       guard let self else {
                                                           assertionFailure("the container was released before the device wipe ran")
                                                           return CancellationError()
                                                       }
                                                       return await makeWiper().wipe()
                                                   },
                                                   // Stage 5 / C1.2: the durable "a wipe is owed"
                                                   // flag, on the same suite every other key uses.
                                                   marker: UserDefaultsDeletionMarker(defaults: userDefaults),
                                                   // Stage 4 / I1: the two SDKs whose own Keychain
                                                   // sessions outlive Firebase's sign-out.
                                                   providers: [googleSignIn, appleSignIn],
                                                   // Task 24: sign-in binds once `/me` has landed,
                                                   // and `dropSession` unbinds -- the ONE teardown
                                                   // path, so the deletion needs no wiring of its own.
                                                   sync: sync)

    /// Phase 4 Task 18: ruling C13's device wipe, built ON DEMAND rather than stored. Reaching for
    /// `session` must not construct `offlineManager` — that builds a background `URLSession`, which
    /// every test and preview that only wants an account would then pay for.
    private func makeWiper() -> LocalAccountWiper {
        LocalAccountWiper(offline: offlineManager, offlineStore: offlineStore, stores: userScopedStores,
                          modelContainer: modelContainer, searchHistory: searchHistory, defaults: userDefaults)
    }

    /// Fix round 1 / M5: a FIXTURE never burns real wall clock. Its `authorizedTransport` holds one
    /// canned `/me`, so a second refresh in a preview or the screenshot rig throws `exhausted` ->
    /// `.network` -> three attempts through `realSleep`, i.e. 3 s of the rig's time waiting on a
    /// retry that can only fail again.
    private var sessionSleep: @Sendable (Duration) async -> Void {
        #if DEBUG
        if isFixture { return Self.noSleep }
        #endif
        return Self.realSleep
    }

    /// A named `nonisolated static` rather than a closure literal: under
    /// `SWIFT_DEFAULT_ACTOR_ISOLATION: MainActor` a literal written here is inferred main-actor
    /// isolated, and `AccountSession`'s `sleep` is a nonisolated `@Sendable` function — "cannot be
    /// both main actor-isolated and nonisolated".
    private nonisolated static func realSleep(_ duration: Duration) async {
        try? await Task.sleep(for: duration)
    }

    /// Named `nonisolated static` for the same reason `realSleep` is — a closure literal written in
    /// this type is inferred main-actor isolated and cannot satisfy a nonisolated `@Sendable`.
    private nonisolated static func noSleep(_ duration: Duration) async {}

    /// Every per-user local store, in ONE list. A store added here is re-scoped on every auth
    /// change for free; a store that is not is the bug this list exists to make visible.
    /// Stage 3 / I2: `meFeed` is on this list. It is a process-lifetime `lazy var` holding the
    /// PREVIOUS account's bucketed videos in memory, and nothing else re-scopes it — so account B
    /// rendered account A's feed on the first frame, and the ruling-C13 wipe left it renderable.
    private var userScopedStores: [any UserScoped] { [favorites, savedPlaylists, subscriptions, meFeed] }

    #if DEBUG
    /// What a fixture container's `GET /api/account/me` answers. Task 13's `-fitrah-fake-auth`
    /// hook is what makes this selectable per launch (`fake(accountStatusJSON:)`); the default is
    /// the same ACTIVE student it always was, matching `FakeAuthClient.defaultUser`.
    static let fixtureAccountMeJSON = fixtureAccountMeJSON(status: "active")

    /// The same record with a different lifecycle `status` — `pending_profile`, `blocked` and
    /// `deleted` are what `RootView`'s `SplashRouter` outcome routes on, so the screenshot rig
    /// reaches those screens by changing this one field.
    static func fixtureAccountMeJSON(status: String, role: String = "user") -> String {
        """
        {"uid":"fake-uid","email":"student@fitrah.test","displayName":"Aisha","dateOfBirth":"2001-04-09",\
        "phoneNumber":null,"status":"\(status)","role":"\(role)"}
        """
    }

    /// Task 4 deliberately left this out (nothing could consume it); Task 13 adds it WITH its
    /// consumer, `authorizedTransport` above.
    private var fixtureAccountJSON: String { injectedAccountStatusJSON ?? Self.fixtureAccountMeJSON }
    #endif

    private func makeOfflineManager() -> OfflineManager {
        let base = offlineBase
        let manager = OfflineManager(
            store: offlineStore,
            engine: offlineEngine,
            resolver: offlineResolver,
            limiterCheck: { [innerTube] in await innerTube.rateLimiter.check($0, kind: .prefetch, now: innerTube.clock.now) },
            wifiOnly: { [settings] in settings.wifiOnlyDownloads },
            isOnCellular: { [network] in network.isOnCellular },
            baseDirectory: base,
            gate: { [offlineGate] in await offlineGate.answer($0) },
            now: { Date() },
            // The SAME config read `PlayerScreen`'s Save button consults (Task 6 review fold-in:
            // the manager refuses to START new work while the kill-switch is off).
            downloadsEnabled: { [innerTube] in await innerTube.remoteConfig.current().isDownloadsEnabled })
        observeOfflineGate(manager)
        return manager
    }

    /// Reconciliation note 6's "thin observation glue": re-arms itself on every change to the
    /// two gate inputs and forwards into the actor. Re-arm BEFORE acting — `withObservationTracking`
    /// fires once per arm, so a change landing while `gateDidChange` is still running would
    /// otherwise go unobserved and the gate would stick to a stale answer.
    private func observeOfflineGate(_ manager: OfflineManager) {
        withObservationTracking {
            _ = settings.wifiOnlyDownloads
            _ = network.isOnCellular
        } onChange: {
            Task { @MainActor [weak self] in
                self?.observeOfflineGate(manager)
                await manager.gateDidChange()
            }
        }
    }

    /// InnerTubeKit composition root (CF-B3/CF-B4, `ios-app-plan.md` §6.1) -- resolves a videoId to
    /// a playable stream via `resolver`. `lazy`, same reasoning as the stores above: building it is
    /// cheap and side-effect-free (no network call happens until something resolves or refreshes).
    private(set) lazy var innerTube: InnerTube = InnerTube(
        keyValueStore: UserDefaultsKeyValueStore(defaults: userDefaults),
        availabilityGate: BackendAvailabilityGate(baseURL: apiBaseURL),
        locale: Self.deviceLocale(),
        remoteConfigURL: Self.debugRemoteConfigURL ?? AppConfig.innerTubeRemoteConfigURL
    )
    var resolver: StreamResolver { innerTube.resolver }

    /// Plan C Task 6 step 8: `-fitrah-remote-config-url <url>` (DEBUG) lets the live rig serve a
    /// document itself and prove `refresh()` adopts it, without editing the production URL.
    private static var debugRemoteConfigURL: URL? {
        #if DEBUG
        let args = LaunchArguments.debug
        guard let i = args.firstIndex(of: "-fitrah-remote-config-url"), args.indices.contains(i + 1) else { return nil }
        return AppConfig.validate(args[i + 1])
        #else
        return nil
        #endif
    }

    /// Plan C Task 2: the detail screens' browse seam and the fire-and-forget index push. `browse`
    /// is injectable (`fake(browse:)`) so previews/UI tests drive the screens from fixtures; the
    /// live one shares InnerTubeKit's `BrowseClient`/`AtomFeedFetcher` and the same
    /// UserDefaults-backed `KeyValueStore` for its 1 h degraded latch.
    private(set) lazy var index = IndexClient(baseURL: apiBaseURL, deviceId: .persisted(in: userDefaults))
    /// Plan C Task 3: the hand-written `POST /api/v1/reports` (same seam, awaited by `ReportSheet`).
    private(set) lazy var report: ReportClient = {
        #if DEBUG
        // Plan C Task 6 screenshot rig: `-fitrah-fake-report <status>` answers every report POST
        // with that status and no network (201 -> thank-you, 429 -> the sheet stays).
        let args = LaunchArguments.debug
        if let i = args.firstIndex(of: "-fitrah-fake-report"), args.indices.contains(i + 1), let status = Int(args[i + 1]) {
            return ReportClient(transport: FixedStatusTransport(status: status), baseURL: apiBaseURL, deviceId: .persisted(in: userDefaults))
        }
        #endif
        return ReportClient(baseURL: apiBaseURL, deviceId: .persisted(in: userDefaults))
    }()
    private(set) lazy var browse: any BrowseSource = injectedBrowse ?? LiveBrowseSource(
        client: innerTube.browse,
        atom: innerTube.atom,
        latch: DegradedLatch(store: UserDefaultsKeyValueStore(defaults: userDefaults)),
        index: index,
        gate: BackendAvailabilityGate(baseURL: apiBaseURL),
        degradedHeader: degradedHeader
    )
    private let injectedBrowse: (any BrowseSource)?
    private let degradedHeader: (@Sendable (String) async throws -> ChannelHeader)?
    /// Plan C Task 4: a deep-linked `Route.playlist` carries no title/count, so the header falls back
    /// to `getPublicPlaylist` -- same closure shape as `degradedHeader` (the container never holds the
    /// generated `Client`); nil in fake containers means the header stays whatever the route carried.
    let playlistHeader: (@Sendable (String) async throws -> PlaylistHeader)?

    init(catalog: any CatalogClient, userDefaults: UserDefaults = .standard, modelContainer: ModelContainer, apiBaseURL: URL,
         browse: (any BrowseSource)? = nil, degradedHeader: (@Sendable (String) async throws -> ChannelHeader)? = nil,
         playlistHeader: (@Sendable (String) async throws -> PlaylistHeader)? = nil,
         gateTransport: any HTTPTransport = URLSessionTransport(),
         auth: (any AuthClient)? = nil,
         capabilities: SignInCapabilities? = nil,
         googleSignIn: (any OAuthSignInProvider)? = nil,
         appleSignIn: (any OAuthSignInProvider)? = nil,
         accountStatusJSON: String? = nil,
         sync: (any SyncTriggering)? = nil,
         isFixture: Bool = false) {
        #if DEBUG
        self.injectedAccountStatusJSON = accountStatusJSON
        self.injectedAuth = auth
        self.injectedGoogleSignIn = googleSignIn
        self.injectedAppleSignIn = appleSignIn
        #endif
        self.capabilities = capabilities ?? .current()
        self.catalog = catalog
        self.userDefaults = userDefaults
        self.modelContainer = modelContainer
        self.apiBaseURL = apiBaseURL
        self.injectedBrowse = browse
        self.injectedSync = sync
        #if DEBUG
        self.isFixture = isFixture
        #endif
        self.degradedHeader = degradedHeader
        self.playlistHeader = playlistHeader
        self.gateTransport = gateTransport
    }

    static func live(baseURL: URL = AppConfig.apiBaseURL) -> AppContainer {
        let deviceId = DeviceId.persisted()
        let api = FitrahAPIClient.make(baseURL: baseURL, deviceId: deviceId)
        // Degraded-mode header (plan Task 2 table): the backend's own `Channel` stands in for a
        // bot-checked `channelHeader` -- name and avatar only; banner, subscriber line and verified
        // badge are lost, which the screen renders as their placeholders. Hand-written (C T6):
        // the generated `getPublicChannel`/`getPublicPlaylist` cannot decode production's
        // Timestamp objects, see `PublicHeaders`.
        let headers = PublicHeaders(baseURL: baseURL, deviceId: deviceId)
        return AppContainer(
            catalog: LiveCatalogClient(client: api), modelContainer: makeModelContainer(inMemory: false), apiBaseURL: baseURL,
            degradedHeader: { try await headers.channel($0) },
            playlistHeader: { try await headers.playlist($0) })
    }

    /// Device language/region for InnerTube requests (`hl`/`gl`) -- ruling 19: the engine itself
    /// never reads `Locale.current`, the app supplies it. Deliberately NOT
    /// `SettingsStore.systemLocaleCode`, which clamps to the app's 3 supported UI languages
    /// (en/ar/nl); YouTube's `hl`/`gl` should reflect the device's real locale/region.
    private static func deviceLocale() -> InnerTubeLocale {
        let locale = Locale.current
        return InnerTubeLocale(hl: locale.language.languageCode?.identifier ?? "en", gl: locale.region?.identifier ?? "US")
    }

    #if DEBUG
    /// The fixture container's sync: it records nothing and does nothing. See `fake(sync:)`.
    nonisolated struct InertSync: SyncTriggering {
        func bind(uid: String) async {}
        func unbind() async {}
        func pushDirty(uid: String) async {}
        func syncNow(uid: String) async {}
    }

    static func fake(
        catalog: any CatalogClient = FakeCatalogClient(),
        // `?? .standard`: `UserDefaults(suiteName:)` returns nil for a suite name equal to the
        // bundle identifier or a reserved domain -- a trap in a default-argument position, far
        // from any call site (gate A-M15). "fitrahtube.fake" is safe today; this keeps it latent.
        defaults: UserDefaults = UserDefaults(suiteName: "fitrahtube.fake") ?? .standard,
        browse: any BrowseSource = FakeBrowseSource(),
        // Task 4: the `injectedBrowse` idiom again. Passing it explicitly is what makes "a fixture
        // container builds no Firebase object" structural rather than incidental — `auth`'s lazy
        // initializer, the only caller of `FirebaseBootstrap.configureIfPossible()` outside the
        // App's warm-up, never runs. Task 13's launch hook selects the state HERE, at construction,
        // instead of mutating a built container.
        auth: any AuthClient = FakeAuthClient(state: .signedOut),
        // Fix round 1 / I1: nil keeps the real (and, with no plist, permanently unavailable) sign-in
        // stack, so every existing call site is unchanged; a preview or screenshot run that needs a
        // populated sign-in screen passes an all-true `SignInCapabilities` and two
        // `FakeOAuthProvider`s.
        capabilities: SignInCapabilities? = nil,
        googleSignIn: (any OAuthSignInProvider)? = nil,
        appleSignIn: (any OAuthSignInProvider)? = nil,
        // Task 4 deferred this (deviation 1) because nothing could consume it. Task 13 adds it
        // together with its consumer: it is the BODY the fixture `/me` answers, so a screenshot
        // run can put the rig on a pending-profile, blocked or deleted account.
        accountStatusJSON: String? = nil,
        // Task 24: the `injectedBrowse` idiom once more. `SyncTriggerTests` drives the five wiring
        // sites against a recording double; without this they would need a real drain to see one
        // method name.
        //
        // Task 24 review / M4: the DEFAULT is a no-op, not nil. A nil built a real `SyncManager`,
        // so any test or preview that signed in through `fake().session` — e.g.
        // `RootViewDestinationTests.theOutcomeAdvisoriesDropTheSessionAndRaiseTheAlert` — spawned a
        // background merge running `tagAnonRows` against the fixture container plus a bounded 503
        // ladder, i.e. background mutation racing that test's assertions. A fixture that genuinely
        // wants the real manager passes one.
        sync: (any SyncTriggering)? = InertSync()
    ) -> AppContainer {
        // A private suite (not `.standard`) so previews/tests never read or write the app's real
        // defaults domain. Does NOT wipe the suite -- callers that write through the returned
        // container's stores (settings/filters/favorites/search history) must pass their own
        // suite with their own teardown, or repeated calls sharing the default suite name would
        // leak state between them. `sharedFake` wipes its suite once, at creation.
        //
        // `apiBaseURL: AppConfig.apiBaseURL`: `innerTube`/`resolver` are still real network-backed
        // InnerTubeKit actors here (the package has no fake variant) -- previews/tests that never
        // touch them pay nothing (`lazy`); one that does gets `BackendAvailabilityGate`'s fail-open
        // behaviour against an unreachable host instead of a crash.
        //
        // `gateTransport`: a CANNED 503, never the real `OfflineGateClient` against
        // `AppConfig.apiBaseURL` (Debug: `http://localhost:8080/`) — that host is only unreachable
        // while nothing is listening on 8080, and with the documented dev backend running the
        // launch sweep gets a real 404 for every `-fitrah-seed-offline` row and deletes the whole
        // screenshot fixture before the rig can photograph it, while every `PlayerScreen` gate
        // fetch under a fake container hits the network. A canned 503 is `.unreachable` by
        // construction: hidden Save button, keep-on-sweep, zero requests.
        AppContainer(catalog: catalog, userDefaults: defaults, modelContainer: makeModelContainer(inMemory: true),
                     apiBaseURL: AppConfig.apiBaseURL, browse: browse,
                     gateTransport: FixedStatusTransport(status: 503), auth: auth,
                     capabilities: capabilities, googleSignIn: googleSignIn, appleSignIn: appleSignIn,
                     accountStatusJSON: accountStatusJSON, sync: sync, isFixture: true)
    }
    #endif

    /// Gate A-I1. This runs eagerly on the launch path (`live()` is evaluated in `FitrahTubeApp`'s
    /// `@State` initialiser), so its failure mode used to be a `preconditionFailure` -- a permanent
    /// crash loop on a corrupt or unmigratable store, unrecoverable without delete-and-reinstall.
    /// Recover by recreating instead: the store files are deleted and the container rebuilt once.
    /// Losing local favorites is the accepted cost (phase 4's sync restores them from the server);
    /// losing the whole app is not.
    ///
    /// `storeURL` exists so `AppContainerTests` can point the recovery path at a deliberately
    /// corrupt file; production always takes the default location.
    static func makeModelContainer(inMemory: Bool, storeURL: URL? = nil) -> ModelContainer {
        let schema = Schema(versionedSchema: FavoritesSchemaV5.self)
        let configuration = storeURL.map { ModelConfiguration(schema: schema, url: $0) }
            ?? ModelConfiguration(schema: schema, isStoredInMemoryOnly: inMemory)
        func build() throws -> ModelContainer {
            try ModelContainer(for: schema, migrationPlan: FavoritesMigrationPlan.self, configurations: configuration)
        }
        do {
            return try build()
        } catch {
            // Gate cubic-r3 X3: a bare catch used to jump straight to deleting the store on
            // *any* failure, destroying every local favorite even for a transient, fully
            // recoverable one -- disk full, the store still locked by a suspended extension,
            // a momentary I/O error. Retrying once first (no deletion) lets those clear on their
            // own; only a second failure is treated as the corrupt/unmigratable case the deletion
            // below exists for.
            if let recovered = try? build() { return recovered }
            if !inMemory {
                // `-shm`/`-wal`, appended to the path -- not `appendingPathExtension`, which
                // produces `default.store.shm` (gate wave-2 W1). SQLite names its sidecars by
                // suffixing the database *filename*, so the wrongly-named deletes left the real
                // WAL and SHM files next to a deleted store: the rebuild replayed stale frames or
                // failed again, dropping every launch to the in-memory fallback.
                for url in ["", "-shm", "-wal"].map({ URL(fileURLWithPath: configuration.url.path + $0) }) {
                    try? FileManager.default.removeItem(at: url)
                }
                if let recovered = try? build() { return recovered }
            }
            // Last resort: an in-memory store keeps the app usable for this launch rather than
            // trapping. If even that fails there is nothing left to fall back to.
            return try! ModelContainer(for: schema,
                                       configurations: ModelConfiguration(schema: schema, isStoredInMemoryOnly: true))
        }
    }

    /// One fake container per process: every preview/test that reads `\.container` without an
    /// explicit `.environment(\.container, …)` override shares this single instance (and its
    /// wiped suite), instead of each read point independently evaluating `.fake()` -- which would
    /// give every SwiftUI preview its own container with no shared state between them.
    #if DEBUG
    @MainActor static let sharedFake: AppContainer = {
        let defaults = UserDefaults(suiteName: "fitrahtube.fake") ?? .standard
        defaults.removePersistentDomain(forName: "fitrahtube.fake")
        // Plan C Task 6: the detail screens' fake reads its own `-fitrah-fake-browse-*` launch arguments.
        // Task 13: auth is chosen HERE, at construction — `auth` is a `private(set) lazy var`, so
        // nothing post-construction (the `-fitrah-seed-*` shape, which writes through a store) can
        // swap it.
        let fakeAuth = FakeAuth.fromLaunchArguments()
        return fake(defaults: defaults, browse: FakeBrowseSource.fromLaunchArguments(),
                    auth: FakeAuthClient(state: fakeAuth.state),
                    accountStatusJSON: fakeAuth.accountJSON)
    }()

    /// `-fitrah-fake-auth <signedOut|active|pendingProfile|blocked|deleted>`: the screenshot rig's
    /// only way onto a signed-in screen. Absent -> signed out, i.e. every existing rig invocation
    /// is unchanged.
    enum FakeAuth {
        static func fromLaunchArguments() -> (state: AuthState, accountJSON: String?) {
            let args = LaunchArguments.debug
            guard let i = args.firstIndex(of: "-fitrah-fake-auth"), args.indices.contains(i + 1) else {
                return (.signedOut, nil)
            }
            let signedIn = AuthState.signedIn(FakeAuthClient.defaultUser)
            switch args[i + 1] {
            case "active": return (signedIn, AppContainer.fixtureAccountMeJSON(status: "active"))
            case "pendingProfile": return (signedIn, AppContainer.fixtureAccountMeJSON(status: "pending_profile"))
            case "blocked": return (signedIn, AppContainer.fixtureAccountMeJSON(status: "blocked"))
            case "deleted": return (signedIn, AppContainer.fixtureAccountMeJSON(status: "deleted"))
            default: return (.signedOut, nil)
            }
        }
    }
    #endif
}

#if DEBUG
/// A fixture container's download engine: records nothing, moves nothing, opens no background
/// `URLSession` (a second one on `ProgressiveEngine.backgroundSessionIdentifier` is its own hazard).
/// `events` never yields, so the manager's consumer loop simply parks — `AsyncStream { _ in }`
/// discards the continuation into a build closure, but the stream VALUE retains the storage, so
/// `for await` stays suspended for the container's life (probed on this toolchain).
nonisolated struct ParkedOfflineEngine: OfflineEngine {
    let events: AsyncStream<OfflineDownloadEvent> = AsyncStream { _ in }
    func start(id: String, url: URL, userAgent: String, allowsCellular: Bool) async -> Data? { nil }
    func resume(id: String, resumeData: Data, allowsCellular: Bool) async {}
    func pause(id: String) async -> Data? { nil }
    func cancel(id: String) async {}
    func liveIds() async -> Set<String> { [] }
}

/// A fixture container's offline resolver. Answers every resolve with a cooldown a day out, which
/// `OfflineManager` treats as wait-don't-skip: the row stays `.queued` ("Waiting") behind a timer
/// that will not fire during a screenshot run. Deliberately NOT a throw the manager fails on — the
/// seeded `.queued` row must photograph as Waiting, and deliberately not a canned success either,
/// which would hand the engine a fake URL to pretend to download.
nonisolated struct ParkedStreamResolver: StreamResolving {
    func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                 sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool) async throws -> Resolved {
        throw ExtractionError.cooldown(until: Date().addingTimeInterval(86_400))
    }
}

/// `-fitrah-fake-report <status>`: one canned status for every request, no network. Also the fake
/// container's offline-gate transport — `AppContainerTests` names the type, so not private.
struct FixedStatusTransport: HTTPTransport {
    let status: Int
    /// Empty for the status-only callers; the Me feed's fixture needs a 200 to carry a body
    /// (`meFeed`, fix round 1 / I3).
    var body = Data()
    func send(_ request: HTTPRequest) async throws -> HTTPResponse { HTTPResponse(status: status, headers: [:], body: body) }
}
#endif

extension EnvironmentValues {
    // Release must not ship the fake default silently -- an un-injected .container in Release
    // traps instead of serving fake data.
    #if DEBUG
    @Entry var container: AppContainer = AppContainer.sharedFake   // previews / tests
    #else
    @Entry var container: AppContainer = { preconditionFailure("AppContainer not injected — wrap the root in .environment(\\.container, …)") }()
    #endif
}
