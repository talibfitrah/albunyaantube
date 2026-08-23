import FitrahAPI
import Foundation
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

    private(set) lazy var settings: any SettingsStore = UserDefaultsSettingsStore(defaults: userDefaults)
    private(set) lazy var filters: any FilterStore = UserDefaultsFilterStore(defaults: userDefaults)
    private(set) lazy var searchHistory: any SearchHistoryStore = UserDefaultsSearchHistoryStore(defaults: userDefaults)
    private(set) lazy var favorites: any FavoritesStore = SwiftDataFavoritesStore(modelContainer: modelContainer)
    private(set) lazy var categories: any CategoriesCache = LiveCategoriesCache(client: catalog)
    private(set) lazy var network = NetworkMonitor()

    init(catalog: any CatalogClient, userDefaults: UserDefaults = .standard, modelContainer: ModelContainer) {
        self.catalog = catalog
        self.userDefaults = userDefaults
        self.modelContainer = modelContainer
    }

    static func live(baseURL: URL = AppConfig.apiBaseURL) -> AppContainer {
        let api = FitrahAPIClient.make(baseURL: baseURL, deviceId: .persisted())
        return AppContainer(catalog: LiveCatalogClient(client: api), modelContainer: makeModelContainer(inMemory: false))
    }

    #if DEBUG
    static func fake(
        catalog: any CatalogClient = FakeCatalogClient(),
        // `?? .standard`: `UserDefaults(suiteName:)` returns nil for a suite name equal to the
        // bundle identifier or a reserved domain -- a trap in a default-argument position, far
        // from any call site (gate A-M15). "fitrahtube.fake" is safe today; this keeps it latent.
        defaults: UserDefaults = UserDefaults(suiteName: "fitrahtube.fake") ?? .standard
    ) -> AppContainer {
        // A private suite (not `.standard`) so previews/tests never read or write the app's real
        // defaults domain. Does NOT wipe the suite -- callers that write through the returned
        // container's stores (settings/filters/favorites/search history) must pass their own
        // suite with their own teardown, or repeated calls sharing the default suite name would
        // leak state between them. `sharedFake` wipes its suite once, at creation.
        AppContainer(catalog: catalog, userDefaults: defaults, modelContainer: makeModelContainer(inMemory: true))
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
        let schema = Schema(versionedSchema: FavoritesSchemaV1.self)
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
        return fake(defaults: defaults)
    }()
    #endif
}

extension EnvironmentValues {
    // Release must not ship the fake default silently -- an un-injected .container in Release
    // traps instead of serving fake data.
    #if DEBUG
    @Entry var container: AppContainer = AppContainer.sharedFake   // previews / tests
    #else
    @Entry var container: AppContainer = { preconditionFailure("AppContainer not injected — wrap the root in .environment(\\.container, …)") }()
    #endif
}
