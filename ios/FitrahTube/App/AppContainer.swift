import FitrahAPI
import Foundation
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

    private(set) lazy var settings: any SettingsStore = UserDefaultsSettingsStore(defaults: userDefaults)
    private(set) lazy var filters: any FilterStore = UserDefaultsFilterStore(defaults: userDefaults)
    private(set) lazy var searchHistory: any SearchHistoryStore = UserDefaultsSearchHistoryStore(defaults: userDefaults)
    private(set) lazy var network = NetworkMonitor()

    init(catalog: any CatalogClient, userDefaults: UserDefaults = .standard) {
        self.catalog = catalog
        self.userDefaults = userDefaults
    }

    static func live(baseURL: URL = AppConfig.apiBaseURL) -> AppContainer {
        let api = FitrahAPIClient.make(baseURL: baseURL, deviceId: .persisted())
        return AppContainer(catalog: LiveCatalogClient(client: api))
    }

    static func fake(catalog: any CatalogClient = FakeCatalogClient()) -> AppContainer {
        // A private suite (not `.standard`) so previews/tests never read or write the app's real
        // defaults domain. Fixed name rather than a fresh UUID per call, so repeated preview/test
        // runs reuse one plist instead of leaving an orphan behind every time; wiped here before
        // the (lazy) stores can read/write it, so no state leaks between runs. Falls back to
        // `.standard` only if suite creation fails.
        let suiteName = "fitrahtube.fake"
        let defaults = UserDefaults(suiteName: suiteName) ?? .standard
        defaults.removePersistentDomain(forName: suiteName)
        return AppContainer(catalog: catalog, userDefaults: defaults)
    }
}

extension EnvironmentValues {
    // Release must not ship the fake default silently -- an un-injected .container in Release
    // traps instead of serving fake data.
    #if DEBUG
    @Entry var container: AppContainer = .fake()   // previews / tests
    #else
    @Entry var container: AppContainer = { preconditionFailure("AppContainer not injected — wrap the root in .environment(\\.container, …)") }()
    #endif
}
