import FitrahAPI
import Foundation
import SwiftUI

nonisolated enum AppConfig {
    /// From Info.plist key `API_BASE_URL`, set per configuration in ios/Config/*.xcconfig.
    static var apiBaseURL: URL {
        guard let raw = Bundle.main.object(forInfoDictionaryKey: "API_BASE_URL") as? String,
              let url = URL(string: raw) else {
            preconditionFailure("API_BASE_URL missing from Info.plist — check ios/Config/*.xcconfig")
        }
        return url
    }
}

/// Composition root. Built once in `FitrahTubeApp`; every ViewModel receives what it needs from here
/// through its initializer (Hilt's constructor injection, without a framework).
@MainActor final class AppContainer {
    let catalog: any CatalogClient

    nonisolated init(catalog: any CatalogClient) {
        self.catalog = catalog
    }

    static func live(baseURL: URL = AppConfig.apiBaseURL) -> AppContainer {
        let api = FitrahAPIClient.make(baseURL: baseURL, deviceId: .persisted())
        return AppContainer(catalog: LiveCatalogClient(client: api))
    }

    nonisolated static func fake(catalog: any CatalogClient = FakeCatalogClient()) -> AppContainer {
        AppContainer(catalog: catalog)
    }
}

extension EnvironmentValues {
    // nonisolated fake() keeps the environment default trap-free; see spec §5.
    // Release must not ship the fake default silently -- an un-injected .container in Release
    // traps instead of serving fake data.
    #if DEBUG
    @Entry var container: AppContainer = .fake()   // previews / tests
    #else
    @Entry var container: AppContainer = { preconditionFailure("AppContainer not injected — wrap the root in .environment(\\.container, …)") }()
    #endif
}
