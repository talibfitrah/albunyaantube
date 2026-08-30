import SwiftUI
import UIKit

/// Pure row/section layout for `SettingsView`, tested by `SettingsRowsTests` without a live view
/// (this project has no ViewInspector/snapshot dependency). Sections are Android's literal order
/// (`favorites-settings-about.md:139-153`): General -> Library -> Playback -> Downloads ->
/// Content -> About & Support, minus the phase-3/none rows (RULINGS 32-35). RULINGS.md line 3
/// makes parity the default; the task-13 brief's "in Android order minus ..." is an order
/// instruction, not licence to regroup Safe Mode into Playback or move Library.
nonisolated enum SettingsSection: CaseIterable, Hashable {
    case general, library, playback, downloads, content, aboutSupport

    var titleKey: String {
        switch self {
        case .general: "settings_general"
        case .library: "settings_library_header"
        case .playback: "settings_playback"
        case .downloads: "settings_downloads"
        case .content: "settings_content"
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
        Row(section: .library, row: .favorites),
        Row(section: .playback, row: .audioOnly),
        Row(section: .playback, row: .backgroundPlay),
        Row(section: .downloads, row: .downloadQuality),
        Row(section: .downloads, row: .wifiOnly),
        Row(section: .content, row: .safeMode),
        Row(section: .aboutSupport, row: .aboutSupport),
    ]
}

/// One option in a settings selection sheet. `Hashable` so `List(_:id:)` can identify it without
/// an extra `Identifiable` conformance.
nonisolated struct SettingsPickerOption: Hashable {
    let value: String
    let labelKey: String
}

/// `favorites-settings-about.md:195`: Material single-choice list -- a **checkmark on the current
/// selection**, tap-to-commit-and-dismiss, no OK button, Cancel only.
///
/// A sheet+`List` rather than a menu/navigationLink `Picker` because those render the *selected
/// option's own label* back in the row, and the contract (§2.3) requires the row to show the
/// **resolved** value ("System default (Light)") while the options show the plain ones
/// ("System default"). Keeping the custom row is what preserves that distinction.
private struct SettingsPickerSheet: View {
    let titleKey: String
    let options: [SettingsPickerOption]
    @Binding var selection: String

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(options, id: \.self) { option in
                Button {
                    selection = option.value
                    dismiss()
                } label: {
                    HStack {
                        Text(String(localized: String.LocalizationValue(option.labelKey)))
                            .foregroundStyle(Color.textPrimary)
                        Spacer()
                        if option.value == selection {
                            Image(systemName: "checkmark")
                                .foregroundStyle(Color.brand)
                                .accessibilityHidden(true) // the row carries `.isSelected` instead
                        }
                    }
                    .contentShape(Rectangle())
                }
                .buttonStyle(.plain)
                .accessibilityAddTraits(option.value == selection ? [.isSelected] : [])
            }
            .navigationTitle(String(localized: String.LocalizationValue(titleKey)))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "cancel")) { dismiss() }
                }
            }
        }
        .presentationDetents([.medium])
        // Without this the sheet keeps the default translucent material and the Settings rows
        // underneath show through the option list -- `background_gray`, same ground the Settings
        // screen itself sits on.
        .presentationBackground(Color.background)
    }
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
    /// task-14 (`screenshots/task-14/iphone-17/settings-en-light-a11y3-portrait.png`): the row
    /// symbol scales with Dynamic Type but its 28 pt circular plate did not, so at
    /// `.accessibility3` the glyph overflowed the plate on every row.
    @ScaledMetric(relativeTo: .body) private var rowIconSize: CGFloat = 28

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
        .sheet(isPresented: $showThemePicker) {
            SettingsPickerSheet(
                titleKey: "settings_theme_select_title",
                options: [
                    SettingsPickerOption(value: "system", labelKey: "settings_theme_system"),
                    SettingsPickerOption(value: "light", labelKey: "settings_theme_light"),
                    SettingsPickerOption(value: "dark", labelKey: "settings_theme_dark"),
                ],
                selection: Binding(get: { settings.theme }, set: { settings.theme = $0 })
            )
        }
        .sheet(isPresented: $showQualityPicker) {
            // Sheet options use the `_desc` long-form labels; the row value uses the short form (contract §2.5).
            SettingsPickerSheet(
                titleKey: "settings_download_quality_title",
                options: [
                    SettingsPickerOption(value: "low", labelKey: "settings_quality_low_desc"),
                    SettingsPickerOption(value: "medium", labelKey: "settings_quality_medium_desc"),
                    SettingsPickerOption(value: "high", labelKey: "settings_quality_high_desc"),
                ],
                selection: Binding(get: { settings.downloadQuality }, set: { settings.downloadQuality = $0 })
            )
        }
        .task {
            #if DEBUG
            // Acceptance-screenshot hook (task-13): the theme picker otherwise only opens after a
            // real tap on the Theme row, which `simctl launch` can't perform -- same technique as
            // FavoritesView's `-fitrah-show-clear-all-confirm`.
            if LaunchArguments.debug.contains("-fitrah-show-theme-picker") {
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
            .frame(width: rowIconSize, height: rowIconSize)
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
            // The row's title, not "" (gate B1-I6). `.labelsHidden()` hides a label *visually*
            // while preserving it for accessibility -- the empty string was the defect, and since
            // the title `Text` and the switch are separate accessibility elements, a VoiceOver
            // user swiping onto the control heard "Switch button, On" with no idea which of the
            // five settings it was.
            Toggle(String(localized: String.LocalizationValue(row.titleKey)), isOn: isOn).labelsHidden()
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

    /// Gate wave-4 V11: resolved from `\.locale` -- the locale this screen is actually rendering
    /// in -- not from `settings.resolvedLocale`, which re-derives the same answer from
    /// `Locale.preferredLanguages` against a hardcoded {en,ar,nl} set. Two derivations of one fact
    /// can disagree (they already do under any `\.locale` override, e.g. the RTL preview below,
    /// which renders Arabic while this row reported the simulator's system language), and a row
    /// naming a different language than the screen around it is worse than no row.
    ///
    /// Always the "System (X)" form: RULING 33 removed the in-app picker, so nothing writes
    /// `appLocale` and the explicit-selection branch that used to guard this was dead. Phase 4
    /// restores both together if a picker ever returns.
    private var languageValue: String {
        let native = nativeName(for: locale.language.languageCode?.identifier ?? "en")
        return Format.localizedFormat("settings_language_system_resolved", locale: locale, native)
    }

    private var themeValue: String {
        switch settings.theme {
        case "light": return String(localized: "settings_theme_light")
        case "dark": return String(localized: "settings_theme_dark")
        case "system":
            let resolved = colorScheme == .dark ? String(localized: "settings_theme_dark") : String(localized: "settings_theme_light")
            return Format.localizedFormat("settings_theme_system_resolved", locale: locale, resolved)
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

}

#if DEBUG
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
#endif
