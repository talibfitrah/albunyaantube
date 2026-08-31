/// Phase 3 Task 1 (§17 item 2) decision record: can the offline feature use HLS downloads
/// (`AVAssetDownloadTask` over the VISIONOS `hlsManifestUrl`), or only progressive files?
///
/// **Observed 2026-08-31, iPhone 17 simulator (Xcode 26.3), `OfflineSpikeTests` — OUTCOME B:
/// the simulator cannot answer the question.** The spike resolved `xc7keR2piUM` live through the
/// real ladder (client=visionos, `.hls` manifest returned) and ran `AVAssetDownloadTask` against
/// it; the task died in under a second with `NSURLErrorDomain Code=-1 "unknown error"`, zero
/// time ranges loaded, no `.movpkg` location. Discriminators, same run:
/// - Apple's own compliant HLS example (`img_bipbop_adv_example_hevc/master.m3u8`) failed
///   **identically** — so the failure is not YouTube's packaging.
/// - A plain GET of the same googlevideo manifest URL with the same UA returned
///   `status=200 bytes=8083 firstLine=#EXTM3U` — so the failure is not the network or the URL.
/// - The simulator log names the root cause: `BackgroundSession … an error occurred on the xpc
///   connection to setup the background session: Error Domain=NSCocoaErrorDomain Code=4097
///   "connection to service named com.apple.nsurlsessiond"` — the background AVAssetDownload
///   session cannot be set up on this simulator at all.
///
/// Consequence (plan fork G): the progressive engine is primary; the HLS engine ships dormant
/// behind the `OfflineEngine` seam; the quality picker states the 360p ceiling. YouTube's HLS
/// packaging remains an OPEN question, answerable only on hardware (USER-BLOCKED — no signing
/// identity). Hardware re-run: `TEST_RUNNER_OFFLINE_LIVE=1` + `OfflineSpikeTests` on a device
/// (CF-D-1); if the round trip passes there (outcome A), flip `current` to `.hls` and the
/// engine selection and picker tiers follow.
nonisolated enum OfflineEngineSupport: Sendable {
    /// `AVAssetDownloadTask` proven against YouTube's HLS end-to-end (movpkg lands and plays):
    /// saves download HLS; the picker offers audio-only + 360/480/720/1080.
    case hls
    /// HLS unproven or rejected: saves download progressive itag 18 (+ itag 140 audio-only);
    /// the picker offers audio-only + "Standard quality (360p)" with the ceiling stated.
    case progressiveOnly

    /// Outcome B, recorded 2026-08-31 (see type doc). Flip to `.hls` only on hardware evidence.
    static let current: OfflineEngineSupport = .progressiveOnly
}
