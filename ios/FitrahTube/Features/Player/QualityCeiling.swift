import AVFoundation
import InnerTubeKit
import Network

/// The manual quality menu, reduced to what AVFoundation actually exposes for HLS (spec §10, plan
/// §6.5, player.md §3/§4): there is no per-track pick, only ceilings ABR then honours --
/// `preferredMaximumResolution` + `preferredPeakBitRate`, clamped further on cellular
/// (`…ForExpensiveNetworks`-equivalent ceiling, applied manually below -- see `apply` doc) and by
/// Low Data Mode (`NWPath.isConstrained`). Android's `QualityTrackSelector` equivalent.
/// Owner request 2026-11-02: the rows are the current stream's REAL renditions (`menu(for:)`), and a pick caps at that
/// rendition's own size: AVPlayer never plays above it, while ABR may still drop below it on a weak link (a ceiling, not a pin).
enum QualityOption: Hashable {
    case auto
    /// One of the stream's video variants (`variantSizes(of:)`), capped at exactly its pixel size.
    case rendition(width: Int, height: Int)
    case dataSaver

    /// player.md §3's LTE/5G ceiling: Auto and Data Saver are clamped to this on an `isExpensive` path
    /// (Wi-Fi/offline: no clamp); a picked rendition is not (`ceiling(for:)`).
    private static let cellularResolution = CGSize(width: 1280, height: 720)
    private static let cellularBitrate: Double = 2_500_000 // 2.5 Mbps

    /// player.md §3's 3G/metered ceiling doubles as Data Saver's own fixed cap (spec §10: "the
    /// lowest sensible cap") and as what Low Data Mode forces on Auto.
    private static let dataSaverResolution = CGSize(width: 854, height: 480)
    private static let dataSaverBitrate: Double = 1_200_000 // 1.2 Mbps

    /// `nil` for `.auto`: the caller substitutes the player layer's own pixel size (spec §10:
    /// "default cap = layer pixel size").
    private var explicitResolution: CGSize? {
        switch self {
        case .auto: return nil
        case .rendition(let width, let height): return CGSize(width: width, height: height)
        case .dataSaver: return Self.dataSaverResolution
        }
    }

    /// `0` is AVFoundation's own "unbounded" convention for `preferredPeakBitRate`.
    private var explicitBitrate: Double {
        self == .dataSaver ? Self.dataSaverBitrate : 0
    }

    /// "Auto", "1080p (1920×1080)", … -- Android's "720p (1280x720)" row format. I5 (B1 final review): the two
    /// word-labels are catalog strings, not English literals -- they are user-visible menu rows and a VoiceOver value,
    /// and B4's Shorts kebab reuses this same type. The rendition labels stay literal (digits + "p" + a ×-separated
    /// pixel pair, identical in all three locales); the "p" number is the short side, so a vertical Short reads as
    /// YouTube labels it.
    var label: String {
        switch self {
        case .auto: return String(localized: "player_quality_auto")
        case .dataSaver: return String(localized: "player_quality_data_saver")
        case .rendition(let width, let height): return "\(min(width, height))p (\(width)×\(height))"
        }
    }

    /// The menu rows' accessibility identifier suffix: `auto`, `720`, `dataSaver`.
    var id: String {
        switch self {
        case .auto: return "auto"
        case .dataSaver: return "dataSaver"
        case .rendition(let width, let height): return "\(min(width, height))"
        }
    }

    /// The menu for a stream whose video variants are `sizes`: Auto, one row per distinct rendition (biggest first; a
    /// master repeats a size once per codec or frame rate), then Data Saver. Empty -- no menu -- with fewer than two
    /// renditions: a single file (rung 2's itag 18, an offline save) has nothing to choose.
    static func menu(for sizes: [CGSize]) -> [QualityOption] {
        var largest: [Int: CGSize] = [:]
        // Sizes come from a master or a player response: one no screen shows is dropped (Int.max trapped back in `Int`).
        for size in sizes where plausible.contains(size.width) && plausible.contains(size.height) {
            let key = Int(min(size.width, size.height))
            if area(size) > area(largest[key] ?? .zero) { largest[key] = size }
        }
        let renditions = largest.keys.sorted(by: >).compactMap { largest[$0] }
            .map { QualityOption.rendition(width: Int($0.width), height: Int($0.height)) }
        return renditions.count > 1 ? [.auto] + renditions + [.dataSaver] : []
    }

    /// The stream's video rendition sizes. Not `AVURLAsset.variants`: that fetches every rendition's media playlist
    /// first (measured), which on tv_samsung is a `sidx` GET each, past `SyntheticHLSLoader`'s 2-GET ceiling. tv_samsung's
    /// model carries the sizes; a native master costs one GET of its text; a progressive file is one rendition.
    /// nil: the master could not be read (no network for a moment, an error status, a body past `maxMasterBytes`, a 2xx
    /// that is no playlist), which is not "one rendition".
    static func variantSizes(of resolved: Resolved, transport: any BoundedHTTPTransport = masterTransport) async -> [CGSize]? {
        // A progressive file, an offline save or a fixture (a local `.hls`): one rendition, no remote master.
        guard case .hls(let url, _, _, _, let synthetic) = resolved.stream, !url.isFileURL else { return [] }
        if let synthetic {
            return synthetic.video.compactMap { v in v.width.flatMap { w in v.height.map { CGSize(width: w, height: $0) } } }
        }
        let request = HTTPRequest(method: "GET", url: url, headers: ["User-Agent": resolved.userAgent], body: nil)
        guard let response = try? await transport.send(request, maxBodyBytes: maxMasterBytes),
              (200..<300).contains(response.status), response.body.count <= maxMasterBytes else { return nil }
        // A master opens with #EXTM3U (RFC 8216 §4.3.1.1); an empty body (a declared length past the cap) or a page is none.
        let master = String(decoding: response.body, as: UTF8.self)
        return master.hasPrefix("#EXTM3U") ? masterSizes(master) : nil
    }

    /// `URLSessionTransport`: ephemeral and cookieless (the signed, IP-bound master never reaches a shared disk cache or
    /// cookie jar), a 15 s timeout, and a body read only as far as the cap (gstack review: a CDN error page or a wrong
    /// redirect is never buffered whole just to fill a menu).
    static let masterTransport: any BoundedHTTPTransport = URLSessionTransport()
    /// A master lists each rendition once per codec and frame rate on a few hundred bytes each: tens of KB.
    nonisolated static let maxMasterBytes = 1 << 20

    /// Each `#EXT-X-STREAM-INF`'s `RESOLUTION` (RFC 8216 §4.3.4.2); I-frame playlists and audio-only variants have none.
    static func masterSizes(_ master: String) -> [CGSize] {
        master.split(whereSeparator: \.isNewline).compactMap { line in
            guard line.hasPrefix("#EXT-X-STREAM-INF:"), let match = line.firstMatch(of: /RESOLUTION=(\d+)x(\d+)/),
                  let width = Int(match.1), let height = Int(match.2) else { return nil }
            return CGSize(width: width, height: height)
        }
    }

    /// Production entry point (`PlayerHostView`): sets the ceiling for `item`, clamped by the
    /// current network path.
    func apply(to item: AVPlayerItem, layerSize: CGSize, network: NWPath) {
        apply(to: item, layerSize: layerSize, isExpensive: network.isExpensive, isConstrained: network.isConstrained)
    }

    /// The `NWPath`-free core `QualityCeilingTests` drives directly -- `NWPath` has no public
    /// initializer, so a real one can't be constructed in a test (same reason
    /// `NetworkMonitor.isOnline(for:)` takes `NWPath.Status`, not `NWPath`, in this codebase).
    func apply(to item: AVPlayerItem, layerSize: CGSize, isExpensive: Bool, isConstrained: Bool) {
        let ceiling = Self.ceiling(for: self, layerSize: layerSize, isExpensive: isExpensive, isConstrained: isConstrained)
        item.preferredMaximumResolution = ceiling.resolution
        item.preferredPeakBitRate = ceiling.bitrate
        // Fix-round-1 F2: defense-in-depth backstop. AVFoundation itself honours these two
        // properties whenever it detects the CURRENT network is expensive, independent of the
        // `isExpensive` this call was made with -- so a mid-playback Wi-Fi -> cellular handover
        // is still capped even though nothing re-applies the manual clamp above until the next
        // prepare/pick. The manual clamp stays the source of truth the tests pin; this never
        // raises it (AVFoundation applies the more restrictive of the two on an expensive path).
        // A picked rendition sets its own cap here too, or AVFoundation would clamp it to 720p on cellular.
        let picked = if case .rendition = self { true } else { false }
        item.preferredMaximumResolutionForExpensiveNetworks = picked ? ceiling.resolution : Self.cellularResolution
        item.preferredPeakBitRateForExpensiveNetworks = picked ? ceiling.bitrate : Self.cellularBitrate
    }

    /// Fix-round-1 F1: `preferredMaximumResolution` is a PIXEL dimension; `UIView.bounds.size` is
    /// POINTS. The caller (`PlayerHostView.applyQuality`) must convert before this ever sees a
    /// layer size, or AUTO's cap collapses to ~one-third of the real pixel size on a 3x device --
    /// well below 480p, forcing every default session to the bottom rendition. Pure so it's
    /// testable without a view hierarchy.
    static func pixelSize(points: CGSize, scale: CGFloat) -> CGSize {
        CGSize(width: points.width * scale, height: points.height * scale)
    }

    static func ceiling(for option: QualityOption, layerSize: CGSize, isExpensive: Bool,
                         isConstrained: Bool) -> (resolution: CGSize, bitrate: Double) {
        // Owner request 2026-11-02: a picked rendition's cap ignores the network clamps (Android parity: a manual pick
        // overrides `QualityTrackSelector`'s network ceiling) -- ABR may still play below it. The clamps bind Auto and Data Saver.
        if case .rendition(let width, let height) = option {
            return (CGSize(width: width, height: height), 0)
        }
        // Low Data Mode wins outright over Auto, Data Saver and the cellular clamp.
        if isConstrained {
            return (dataSaverResolution, dataSaverBitrate)
        }
        var resolution = option.explicitResolution ?? layerSize
        var bitrate = option.explicitBitrate
        if isExpensive {
            if area(resolution) == 0 || area(resolution) > area(cellularResolution) {
                resolution = cellularResolution
            }
            if bitrate == 0 || bitrate > cellularBitrate {
                bitrate = cellularBitrate
            }
        }
        return (resolution, bitrate)
    }

    /// Owner report 2026-11-02: a LOWER ceiling binds only the segments fetched after it, so AVPlayer plays out what it
    /// already buffered above it (measured on the iPhone 17 simulator: 1920x1080 still drawn 36 s after a 480p pick; a
    /// fresh item on the same asset drew 854x480 within 3 s). True when a pick lowers the effective ceiling, so the host
    /// rebuilds the item at the live position. A raise needs nothing (1080p drawn within 3 s), and `old == nil` is the
    /// first prepare, not a pick. Width and height are compared apart, as AVFoundation caps them: a portrait layer's Auto
    /// has more area than 720p yet a narrower width (review of a2c737ba). A lower SIZE cap rebuilds only when what is
    /// drawn (`playing`, the item's `presentationSize`; `.zero` = nothing yet, unknown) is above it (Cubic r2): picking the
    /// top rendition from a fullscreen Auto lowers the cap past the layer while ABR already plays under it.
    static func needsFreshItem(from old: QualityOption?, to new: QualityOption, layerSize: CGSize, playing: CGSize = .zero,
                               isExpensive: Bool, isConstrained: Bool) -> Bool {
        guard let old, old != new else { return false }
        let before = ceiling(for: old, layerSize: layerSize, isExpensive: isExpensive, isConstrained: isConstrained)
        let after = ceiling(for: new, layerSize: layerSize, isExpensive: isExpensive, isConstrained: isConstrained)
        let sizeLowered = isLower(after.resolution.width, than: before.resolution.width)
            || isLower(after.resolution.height, than: before.resolution.height)
        let drawnAbove = playing == .zero || playing.width > after.resolution.width || playing.height > after.resolution.height
        return sizeLowered && drawnAbove || isLower(after.bitrate, than: before.bitrate)
    }

    /// `0` is AVFoundation's "unbounded" for both the resolution and the bitrate cap.
    private static func isLower(_ new: Double, than old: Double) -> Bool { new > 0 && (old == 0 || new < old) }

    private static func area(_ size: CGSize) -> Double { Double(size.width) * Double(size.height) }

    /// 8K is 7680 wide; 16384 leaves room and stays far inside `Int`.
    private static let plausible: ClosedRange<CGFloat> = 1...16_384
}
