import Foundation
import Observation
import SwiftUI

/// Keys and defaults are Android's `SettingsPreferences` DataStore keys, kept verbatim for
/// parity/debuggability (`docs/superpowers/plans/2026-08-23-ios-phase1-research/favorites-settings-about.md:322-350`).
/// `UserDefaults` is already synchronous, so Android's separate cold-start `settings_cache`
/// `SharedPreferences` has no iOS counterpart -- see that contract's §4.1.
@MainActor protocol SettingsStore: AnyObject, Observable {
    /// RULING 33 removed the in-app language picker (the iOS per-app language setting is the only
    /// way to change it), so nothing in phase 1 writes this and `resolvedLocale` is always its
    /// "system" arm -- the Settings screen reads the locale it is rendering in instead (gate
    /// wave-4 V11). Both are kept, not deleted: the key is Android-parity persisted state and the
    /// resolution is the contract's §4.2 rule, restored together by phase 4's picker.
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

    /// Build defaults are read per-key here rather than through `UserDefaults.register(defaults:)`
    /// -- registration installs into the process-global `NSRegistrationDomain`, which every
    /// suite/instance in the process reads, so one store's defaults would leak into every other
    /// suite (e.g. two `fake()` containers with different intended defaults). Keys with no default
    /// (audioOnly, wifiOnlyDownloads, onboardingCompleted, importOfferShown) fall to `false`.
    ///
    /// The three string keys read through `string(forKey:) ?? default` rather than probing
    /// `object(forKey:) == nil` and force-unwrapping (gate A-I6): `object(forKey:) != nil` does
    /// **not** imply `string(forKey:) != nil` -- the latter returns nil for an array, dictionary or
    /// data value. Any wrong-typed value under one of these keys (a stale value from an earlier
    /// build, an MDM-pushed managed configuration, a hand-edited plist) trapped during
    /// `AppContainer.settings`'s lazy init, i.e. at launch, with no recovery. Identical behaviour
    /// for a missing key; total for a wrong-typed one.
    init(defaults: UserDefaults = .standard) {
        self.defaults = defaults
        appLocale = defaults.string(forKey: Keys.appLocale) ?? "system"
        theme = defaults.string(forKey: Keys.theme) ?? "system"
        audioOnly = defaults.bool(forKey: Keys.audioOnly)
        backgroundPlay = defaults.object(forKey: Keys.backgroundPlay) == nil ? true : defaults.bool(forKey: Keys.backgroundPlay)
        safeMode = defaults.object(forKey: Keys.safeMode) == nil ? true : defaults.bool(forKey: Keys.safeMode)
        downloadQuality = defaults.string(forKey: Keys.downloadQuality) ?? "medium"
        wifiOnlyDownloads = defaults.bool(forKey: Keys.wifiOnlyDownloads)
        onboardingCompleted = defaults.bool(forKey: Keys.onboardingCompleted)
        importOfferShown = defaults.bool(forKey: Keys.importOfferShown)
        #if DEBUG
        // Acceptance artefact hook (task 8): reach Onboarding on a simulator that already
        // completed it, without wiping the rest of its state. Same launch-arg pattern as
        // `NetworkMonitor`'s `-fitrah-offline`; unlike that one, this assignment runs through
        // the `didSet` below (it's not the *first* assignment to the property, so the observer
        // does fire) and genuinely persists `false` -- a real reset, not just a one-run fake.
        if LaunchArguments.debug.contains("-fitrah-reset-onboarding") {
            onboardingCompleted = false
        }
        #endif
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
