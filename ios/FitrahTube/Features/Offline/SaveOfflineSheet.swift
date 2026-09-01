import SwiftUI

/// Phase 3 Task 5: the Save-for-offline confirmation sheet (a sheet, NOT `confirmationDialog` —
/// CF-B3-11). Quality rows from `OfflineQuality.options` (exactly two under today's
/// `.progressiveOnly`), preselected from `SettingsStore.downloadQuality`; the save fires ONLY on
/// the explicit confirm button — never speculatively (every video save POSTs a real walk).
struct SaveOfflineSheet: View {
    let args: PlayerArgs

    @Environment(\.container) private var container
    @Environment(\.dismiss) private var dismiss
    @State private var selection: OfflineQuality?

    private var options: [OfflineQuality] { OfflineQuality.options(for: OfflineEngineSupport.current) }

    var body: some View {
        NavigationStack {
            List(options) { option in
                row(option)
            }
            .navigationTitle(String(localized: "offline_quality_title"))
            .navigationBarTitleDisplayMode(.inline)
            .toolbar {
                ToolbarItem(placement: .cancellationAction) {
                    Button(String(localized: "cancel")) { dismiss() }
                }
                ToolbarItem(placement: .confirmationAction) {
                    Button(String(localized: "offline_save")) { save() }
                }
            }
        }
        .presentationDetents([.medium])
        // Same reasoning as SettingsPickerSheet: without this the sheet keeps the translucent
        // material and the player content underneath shows through the option list.
        .presentationBackground(Color.background)
        .onAppear {
            if selection == nil {
                selection = OfflineQuality.preselection(for: container.settings.downloadQuality, in: options)
            }
        }
    }

    /// The SettingsPickerSheet row idiom (checkmark + `.isSelected`), minus its dismiss-on-tap —
    /// confirmation is the Save button's job, not the row's.
    private func row(_ option: OfflineQuality) -> some View {
        Button {
            selection = option
        } label: {
            HStack {
                Text(label(option)).foregroundStyle(Color.textPrimary)
                Spacer()
                if option == selection {
                    Image(systemName: "checkmark")
                        .foregroundStyle(Color.brand)
                        .accessibilityHidden(true)  // the row carries `.isSelected` instead
                }
            }
            .contentShape(Rectangle())
        }
        .buttonStyle(.plain)
        .accessibilityAddTraits(option == selection ? [.isSelected] : [])
    }

    private func label(_ option: OfflineQuality) -> String {
        switch option {
        case .audioOnly:
            String(localized: "offline_quality_audio_only")
        case .video:
            // ponytail: under `.progressiveOnly` the one video row IS the stated 360p ceiling;
            // when hardware evidence flips `OfflineEngineSupport.current` to `.hls`, the tier
            // rows need their own display strings (authored with that flip, not before).
            String(localized: "offline_quality_standard_ceiling")
        }
    }

    private func save() {
        guard let selection else { return }
        let metadata = OfflineMetadata(title: args.title ?? args.videoId, channelName: args.channelName,
                                       thumbnailUrl: args.thumbnailURL?.absoluteString)
        let videoId = args.videoId
        Task { [container] in
            await container.offlineManager.save(videoId: videoId, quality: selection.qualityLabel,
                                                audioOnly: selection.isAudioOnly, metadata: metadata)
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
