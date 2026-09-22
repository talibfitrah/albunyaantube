import SwiftUI

/// Phase 3 Task 5: the Save-for-offline confirmation sheet (a sheet, NOT `confirmationDialog` —
/// CF-B3-11). Quality rows from `OfflineQuality.options` (exactly two under today's
/// `.progressiveOnly`), preselected from `SettingsStore.downloadQuality`; the save fires ONLY on
/// the explicit confirm button — never speculatively (every video save POSTs a real walk).
///
/// The body IS `SettingsPickerSheet`, not a verbatim copy of it: same
/// checkmark rows, detents and background, with `confirm:` supplying the Save button and
/// suppressing dismiss-on-tap.
struct SaveOfflineSheet: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.dismiss) private var dismiss
    /// The picker's option value is `OfflineQuality.qualityLabel` — unique per option ("audio",
    /// "360p", …). Empty until the preselection lands.
    @State private var selection = ""

    private var options: [OfflineQuality] { OfflineQuality.options(for: OfflineEngineSupport.current) }

    var body: some View {
        SettingsPickerSheet(
            titleKey: "offline_quality_title",
            options: options.map { SettingsPickerOption(value: $0.qualityLabel, labelKey: Self.labelKey($0)) },
            selection: $selection,
            confirm: (titleKey: "offline_save", action: save))
        .onAppear {
            if selection.isEmpty {
                selection = OfflineQuality.preselection(for: container.settings.downloadQuality,
                                                        in: options).qualityLabel
            }
        }
    }

    private static func labelKey(_ option: OfflineQuality) -> String {
        // ponytail: under `.progressiveOnly` the one video row IS the stated 360p ceiling; when
        // hardware evidence flips `OfflineEngineSupport.current` to `.hls`, the tier rows need
        // their own display strings (authored with that flip, not before).
        option.isAudioOnly ? "offline_quality_audio_only" : "offline_quality_standard_ceiling"
    }

    private func save() {
        guard let quality = options.first(where: { $0.qualityLabel == selection }) else { return }
        // CF-A-50: the owner is the account signed in NOW (`""` for a guest) — `currentUid`, not
        // `user` alone, so a save in the `land()` window is not stamped as the guest's.
        let metadata = OfflineMetadata(title: args.title ?? args.videoId, channelName: args.channelName,
                                       thumbnailUrl: args.thumbnailURL?.absoluteString,
                                       userId: container.session.currentUid ?? "")
        let videoId = args.videoId
        Task { [container] in
            await container.offlineManager.save(videoId: videoId, quality: quality.qualityLabel,
                                                audioOnly: quality.isAudioOnly, metadata: metadata)
        }
        dismiss()
    }
}

#if DEBUG
#Preview {
    SaveOfflineSheet(args: PlayerArgs(videoId: "preview", title: "Preview video"))
        .environment(\.container, .sharedFake)
}
#endif
