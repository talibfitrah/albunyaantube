import Foundation
import Observation
import SwiftUI

/// Keys and defaults are Android's `SettingsPreferences` DataStore keys, kept verbatim for
/// parity/debuggability (`docs/superpowers/plans/2026-08-23-ios-phase1-research/favorites-settings-about.md:322-350`).
/// `UserDefaults` is already synchronous, so Android's separate cold-start `settings_cache`
/// `SharedPreferences` has no iOS counterpart -- see that contract's §4.1.
@MainActor protocol SettingsStore: AnyObject, Observable {
    var appLocale: String { get set }           // "system" | "en" | "ar" | "nl"
    var theme: String { get set }                // "system" | "light" | "dark"
    var audioOnly: Bool { get set }
    var backgroundPlay: Bool { get set }
    var safeMode: Bool { get set }
    var downloadQuality: String { get set }      // "low" | "medium" | "high"
    var wifiOnlyDownloads: Bool { get set }
    var onboardingCompleted: Bool { get set }
    var importOfferShown: Bool { get set }

    /// `selection == "system" ? systemLocale() : selection`, where `systemLocale()` walks
    /// `Locale.preferredLanguages` (the user's priority order) for the first language code in
    /// {en, ar, nl}, else "en" -- contract §4.2. Deliberately not `Locale.current`, which already
    /// reflects this app's own override and would feed back into the resolution.
    var resolvedLocale: Locale { get }
    /// nil for "system" so the view inherits the environment's scheme.
    var colorScheme: ColorScheme? { get }
}

@MainActor @Observable final class UserDefaultsSettingsStore: SettingsStore {
    private enum Keys {
        static let appLocale = "app_locale"
        static let theme = "theme"
        static let audioOnly = "audio_only"
        static let backgroundPlay = "background_play"
        static let safeMode = "safe_mode"
        static let downloadQuality = "download_quality"
        static let wifiOnlyDownloads = "wifi_only_downloads"
        static let onboardingCompleted = "onboarding_completed"
        static let importOfferShown = "import_offer_shown"
    }

    private let defaults: UserDefaults

    var appLocale: String { didSet { defaults.set(appLocale, forKey: Keys.appLocale) } }
    var theme: String { didSet { defaults.set(theme, forKey: Keys.theme) } }
    var audioOnly: Bool { didSet { defaults.set(audioOnly, forKey: Keys.audioOnly) } }
    var backgroundPlay: Bool { didSet { defaults.set(backgroundPlay, forKey: Keys.backgroundPlay) } }
    var safeMode: Bool { didSet { defaults.set(safeMode, forKey: Keys.safeMode) } }
    var downloadQuality: String { didSet { defaults.set(downloadQuality, forKey: Keys.downloadQuality) } }
    var wifiOnlyDownloads: Bool { didSet { defaults.set(wifiOnlyDownloads, forKey: Keys.wifiOnlyDownloads) } }
    var onboardingCompleted: Bool { didSet { defaults.set(onboardingCompleted, forKey: Keys.onboardingCompleted) } }
    var importOfferShown: Bool { didSet { defaults.set(importOfferShown, forKey: Keys.importOfferShown) } }

    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        defaults.register(defaults: [
            Keys.appLocale: "system",
            Keys.theme: "system",
            Keys.backgroundPlay: true,
            Keys.safeMode: true,
            Keys.downloadQuality: "medium",
        ])
        appLocale = defaults.string(forKey: Keys.appLocale) ?? "system"
        theme = defaults.string(forKey: Keys.theme) ?? "system"
        audioOnly = defaults.bool(forKey: Keys.audioOnly)
        backgroundPlay = defaults.bool(forKey: Keys.backgroundPlay)
        safeMode = defaults.bool(forKey: Keys.safeMode)
        downloadQuality = defaults.string(forKey: Keys.downloadQuality) ?? "medium"
        wifiOnlyDownloads = defaults.bool(forKey: Keys.wifiOnlyDownloads)
        onboardingCompleted = defaults.bool(forKey: Keys.onboardingCompleted)
        importOfferShown = defaults.bool(forKey: Keys.importOfferShown)
    }

    var resolvedLocale: Locale {
        Locale(identifier: appLocale == "system" ? Self.systemLocaleCode() : appLocale)
    }

    var colorScheme: ColorScheme? {
        switch theme {
        case "light": .light
        case "dark": .dark
        default: nil
        }
    }

    private nonisolated static let supportedLanguages: Set<String> = ["en", "ar", "nl"]

    /// `preferredLanguages` is injectable so the priority-order walk (first match wins, region
    /// ignored) is testable without depending on the running simulator's actual system languages.
    nonisolated static func systemLocaleCode(preferredLanguages: [String] = Locale.preferredLanguages) -> String {
        for preference in preferredLanguages {
            if let code = Locale(identifier: preference).language.languageCode?.identifier,
               supportedLanguages.contains(code) {
                return code
            }
        }
        return "en"
    }
}
