import Foundation

/// Phase 3 Task 5: the Save-for-offline quality picker's model. Audio-only first, then the video
/// tiers `OfflineEngineSupport` proves — under today's `.progressiveOnly` (Task 1 outcome B)
/// exactly ONE video option, the 360p ceiling; the HLS tiers appear only when hardware evidence
/// flips `OfflineEngineSupport.current` to `.hls`.
nonisolated enum OfflineQuality: Equatable, Hashable, Sendable, Identifiable {
    case audioOnly
    case video(label: String)

    var id: String { qualityLabel }

    var isAudioOnly: Bool { self == .audioOnly }

    /// What `OfflineManager.save(videoId:quality:...)` receives as `qualityLabel`. Video saves
    /// are ALWAYS the muxed 360p mp4 today; for audio-only the label is informational (the
    /// manager's audio walk ignores it).
    var qualityLabel: String {
        switch self {
        case .audioOnly: "audio"
        case .video(let label): label
        }
    }

    static func options(for support: OfflineEngineSupport) -> [OfflineQuality] {
        switch support {
        case .progressiveOnly: [.audioOnly, .video(label: "360p")]
        case .hls: [.audioOnly] + ["360p", "480p", "720p", "1080p"].map { .video(label: $0) }
        }
    }

    /// Preselect from `SettingsStore.downloadQuality` (`DownloadQualityDialog.kt:64-114`):
    /// low/medium/high → 360/720/1080. Audio-only is never preselected by the quality setting;
    /// a tier the support level doesn't offer falls to the highest available video option
    /// (under `.progressiveOnly` that is the one 360p row for all three settings).
    static func preselection(for downloadQuality: String, in options: [OfflineQuality]) -> OfflineQuality {
        let preferred = switch downloadQuality {
        case "low": "360p"
        case "high": "1080p"
        default: "720p"
        }
        let videos = options.filter { !$0.isAudioOnly }
        return videos.first { $0.qualityLabel == preferred } ?? videos.last ?? .audioOnly
    }
}
