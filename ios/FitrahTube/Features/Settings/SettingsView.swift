import SwiftUI
import UIKit

/// Pure row/section layout for `SettingsView`, tested by `SettingsRowsTests` without a live view
/// (this project has no ViewInspector/snapshot dependency). Order follows the task-13 brief
/// verbatim (`favorites-settings-about.md:139-158` minus phase-3/none rows; RULINGS 32-35), which
/// reorders two of Android's six sections: Safe Mode (Android's own single-row "Content" section)
/// folds into Playback, and Library -- one row once Downloads library is dropped -- moves from
/// right after General to just before About & Support.
nonisolated enum SettingsSection: CaseIterable, Hashable {
    case general, playback, downloads, library, aboutSupport

    var titleKey: String {
        switch self {
        case .general: "settings_general"
        case .playback: "settings_playback"
        case .downloads: "settings_downloads"
        case .library: "settings_library_header"
        case .aboutSupport: "settings_about_support"
        }
    }
}

nonisolated enum SettingsRow: Hashable {
    case language, theme
    case audioOnly, backgroundPlay, safeMode
    case downloadQuality, wifiOnly
    case favorites
    case aboutSupport

    /// SF Symbols by meaning, per the contract's own mapping table (`favorites-settings-about.md:162`).
    var symbolName: String {
        switch self {
        case .language: "globe"
        case .theme: "circle.lefthalf.filled"
        case .audioOnly: "speaker.wave.2"
        case .backgroundPlay: "play.circle"
        case .safeMode: "shield"
        case .downloadQuality: "arrow.down.circle"
        case .wifiOnly: "wifi"
        case .favorites: "heart"
        case .aboutSupport: "questionmark.circle"
        }
    }

    var titleKey: String {
        switch self {
        case .language: "settings_language"
        case .theme: "settings_theme"
        case .audioOnly: "settings_audio_only"
        case .backgroundPlay: "settings_background_play"
        case .safeMode: "settings_safe_mode"
        case .downloadQuality: "settings_download_quality"
        case .wifiOnly: "settings_wifi_only"
        case .favorites: "settings_favorites_title"
        case .aboutSupport: "settings_support_center" // row inside the "About & Support" section
        }
    }

    var descriptionKey: String? {
        switch self {
        case .audioOnly: "settings_audio_only_desc"
        case .backgroundPlay: "settings_background_play_desc"
        case .safeMode: "settings_safe_mode_desc"
        case .wifiOnly: "settings_wifi_only_desc"
        case .language, .theme, .downloadQuality, .favorites, .aboutSupport: nil
        }
    }
}

nonisolated enum SettingsLayout {
    struct Row: Equatable { let section: SettingsSection; let row: SettingsRow }

    static let rows: [Row] = [
        Row(section: .general, row: .language),
        Row(section: .general, row: .theme),
        Row(section: .playback, row: .audioOnly),
        Row(section: .playback, row: .backgroundPlay),
        Row(section: .playback, row: .safeMode),
        Row(section: .downloads, row: .downloadQuality),
        Row(section: .downloads, row: .wifiOnly),
        Row(section: .library, row: .favorites),
        Row(section: .aboutSupport, row: .aboutSupport),
    ]
}

/// Android's `SettingsFragment` (`favorites-settings-about.md:139-253`). Account/Sign-out is
/// omitted entirely: phase 1 has no signed-in state to show it for (spec D11: guest-only until
/// phase 4 auth), so "hidden unless signed in" holds with nothing that ever signs in yet --
/// `SettingsRowsTests.nineRowsInFiveSectionsNoAccountSection` proves this by construction.
struct SettingsView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    @State private var showThemePicker = false
    @State private var showQualityPicker = false

    private var settings: any SettingsStore { container.settings }

    var body: some View {
        Form {
            ForEach(SettingsSection.allCases, id: \.self) { section in
                Section(String(localized: String.LocalizationValue(section.titleKey))) {
                    ForEach(SettingsLayout.rows.filter { $0.section == section }.map(\.row), id: \.self) { row in
                        rowView(row)
                    }
                }
            }
        }
        .navigationTitle(String(localized: "settings_title"))
        .navigationBarTitleDisplayMode(.inline)
        // favorites-settings-about.md:195 -- Material single-choice list, tap-to-commit, no OK
        // button, Cancel only; `.confirmationDialog` is the native equivalent.
        .confirmationDialog(String(localized: "settings_theme_select_title"), isPresented: $showThemePicker, titleVisibility: .visible) {
            Button(String(localized: "settings_theme_system")) { settings.theme = "system" }
            Button(String(localized: "settings_theme_light")) { settings.theme = "light" }
            Button(String(localized: "settings_theme_dark")) { settings.theme = "dark" }
            Button(String(localized: "cancel"), role: .cancel) {}
        }
        .confirmationDialog(String(localized: "settings_download_quality_title"), isPresented: $showQualityPicker, titleVisibility: .visible) {
            // Dialog options use the `_desc` long-form labels; the row value uses the short form (contract §2.5).
            Button(String(localized: "settings_quality_low_desc")) { settings.downloadQuality = "low" }
            Button(String(localized: "settings_quality_medium_desc")) { settings.downloadQuality = "medium" }
            Button(String(localized: "settings_quality_high_desc")) { settings.downloadQuality = "high" }
            Button(String(localized: "cancel"), role: .cancel) {}
        }
        .task {
            #if DEBUG
            // Acceptance-screenshot hook (task-13): the theme picker otherwise only opens after a
            // real tap on the Theme row, which `simctl launch` can't perform -- same technique as
            // FavoritesView's `-fitrah-show-clear-all-confirm`.
            if ProcessInfo.processInfo.arguments.contains("-fitrah-show-theme-picker") {
                showThemePicker = true
            }
            #endif
        }
    }

    @ViewBuilder
    private func rowView(_ row: SettingsRow) -> some View {
        switch row {
        case .language:
            actionRow(row, value: languageValue, action: openAppSettings)
        case .theme:
            actionRow(row, value: themeValue) { showThemePicker = true }
        case .audioOnly:
            toggleRow(row, isOn: Binding(get: { settings.audioOnly }, set: { settings.audioOnly = $0 }))
        case .backgroundPlay:
            toggleRow(row, isOn: Binding(get: { settings.backgroundPlay }, set: { settings.backgroundPlay = $0 }))
        case .safeMode:
            toggleRow(row, isOn: Binding(get: { settings.safeMode }, set: { settings.safeMode = $0 }))
        case .downloadQuality:
            actionRow(row, value: qualityValue) { showQualityPicker = true }
        case .wifiOnly:
            toggleRow(row, isOn: Binding(get: { settings.wifiOnlyDownloads }, set: { settings.wifiOnlyDownloads = $0 }))
        case .favorites:
            actionRow(row, value: nil) { router.push(.favorites) }
        case .aboutSupport:
            actionRow(row, value: nil) { router.push(.about) }
        }
    }

    // MARK: - Row chrome

    private func rowIcon(_ row: SettingsRow) -> some View {
        Image(systemName: row.symbolName)
            .foregroundStyle(Color.brand)
            .frame(width: 28, height: 28)
            .background(Color.settingsIconBackground, in: Circle())
    }

    private func rowLabel(_ row: SettingsRow) -> some View {
        VStack(alignment: .leading, spacing: Spacing.xxs) {
            Text(String(localized: String.LocalizationValue(row.titleKey)))
                .foregroundStyle(Color.textPrimary)
            if let descKey = row.descriptionKey {
                Text(String(localized: String.LocalizationValue(descKey)))
                    .font(.caption)
                    .foregroundStyle(Color.textSecondary)
            }
        }
    }

    private func toggleRow(_ row: SettingsRow, isOn: Binding<Bool>) -> some View {
        HStack(spacing: Spacing.sm) {
            rowIcon(row)
            rowLabel(row)
            Spacer()
            Toggle("", isOn: isOn).labelsHidden()
        }
    }

    private func actionRow(_ row: SettingsRow, value: String?, action: @escaping () -> Void) -> some View {
        Button(action: action) {
            HStack(spacing: Spacing.sm) {
                rowIcon(row)
                rowLabel(row)
                Spacer()
                if let value {
                    Text(value)
                        .foregroundStyle(Color.textSecondary)
                        .lineLimit(1)
                }
                Image(systemName: "chevron.forward") // direction-sensitive SF Symbol -- auto-mirrors in RTL
                    .font(.caption)
                    .foregroundStyle(Color.textMuted)
                    .accessibilityHidden(true)
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
    }

    // MARK: - Row values

    private var languageValue: String {
        let native = nativeName(for: settings.resolvedLocale.language.languageCode?.identifier ?? "en")
        guard settings.appLocale == "system" else { return native }
        return localizedFormat("settings_language_system_resolved", native)
    }

    private var themeValue: String {
        switch settings.theme {
        case "light": return String(localized: "settings_theme_light")
        case "dark": return String(localized: "settings_theme_dark")
        case "system":
            let resolved = colorScheme == .dark ? String(localized: "settings_theme_dark") : String(localized: "settings_theme_light")
            return localizedFormat("settings_theme_system_resolved", resolved)
        default: return String(localized: "settings_theme_system")
        }
    }

    private var qualityValue: String {
        switch settings.downloadQuality {
        case "low": String(localized: "settings_quality_low")
        case "high": String(localized: "settings_quality_high")
        default: String(localized: "settings_quality_medium")
        }
    }

    /// Android's `LANGUAGE_NATIVE_NAMES` (`locale/LocaleManager.kt:39-43`), always shown in its
    /// own language regardless of the app's current locale -- not a translatable string.
    private func nativeName(for code: String) -> String {
        switch code {
        case "en": "English"
        case "ar": "العربية"
        case "nl": "Nederlands"
        default: code.uppercased()
        }
    }

    /// RULING 33: no in-app language picker on iOS -- tapping Language deep-links to the system
    /// Settings app instead.
    private func openAppSettings() {
        guard let url = URL(string: UIApplication.openSettingsURLString) else { return }
        openURL(url)
    }

    /// Same per-file pattern as `FavoritesView`/`SearchView`/etc.: resolves the `.lproj` bundle for
    /// `\.locale` so `%1$@` substitutes correctly regardless of the simulator's system language.
    /// Safe here because both keys this is called with (`settings_theme_system_resolved`,
    /// `settings_language_system_resolved`) are fully translated in en/ar/nl -- unlike About's
    /// English-only keys, which must NOT use this (see `AboutView.swift`'s own note).
    private func localizedFormat(_ key: String, _ args: CVarArg...) -> String {
        let format = Format.localizedBundle(for: locale).localizedString(forKey: key, value: nil, table: nil)
        return String(format: format, locale: locale, arguments: args)
    }
}

#Preview {
    NavigationStack { SettingsView() }
        .environment(\.container, .sharedFake)
}

#Preview("RTL") {
    NavigationStack { SettingsView() }
        .environment(\.container, .sharedFake)
        .environment(\.locale, Locale(identifier: "ar"))
        .environment(\.layoutDirection, .rightToLeft)
}
