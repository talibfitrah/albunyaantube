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
    // Phase 3 Task 6: the Save-for-offline section's library/storage/clear rows.
    case savedLibrary, storage, clearOffline
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
        case .savedLibrary: "checkmark.circle"
        case .storage: "internaldrive"
        case .clearOffline: "trash"
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
        case .savedLibrary: "offline_saved_title"
        case .storage: "settings_offline_storage"
        case .clearOffline: "settings_offline_clear"
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
        case .language, .theme, .downloadQuality, .savedLibrary, .storage, .clearOffline,
             .favorites, .aboutSupport: nil
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
        Row(section: .downloads, row: .savedLibrary),
        Row(section: .downloads, row: .storage),
        Row(section: .downloads, row: .clearOffline),
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
///
/// Not `private` (Phase 3 fold-in): `SaveOfflineSheet` was a verbatim copy of this body — same
/// `List`, checkmark row, `.isSelected` trait, detents and background. Its one real difference is
/// `confirm`.
struct SettingsPickerSheet: View {
    let titleKey: String
    let options: [SettingsPickerOption]
    @Binding var selection: String
    /// An explicit confirmation button, and with it tap-to-select-WITHOUT-dismissing: the
    /// Save-for-offline sheet must never fire on a row tap (every video save POSTs a real walk).
    /// nil keeps the Material single-choice behaviour above — tap commits and dismisses.
    var confirm: (titleKey: String, action: () -> Void)? = nil

    @Environment(\.dismiss) private var dismiss

    var body: some View {
        NavigationStack {
            List(options, id: \.self) { option in
                Button {
                    selection = option.value
                    if confirm == nil { dismiss() }
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
                if let confirm {
                    ToolbarItem(placement: .confirmationAction) {
                        Button(String(localized: String.LocalizationValue(confirm.titleKey)),
                               action: confirm.action)
                    }
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
/// `SettingsRowsTests.twelveRowsInSixSectionsNoAccountSection` proves this by construction.
struct SettingsView: View {
    @Environment(\.container) private var container
    @Environment(\.router) private var router
    @Environment(\.colorScheme) private var colorScheme
    @Environment(\.locale) private var locale
    @Environment(\.openURL) private var openURL

    @State private var showThemePicker = false
    @State private var showQualityPicker = false
    /// Phase 3 Task 6 (CF-B3-11): Clear saved videos requires an explicit confirm.
    @State private var showClearOfflineConfirm = false
    /// Phase 4 Task 13: the second sign-out surface (the Me kebab is the other), same `.alert`.
    @State private var showSignOutConfirm = false
    /// Free-space capacity, read once per appearance instead of once per `body` — see
    /// `OfflineStorage.availableBytes(cached:base:)`, which the Storage row reads it through.
    @State private var availableBytes: Int64?
    /// task-14 (`screenshots/task-14/iphone-17/settings-en-light-a11y3-portrait.png`): the row
    /// symbol scales with Dynamic Type but its 28 pt circular plate did not, so at
    /// `.accessibility3` the glyph overflowed the plate on every row.
    @ScaledMetric(relativeTo: .body) private var rowIconSize: CGFloat = 28

    private var settings: any SettingsStore { container.settings }

    /// `favorites-settings-about.md:143`: Account/Sign-out is "hidden unless signed in", and until
    /// Phase 4 nothing could sign in — which is why it is a conditional Section here rather than a
    /// `SettingsSection` case. `SettingsLayout.rows` stays the unconditional twelve
    /// (`SettingsRowsTests.twelveRowsInSixSectionsNoAccountSection` is still true of it), because a
    /// static table cannot express a row that appears only for a signed-in user.
    @ViewBuilder
    private var accountSection: some View {
        if let me = container.session.state.me {
            Section(String(localized: "settings_account_header")) {
                // Label = the role, value = who — the same split `CategoryPill` makes (gate B1-I7),
                // so VoiceOver reads "Account, <email>" rather than one fused sentence.
                Text(signedInAs(me))
                    .font(TypeScale.subtitle)
                    .foregroundStyle(Color.textSecondary)
                    .accessibilityLabel(String(localized: "settings_account_header"))
                    .accessibilityValue(me.email ?? me.displayName ?? "")
                Button(String(localized: "settings_account_sign_out"), role: .destructive) {
                    showSignOutConfirm = true
                }
                .frame(minHeight: 44)
            }
        }
    }

    /// `\u{2068}…\u{2069}` isolation: an email or display name carries its own bidi direction and
    /// would otherwise corrupt the surrounding Arabic sentence (Global Constraints, spec §14).
    private func signedInAs(_ me: AccountMe) -> String {
        guard let who = me.email ?? me.displayName, !who.isEmpty else {
            return String(localized: "settings_account_signed_in_default")
        }
        return Format.localizedFormat("settings_account_signed_in_as", locale: locale,
                                      "\u{2068}\(who)\u{2069}")
    }

    var body: some View {
        Form {
            accountSection
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
        // Phase 3 Task 6 (CF-B3-11): a confirmation `.alert`, deleting the whole batch through the
        // manager in one call (Cubic P3-3) — never the file system from UI.
        .alert(String(localized: "settings_offline_clear"), isPresented: $showClearOfflineConfirm) {
            Button(String(localized: "offline_action_delete"), role: .destructive) { clearOffline() }
            Button(String(localized: "cancel"), role: .cancel) {}
        } message: {
            Text(String(localized: "settings_offline_clear_confirm"))
        }
        .signOutConfirmation(isPresented: $showSignOutConfirm) { container.session.signOut() }
        .task(id: container.offlineStore.items.count) {
            availableBytes = OfflineStorage.availableBytes(base: container.offlineBase)
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
        case .savedLibrary:
            actionRow(row, value: nil) { router.push(.offline) }
        case .storage:
            valueRow(row, value: OfflineStorage.storageValue(
                used: OfflineStorage.usedBytes(items: container.offlineStore.items),
                available: OfflineStorage.availableBytes(cached: availableBytes, base: container.offlineBase),
                locale: locale))
        case .clearOffline:
            actionRow(row, value: nil) { showClearOfflineConfirm = true }
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

    /// Informational row (Phase 3 Task 6's Storage): icon + label + value, no chevron, no action.
    private func valueRow(_ row: SettingsRow, value: String) -> some View {
        HStack(spacing: Spacing.sm) {
            rowIcon(row)
            rowLabel(row)
            Spacer()
            Text(value)
                .foregroundStyle(Color.textSecondary)
        }
    }

    private func clearOffline() {
        let ids = container.offlineStore.items.map(\.id)
        let manager = container.offlineManager
        Task { await manager.deleteAll(ids) }
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
