import Foundation
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct SettingsStoreTests {
    private func makeStore() -> (UserDefaultsSettingsStore, UserDefaults, String) {
        let suiteName = "SettingsStoreTests.\(UUID().uuidString)"
        let defaults = UserDefaults(suiteName: suiteName)!
        return (UserDefaultsSettingsStore(defaults: defaults), defaults, suiteName)
    }

    @Test func defaultsMatchAndroidBuildDefaults() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        #expect(store.appLocale == "system")
        #expect(store.theme == "system")
        #expect(store.audioOnly == false)
        #expect(store.backgroundPlay == true)
        #expect(store.safeMode == true)
        #expect(store.downloadQuality == "medium")
        #expect(store.wifiOnlyDownloads == false)
        #expect(store.onboardingCompleted == false)
        #expect(store.importOfferShown == false)
    }

    @Test func writesPersistUnderVerbatimAndroidKeys() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.appLocale = "ar"
        store.theme = "dark"
        store.audioOnly = true
        store.backgroundPlay = false
        store.safeMode = false
        store.downloadQuality = "high"
        store.wifiOnlyDownloads = true
        store.onboardingCompleted = true
        store.importOfferShown = true

        #expect(defaults.string(forKey: "app_locale") == "ar")
        #expect(defaults.string(forKey: "theme") == "dark")
        #expect(defaults.bool(forKey: "audio_only") == true)
        #expect(defaults.bool(forKey: "background_play") == false)
        #expect(defaults.bool(forKey: "safe_mode") == false)
        #expect(defaults.string(forKey: "download_quality") == "high")
        #expect(defaults.bool(forKey: "wifi_only_downloads") == true)
        #expect(defaults.bool(forKey: "onboarding_completed") == true)
        #expect(defaults.bool(forKey: "import_offer_shown") == true)
    }

    @Test func rereadingFromDefaultsRestoresPersistedValues() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.appLocale = "nl"
        store.safeMode = false

        let reloaded = UserDefaultsSettingsStore(defaults: defaults)
        #expect(reloaded.appLocale == "nl")
        #expect(reloaded.safeMode == false)
        // Untouched keys keep their build defaults.
        #expect(reloaded.backgroundPlay == true)
    }

    @Test func resolvedLocaleUsesExplicitSelectionVerbatim() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        store.appLocale = "ar"
        #expect(store.resolvedLocale.language.languageCode?.identifier == "ar")
    }

    @Test func resolvedLocaleFallsBackToSupportedSetWhenSystem() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        // appLocale defaults to "system" — resolution must land on one of the three supported
        // languages (en/ar/nl), never an arbitrary system code, per the {en,ar,nl}-else-en rule.
        #expect(store.appLocale == "system")
        let resolved = store.resolvedLocale.language.languageCode?.identifier
        #expect(["en", "ar", "nl"].contains(resolved ?? ""))
    }

    @Test func colorSchemeMapsThemeSelection() {
        let (store, defaults, suiteName) = makeStore()
        defer { defaults.removePersistentDomain(forName: suiteName) }

        #expect(store.colorScheme == nil) // "system"
        store.theme = "light"
        #expect(store.colorScheme == .light)
        store.theme = "dark"
        #expect(store.colorScheme == .dark)
    }
}
