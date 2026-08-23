import FitrahAPI
import SwiftUI

/// Android's `DeveloperSettingsDialog` (`favorites-settings-about.md:290-318`), phase-1 slice per
/// RULING 35: version/build, API base URL, device id, and a link into the Components gallery
/// (Task 6's debug screenshot rig). The three playback-extraction kill switches (MPD prefetch /
/// iOS client fetch / generous crop budget) and the cache-clear/cooldown/telemetry rows are
/// Android's NewPipe/DASH-specific internals with no `InnerTubeKit` equivalent yet -- phase 2.
/// Row labels below have no Android string to port (this dialog's phase-1 *content* is new to
/// iOS) and, like the 35 `dev_settings_*` keys that already exist, are deliberately left
/// untranslated -- this is a debug-only screen.
struct DeveloperDialog: View {
    @Environment(\.dismiss) private var dismiss
    @State private var showGallery = false

    var body: some View {
        NavigationStack {
            Form {
                Section {
                    LabeledContent("Version", value: versionText)
                    LabeledContent("API base URL", value: AppConfig.apiBaseURL.absoluteString)
                    LabeledContent("Device ID", value: DeviceId.persisted().value)
                }
                Section {
                    Button("Components Gallery") { showGallery = true }
                }
            }
            .navigationTitle(String(localized: "dev_settings_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "dev_settings_done")) { dismiss() }
                }
            }
        }
        .sheet(isPresented: $showGallery) { ComponentsGallery() }
    }

    private var versionText: String {
        let version = Bundle.main.object(forInfoDictionaryKey: "CFBundleShortVersionString") as? String ?? "0.0.0"
        let build = Bundle.main.object(forInfoDictionaryKey: "CFBundleVersion") as? String ?? "0"
        return AboutVersionText.format(version: version, build: build)
    }
}

#Preview {
    DeveloperDialog()
        .environment(\.container, .sharedFake)
}
