import AVFoundation
import Network

/// The manual quality menu, reduced to what AVFoundation actually exposes for HLS (spec §10, plan
/// §6.5, player.md §3/§4): there is no per-track pick, only ceilings ABR then honours --
/// `preferredMaximumResolution` + `preferredPeakBitRate`, clamped further on cellular
/// (`…ForExpensiveNetworks`-equivalent ceiling, applied manually below -- see `apply` doc) and by
/// Low Data Mode (`NWPath.isConstrained`). Android's `QualityTrackSelector` equivalent.
enum QualityOption: CaseIterable {
    case auto, p1080, p720, p480, dataSaver

    /// player.md §3's LTE/5G ceiling: every option is clamped to this on an `isExpensive` path
    /// (Wi-Fi/offline: no clamp).
    private static let cellularResolution = CGSize(width: 1280, height: 720)
    private static let cellularBitrate: Double = 2_500_000 // 2.5 Mbps

    /// player.md §3's 3G/metered ceiling doubles as Data Saver's own fixed cap (spec §10: "the
    /// lowest sensible cap") and as what Low Data Mode forces regardless of the picked option.
    private static let dataSaverResolution = CGSize(width: 854, height: 480)
    private static let dataSaverBitrate: Double = 1_200_000 // 1.2 Mbps

    /// `nil` for `.auto`: the caller substitutes the player layer's own pixel size (spec §10:
    /// "default cap = layer pixel size").
    private var explicitResolution: CGSize? {
        switch self {
        case .auto: return nil
        case .p1080: return CGSize(width: 1920, height: 1080)
        case .p720: return CGSize(width: 1280, height: 720)
        case .p480: return CGSize(width: 854, height: 480)
        case .dataSaver: return Self.dataSaverResolution
        }
    }

    /// `0` is AVFoundation's own "unbounded" convention for `preferredPeakBitRate`.
    private var explicitBitrate: Double {
        self == .dataSaver ? Self.dataSaverBitrate : 0
    }

    /// "Auto", "1080p (1920×1080)", … -- no `asset.variants` peak-resolution list exists anywhere
    /// in InnerTubeKit's `Resolved`/`ResolvedStream` (checked: `.hls` carries one opaque manifest
    /// URL, no per-rendition data), so every label is the "else the option name" branch the brief
    /// allows; `player_quality_dialog_title` (the menu's title) is the only localized string here.
    var label: String {
        switch self {
        case .auto: return "Auto"
        case .dataSaver: return "Data Saver"
        default:
            guard let size = explicitResolution else { return "" }
            return "\(Int(size.height))p (\(Int(size.width))×\(Int(size.height)))"
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
        item.preferredMaximumResolutionForExpensiveNetworks = Self.cellularResolution
        item.preferredPeakBitRateForExpensiveNetworks = Self.cellularBitrate
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
        // Low Data Mode wins outright over both the picked option and the cellular clamp.
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

    private static func area(_ size: CGSize) -> Double { Double(size.width) * Double(size.height) }
}
