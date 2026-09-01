import Foundation
import Testing
@testable import FitrahTube

/// Phase 3 Task 5: the Save-for-offline quality picker model. The option set derives from
/// `OfflineEngineSupport` — under today's `.progressiveOnly` (Task 1's outcome B) it is exactly
/// TWO options; the HLS tiers appear only when hardware evidence flips `current` to `.hls`.
@Suite struct OfflineQualityTests {

    @Test func progressiveOnlyOffersExactlyAudioOnlyThenStandard360() {
        #expect(OfflineQuality.options(for: .progressiveOnly) == [.audioOnly, .video(label: "360p")])
    }

    @Test func hlsOffersAudioOnlyThenTheFourTiers() {
        #expect(OfflineQuality.options(for: .hls) == [
            .audioOnly,
            .video(label: "360p"), .video(label: "480p"), .video(label: "720p"), .video(label: "1080p"),
        ])
    }

    /// The shipped support level is still Task 1's outcome B — the picker must not grow tiers
    /// until `OfflineEngineSupport.current` flips on hardware evidence.
    @Test func theCurrentSupportLevelYieldsTheTwoOptionPicker() {
        #expect(OfflineQuality.options(for: OfflineEngineSupport.current).count == 2)
    }

    /// Preselect mapping (`DownloadQualityDialog.kt:64-114`): low/medium/high → 360/720/1080.
    /// Under progressiveOnly only the 360p row exists, so ALL THREE settings preselect it —
    /// audio-only is never preselected by the quality setting.
    @Test func everyQualitySettingPreselectsTheStandardRowUnderProgressiveOnly() {
        let options = OfflineQuality.options(for: .progressiveOnly)
        for setting in ["low", "medium", "high", "garbage"] {
            #expect(OfflineQuality.preselection(for: setting, in: options) == .video(label: "360p"))
        }
    }

    @Test func underHlsTheSettingMapsToItsTier() {
        let options = OfflineQuality.options(for: .hls)
        #expect(OfflineQuality.preselection(for: "low", in: options) == .video(label: "360p"))
        #expect(OfflineQuality.preselection(for: "medium", in: options) == .video(label: "720p"))
        #expect(OfflineQuality.preselection(for: "high", in: options) == .video(label: "1080p"))
    }

    /// What `OfflineManager.save(videoId:quality:audioOnly:)` receives per option: video saves
    /// are ALWAYS the muxed 360p mp4 today (`qualityLabel: "360p"`); the audio flag drives the
    /// manager's audio walk and the label is informational.
    @Test func saveParametersPerOption() {
        #expect(OfflineQuality.audioOnly.isAudioOnly == true)
        #expect(OfflineQuality.audioOnly.qualityLabel == "audio")
        let video = OfflineQuality.video(label: "360p")
        #expect(video.isAudioOnly == false)
        #expect(video.qualityLabel == "360p")
    }
}
