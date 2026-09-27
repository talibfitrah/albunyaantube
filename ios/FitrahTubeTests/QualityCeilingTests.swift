import AVFoundation
import Network
import Testing
@testable import FitrahTube

/// player.md §3/§4 + spec §10's quality-ceiling contract. `AVPlayerItem(url:)` is constructible
/// without playback (no network hit at construction), so these drive the real production `apply`
/// -- `NWPath` itself has no public initializer, so the bool-flag overload (documented on
/// `QualityOption.apply`) is what's actually exercised, same pattern as `NetworkMonitorTests`.
@Suite(.perTest)
struct QualityCeilingTests {
    private static let fixtureURL = URL(string: "file:///dev/null")!
    private static let layerSize = CGSize(width: 750, height: 422)

    private func item() -> AVPlayerItem { AVPlayerItem(url: Self.fixtureURL) }

    @Test func autoUsesTheLayerSizeWithNoBitrateCap() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
        #expect(item.preferredMaximumResolution == Self.layerSize)
        #expect(item.preferredPeakBitRate == 0)
    }

    @Test func explicitOptionsMapToTheirOwnResolutionAndBitrate() {
        let cases: [(QualityOption, CGSize, Double)] = [
            (.p1080, CGSize(width: 1920, height: 1080), 0),
            (.p720, CGSize(width: 1280, height: 720), 0),
            (.p480, CGSize(width: 854, height: 480), 0),
            (.dataSaver, CGSize(width: 854, height: 480), 1_200_000),
        ]
        for (option, resolution, bitrate) in cases {
            let item = item()
            option.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
            #expect(item.preferredMaximumResolution == resolution, "\(option)")
            #expect(item.preferredPeakBitRate == bitrate, "\(option)")
        }
    }

    @Test func cellularCeilingClamps1080pPickTo720pOnAnExpensivePath() {
        let item = item()
        QualityOption.p1080.apply(to: item, layerSize: Self.layerSize, isExpensive: true, isConstrained: false)
        #expect(item.preferredMaximumResolution == CGSize(width: 1280, height: 720))
        #expect(item.preferredPeakBitRate == 2_500_000)
    }

    /// A pick already at or below the cellular ceiling (480p) is left alone on resolution -- only
    /// bitrate 0 ("unbounded") gets pulled down to the ceiling.
    @Test func cellularCeilingDoesNotRaiseAPickAlreadyBelowIt() {
        let item = item()
        QualityOption.p480.apply(to: item, layerSize: Self.layerSize, isExpensive: true, isConstrained: false)
        #expect(item.preferredMaximumResolution == CGSize(width: 854, height: 480))
        #expect(item.preferredPeakBitRate == 2_500_000)
    }

    @Test func lowDataModeForcesDataSaverRegardlessOfThePickedOption() {
        let item = item()
        QualityOption.p1080.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: true)
        #expect(item.preferredMaximumResolution == CGSize(width: 854, height: 480))
        #expect(item.preferredPeakBitRate == 1_200_000)
    }

    @Test func lowDataModeWinsOverTheCellularClampToo() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: Self.layerSize, isExpensive: true, isConstrained: true)
        #expect(item.preferredMaximumResolution == CGSize(width: 854, height: 480))
        #expect(item.preferredPeakBitRate == 1_200_000)
    }

    /// Fix-round-1 F3: a zero layer size (view not yet laid out) on an expensive path must clamp
    /// to the cellular ceiling, not collapse to a zero-pixel cap that blocks video entirely.
    @Test func autoWithZeroLayerSizeOnAnExpensivePathClampsToTheCellularCeilingNotZero() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: .zero, isExpensive: true, isConstrained: false)
        #expect(item.preferredMaximumResolution == CGSize(width: 1280, height: 720))
        #expect(item.preferredPeakBitRate == 2_500_000)
    }

    /// Fix-round-1 F2: the native backstop is always set, independent of the primary cap, so
    /// AVFoundation itself still enforces a ceiling on a mid-playback network handover.
    @Test func expensiveNetworkBackstopIsAlwaysSet() {
        let item = item()
        QualityOption.dataSaver.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
        #expect(item.preferredMaximumResolutionForExpensiveNetworks == CGSize(width: 1280, height: 720))
        #expect(item.preferredPeakBitRateForExpensiveNetworks == 2_500_000)
    }

    /// Fix-round-1 F1: pins the points -> pixels conversion `PlayerHostView.applyQuality` needs
    /// (`preferredMaximumResolution` is a pixel dimension; `UIView.bounds.size` is points).
    @Test func pixelSizeMultipliesPointsByScale() {
        let pixels = QualityOption.pixelSize(points: CGSize(width: 390, height: 844), scale: 3)
        #expect(pixels == CGSize(width: 1170, height: 2532))
    }

    /// Fix-round-1 F4: thin test on the production `network: NWPath` overload -- the pass-through
    /// seam where F1's points/pixels bug lived. A real `NWPath` (unlike a fabricated one) is
    /// obtainable via `NWPathMonitor().currentPath`, so this pins the overload's delegation is
    /// self-consistent with the bool-flag core, whatever this sandbox's actual path reports.
    @Test func networkOverloadDelegatesToTheBoolFlagCore() {
        let network = NWPathMonitor().currentPath
        let item = item()
        QualityOption.p1080.apply(to: item, layerSize: Self.layerSize, network: network)
        let expected = QualityOption.ceiling(for: .p1080, layerSize: Self.layerSize,
                                             isExpensive: network.isExpensive, isConstrained: network.isConstrained)
        #expect(item.preferredMaximumResolution == expected.resolution)
        #expect(item.preferredPeakBitRate == expected.bitrate)
    }

    @Test func labelsMatchTheAndroidFormat() {
        // I5 (B1 final review): the two word-labels are localized now -- pinned against the
        // catalog keys, not the English literals, so the ar/nl legs aren't silently English.
        #expect(QualityOption.auto.label == String(localized: "player_quality_auto"))
        #expect(QualityOption.p1080.label == "1080p (1920×1080)")
        #expect(QualityOption.p720.label == "720p (1280×720)")
        #expect(QualityOption.p480.label == "480p (854×480)")
        #expect(QualityOption.dataSaver.label == String(localized: "player_quality_data_saver"))
    }
}
