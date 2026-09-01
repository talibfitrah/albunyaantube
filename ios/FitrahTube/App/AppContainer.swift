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
    let catalog: any CatalogClient
    private let userDefaults: UserDefaults
    private let modelContainer: ModelContainer
    private let apiBaseURL: URL

    private(set) lazy var settings: any SettingsStore = UserDefaultsSettingsStore(defaults: userDefaults)
    private(set) lazy var filters: any FilterStore = UserDefaultsFilterStore(defaults: userDefaults)
    private(set) lazy var searchHistory: any SearchHistoryStore = UserDefaultsSearchHistoryStore(defaults: userDefaults)
    private(set) lazy var favorites: any FavoritesStore = SwiftDataFavoritesStore(modelContainer: modelContainer)
    /// Plan C Task 4: the playlist screen's Save toggle, same container/schema as favorites.
    private(set) lazy var savedPlaylists: any SavedPlaylistsStore = SwiftDataSavedPlaylistsStore(modelContainer: modelContainer)
    /// Plan C Task 5: the channel screen's Subscribe toggle (RULING 27, 30-channel guest cap).
    private(set) lazy var subscriptions: any SubscriptionsStore = SwiftDataSubscriptionsStore(modelContainer: modelContainer)
    /// Phase 3 Task 3: the Save-for-offline library rows, same container/schema as favorites.
    private(set) lazy var offlineStore = OfflineStore(modelContainer: modelContainer)
    private(set) lazy var categories: any CategoriesCache = LiveCategoriesCache(client: catalog)
    private(set) lazy var network = NetworkMonitor()
    /// Phase 3 Task 5: the per-video `offlineAllowed` gate — ONE client shared by the player's
    /// Save button (via `PlayerScreen`) and the manager's revalidation sweep. Fake containers get
    /// the real client against an unreachable host: every answer is `.unreachable`, which is
    /// hidden-button / keep-on-sweep — the safe fixture default.
    private(set) lazy var offlineGate = OfflineGateClient(baseURL: apiBaseURL, deviceId: .persisted(in: userDefaults))

    /// Phase 3 Task 4: resolve → download → persist over `offlineStore`. One background session
    /// (`ProgressiveEngine.backgroundSessionIdentifier`); `.prefetch` lane on the ONE limiter/clock
    /// (reconciliation note 4); the cellular gate reads `settings`/`network` live (note 6); the
    /// per-video gate closure is `offlineGate` (Task 5), whose `.unreachable`-on-error keeps the
    /// sweep fail-open.
    private(set) lazy var offlineManager: OfflineManager = makeOfflineManager()

    private func makeOfflineManager() -> OfflineManager {
        let configuration = URLSessionConfiguration.background(withIdentifier: ProgressiveEngine.backgroundSessionIdentifier)
        configuration.sessionSendsLaunchEvents = true
        let base = URL.applicationSupportDirectory
        let manager = OfflineManager(
            store: offlineStore,
            engine: ProgressiveEngine(directory: OfflineStorage.directoryURL(base: base), configuration: configuration),
            resolver: LiveStreamResolver(resolver: resolver),
            limiterCheck: { [innerTube] in await innerTube.rateLimiter.check($0, kind: .prefetch, now: innerTube.clock.now) },
            wifiOnly: { [settings] in settings.wifiOnlyDownloads },
            isOnCellular: { [network] in network.isOnCellular },
            baseDirectory: base,
            gate: { [offlineGate] in await offlineGate.answer($0) },
            now: { Date() })
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
         playlistHeader: (@Sendable (String) async throws -> PlaylistHeader)? = nil) {
        self.catalog = catalog
        self.userDefaults = userDefaults
        self.modelContainer = modelContainer
        self.apiBaseURL = apiBaseURL
        self.injectedBrowse = browse
        self.degradedHeader = degradedHeader
        self.playlistHeader = playlistHeader
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
    static func fake(
        catalog: any CatalogClient = FakeCatalogClient(),
        // `?? .standard`: `UserDefaults(suiteName:)` returns nil for a suite name equal to the
        // bundle identifier or a reserved domain -- a trap in a default-argument position, far
        // from any call site (gate A-M15). "fitrahtube.fake" is safe today; this keeps it latent.
        defaults: UserDefaults = UserDefaults(suiteName: "fitrahtube.fake") ?? .standard,
        browse: any BrowseSource = FakeBrowseSource()
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
        AppContainer(catalog: catalog, userDefaults: defaults, modelContainer: makeModelContainer(inMemory: true),
                     apiBaseURL: AppConfig.apiBaseURL, browse: browse)
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
        let schema = Schema(versionedSchema: FavoritesSchemaV4.self)
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
        return fake(defaults: defaults, browse: FakeBrowseSource.fromLaunchArguments())
    }()
    #endif
}

#if DEBUG
/// `-fitrah-fake-report <status>`: one canned status for every request, no network.
private struct FixedStatusTransport: HTTPTransport {
    let status: Int
    func send(_ request: HTTPRequest) async throws -> HTTPResponse { HTTPResponse(status: status, headers: [:], body: Data()) }
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
