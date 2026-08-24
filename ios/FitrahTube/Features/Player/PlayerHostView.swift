import AVFoundation
import AVKit
import InnerTubeKit
import SwiftUI

/// `AVPlayerViewController` host for rung-1 (HLS) and rung-2 (progressive) playback (spec §10, plan
/// §6.5). Stock chrome gives scrubber/±10s/speed/subtitle-menu/AirPlay for free; quality ceiling,
/// captions overlay, audio-language menu, recovery and the toolbar are later tasks in this plan.
struct PlayerHostView: UIViewControllerRepresentable {
    let state: StreamState

    func makeUIViewController(context: Context) -> AVPlayerViewController {
        let controller = AVPlayerViewController()
        controller.showsPlaybackControls = true
        // ponytail: B1 never turns PiP on (App Review flags autoplay-into-PiP as a review risk);
        // B2 (plan §6.5 "Background audio"/"PiP") flips this to true.
        controller.allowsPictureInPicturePlayback = false
        controller.player = Self.player(for: state, replacing: nil)
        return controller
    }

    func updateUIViewController(_ controller: AVPlayerViewController, context: Context) {
        controller.player = Self.player(for: state, replacing: controller.player)
    }

    static func dismantleUIViewController(_ controller: AVPlayerViewController, coordinator: ()) {
        controller.player?.pause()
        controller.player = nil
    }

    // MARK: - Builder (static so `PlayerHostTests` can call it directly, no view hierarchy needed)

    /// Builds the `AVPlayer` for one `StreamState`, reusing `replacing` when it's already playing the
    /// resolved URL (an unrelated state change -- e.g. a later task's recovery counters -- must not
    /// restart playback). Ruling 32 (session-only resume): `replacing`'s `currentTime()` carries into
    /// the replacement item; nothing here persists past this `AVPlayer`'s own lifetime.
    static func player(for state: StreamState, replacing existing: AVPlayer?) -> AVPlayer? {
        guard let resolved = resolvedStream(for: state), let url = streamURL(resolved.stream) else {
            existing?.pause()
            return nil
        }
        if let existing, (existing.currentItem?.asset as? AVURLAsset)?.url == url {
            return existing
        }
        let item = AVPlayerItem(asset: asset(url: url, userAgent: resolved.userAgent))
        guard let existing else {
            let player = AVPlayer(playerItem: item)
            player.play()
            return player
        }
        let resumeTime = existing.currentTime()
        existing.replaceCurrentItem(with: item)
        if resumeTime.isValid, resumeTime.seconds.isFinite, resumeTime.seconds > 0 {
            existing.seek(to: resumeTime)
        }
        existing.play()
        return existing
    }

    /// The mandatory `AVURLAsset` for a resolved stream (plan constraint: the resolved User-Agent
    /// MUST be set on the asset). Root-cause finding: the plan's own snippet
    /// (`AVURLAssetHTTPHeaderFieldsKey: ["User-Agent": ...]`) doesn't compile against this
    /// toolchain's SDK (Xcode 26.3 / iOS 26.2) -- that generic header-dict key has been removed
    /// (confirmed absent from `AVAsset.h`), replaced by a dedicated `AVURLAssetHTTPUserAgentKey`
    /// (iOS 16+, well within this app's 18.0 floor) taking the user-agent string directly, not
    /// nested in a header dictionary. `AVURLAsset` doesn't expose its `options` dictionary back out
    /// once built, so `assetOptions(userAgent:)` below -- not this asset -- is what `PlayerHostTests`
    /// actually asserts the value against.
    static func asset(url: URL, userAgent: String) -> AVURLAsset {
        AVURLAsset(url: url, options: assetOptions(userAgent: userAgent))
    }

    static func assetOptions(userAgent: String) -> [String: Any] {
        [AVURLAssetHTTPUserAgentKey: userAgent]
    }

    private static func resolvedStream(for state: StreamState) -> Resolved? {
        switch state {
        case .ready(let resolved), .rung2Progressive(let resolved): return resolved
        default: return nil
        }
    }

    private static func streamURL(_ stream: ResolvedStream) -> URL? {
        switch stream {
        case .hls(let url, _, _, _): return url
        case .progressive(let url, _): return url
        case .embed, .openInYouTube: return nil // B3
        }
    }
}
