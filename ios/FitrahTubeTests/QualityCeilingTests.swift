import AVFoundation
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

    @Test func labelsMatchTheAndroidFormat() {
        #expect(QualityOption.auto.label == "Auto")
        #expect(QualityOption.p1080.label == "1080p (1920×1080)")
        #expect(QualityOption.p720.label == "720p (1280×720)")
        #expect(QualityOption.p480.label == "480p (854×480)")
        #expect(QualityOption.dataSaver.label == "Data Saver")
    }
}
