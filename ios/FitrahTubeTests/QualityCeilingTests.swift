import AVFoundation
import InnerTubeKit
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

    private static let p1080 = QualityOption.rendition(width: 1920, height: 1080)
    private static let p720 = QualityOption.rendition(width: 1280, height: 720)
    private static let p480 = QualityOption.rendition(width: 854, height: 480)
    private static let p360 = QualityOption.rendition(width: 640, height: 360)

    private func item() -> AVPlayerItem { AVPlayerItem(url: Self.fixtureURL) }

    // MARK: - The menu lists the stream's real renditions (owner request 2026-11-02)

    /// The variant sizes of C3BXt0zJr3o's visionOS master (measured 2026-11-02), with the repeats a master carries
    /// (one per codec/frame rate): one row per distinct size, biggest first, between Auto and Data Saver.
    @Test func theMenuListsEachDistinctRenditionHighestFirst() {
        let sizes = [CGSize(width: 256, height: 144), CGSize(width: 426, height: 240), CGSize(width: 640, height: 360),
                     CGSize(width: 854, height: 480), CGSize(width: 1280, height: 720), CGSize(width: 1920, height: 1080),
                     CGSize(width: 1280, height: 720), CGSize(width: 640, height: 360)]
        #expect(QualityOption.menu(for: sizes) == [
            .auto, Self.p1080, Self.p720, Self.p480, Self.p360,
            .rendition(width: 426, height: 240), .rendition(width: 256, height: 144), .dataSaver,
        ])
    }

    /// xc7keR2piUM tops out at 720p: no 1080p row that would do nothing.
    @Test func theMenuOffersNothingAboveTheStreamsTopRendition() {
        let sizes = [CGSize(width: 1280, height: 720), CGSize(width: 640, height: 360), CGSize(width: 256, height: 144)]
        #expect(QualityOption.menu(for: sizes) == [.auto, Self.p720, Self.p360, .rendition(width: 256, height: 144), .dataSaver])
    }

    /// A single file (rung 2's itag 18, an offline save) or a master with one rendition has nothing to choose:
    /// no menu, rather than rows that change nothing. Audio-only variants (no video size) are not renditions.
    @Test func oneRenditionOrNoneOffersNoMenu() {
        #expect(QualityOption.menu(for: []).isEmpty)
        #expect(QualityOption.menu(for: [CGSize(width: 640, height: 360)]).isEmpty)
        #expect(QualityOption.menu(for: [CGSize(width: 640, height: 360), .zero]).isEmpty)
    }

    /// Review of a2c737ba: sizes come from a master playlist or a player response. One no device can show (`Int.max`
    /// wide trapped converting back to `Int`; a negative pair has a positive area) is not a rendition.
    @Test func implausibleSizesAreNotRenditions() {
        let sizes = [CGSize(width: Double(Int.max), height: 1080), CGSize(width: -1280, height: -720),
                     CGSize(width: 1280, height: 720), CGSize(width: 640, height: 360)]
        #expect(QualityOption.menu(for: sizes) == [.auto, Self.p720, Self.p360, .dataSaver])
    }

    /// A vertical video (a Short) is labelled by its short side, as YouTube labels it.
    @Test func aVerticalRenditionIsLabelledByItsShortSide() {
        #expect(QualityOption.rendition(width: 1080, height: 1920).label == "1080p (1080×1920)")
    }

    /// A native master's `RESOLUTION`s, one per `#EXT-X-STREAM-INF` (an I-frame playlist or a variant with no
    /// RESOLUTION is not a rendition to pick).
    @Test func masterSizesReadEachStreamInfResolution() {
        let master = """
        #EXTM3U
        #EXT-X-INDEPENDENT-SEGMENTS
        #EXT-X-MEDIA:TYPE=AUDIO,GROUP-ID="234",NAME="Default",DEFAULT=YES,URI="https://m/a.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=290000,CODECS="avc1.4d4015,mp4a.40.5",RESOLUTION=426x240,FRAME-RATE=25,AUDIO="234"
        https://m/240.m3u8
        #EXT-X-STREAM-INF:BANDWIDTH=4500000,CODECS="avc1.640028,mp4a.40.2",RESOLUTION=1920x1080,FRAME-RATE=25,AUDIO="234"
        https://m/1080.m3u8
        #EXT-X-I-FRAME-STREAM-INF:BANDWIDTH=90000,RESOLUTION=1280x720,URI="https://m/iframe.m3u8"
        #EXT-X-STREAM-INF:BANDWIDTH=64000,CODECS="mp4a.40.2"
        https://m/audio.m3u8
        """
        #expect(QualityOption.masterSizes(master) == [CGSize(width: 426, height: 240), CGSize(width: 1920, height: 1080)])
    }

    /// tv_samsung: the sizes come from its own model -- no request, and none of `SyntheticHLSLoader`'s `sidx` GETs
    /// (`AVURLAsset.variants` would fetch every rendition's playlist, a `sidx` GET each).
    @Test func aTVStreamsSizesComeFromItsModel() async {
        func video(_ w: Int, _ h: Int) -> SyntheticHLS.Rendition {
            .init(url: URL(string: "https://fixture.invalid/v\(h)")!, codecs: "avc1.640028", bandwidth: h, width: w, height: h,
                  initRange: 0...1, indexRange: 2...3, contentLength: 9)
        }
        let resolved = Resolved(stream: .hls(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!, isLive: false,
                                             audioOnlyURL: nil, captionTracks: [],
                                             synthetic: SyntheticHLS(video: [video(1920, 1080), video(426, 240)], audio: [])),
                                client: .tvSamsung, userAgent: "TV-UA", resolvedAt: Date(), expiresAt: nil)
        #expect(await QualityOption.variantSizes(of: resolved) == [CGSize(width: 1920, height: 1080), CGSize(width: 426, height: 240)])
    }

    /// Rung 2's single file: one rendition, nothing to list.
    @Test func aProgressiveStreamHasNoSizes() async {
        let resolved = Resolved(stream: .progressive(url: URL(string: "https://fixture.invalid/18.mp4")!, label: "360p"),
                                client: .android, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        #expect(await QualityOption.variantSizes(of: resolved) == [])
    }

    private struct OneStream: StreamResolving {
        let resolved: Resolved
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved { resolved }
        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    /// A native master: one bounded GET with the stream's own User-Agent, read no further than `maxMasterBytes`.
    @Test func aNativeMasterIsOneBoundedGETWithTheStreamsUserAgent() async {
        let transport = StubTransport(status: 200, body: Self.master)
        #expect(await QualityOption.variantSizes(of: Self.native, transport: transport)
            == [CGSize(width: 1280, height: 720), CGSize(width: 640, height: 360)])
        #expect(transport.requests.map(\.headers) == [["User-Agent": "UA"]])
        #expect(transport.caps == [QualityOption.maxMasterBytes])
    }

    /// An offline save (`OfflineResolver`) or a fixture is a local `.hls`: one rendition, no remote master to ask for --
    /// no request, no menu (a bounded HTTP read of a file URL would fail and read as "unknown", offering rows).
    @Test func aLocalStreamHasNoSizesAndSendsNothing() async {
        let local = Resolved(stream: .hls(url: URL(fileURLWithPath: "/tmp/saved.movpkg"), isLive: false, audioOnlyURL: nil,
                                          captionTracks: []),
                             client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)
        let transport = StubTransport(status: 200, body: Self.master)
        #expect(await QualityOption.variantSizes(of: local, transport: transport) == [])
        #expect(transport.requests.isEmpty)
    }

    /// Gstack review: an error status (an expired signed URL answers 403 with an HTML page) and a body past the cap
    /// are no answer, not "one rendition": the rows stay unread so the next item asks again.
    @Test(arguments: [(403, master), (200, String(repeating: "#", count: QualityOption.maxMasterBytes + 1))])
    func anErrorStatusOrAnOversizeBodyIsNoAnswer(status: Int, body: String) async {
        #expect(await QualityOption.variantSizes(of: Self.native, transport: StubTransport(status: status, body: body)) == nil)
    }

    /// The model reads the native master of the stream on screen once its item is built, and a new item on the SAME
    /// stream -- a lower pick's rebuild, the audio-only swap -- keeps the rows, so the button does not flicker.
    @MainActor @Test(.timeLimit(.minutes(1))) func theModelLoadsTheMenuOfTheStreamOnScreen() async throws {
        let transport = StubTransport(status: 200, body: Self.master)
        let model = model(transport)
        await model.open()
        #expect(model.qualityOptions.isEmpty, "no rows before the host builds an item")
        let clip = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        model.currentItem = AVPlayerItem(url: clip)
        await model.qualityLoad?.value
        #expect(model.qualityOptions == [.auto, Self.p720, Self.p360, .dataSaver])
        model.currentItem = AVPlayerItem(url: clip)
        #expect(model.qualityOptions == [.auto, Self.p720, Self.p360, .dataSaver])
        #expect(transport.requests.count == 1)
    }

    /// Review of a2c737ba: a master that could not be read (offline for a moment, an error status) is not "one rendition":
    /// the next item built for the same stream reads it again. Meanwhile the menu keeps the two rows no rendition decides
    /// (Cubic r2 P3), so a Data Saver carried from the last video can still be turned off.
    @MainActor @Test(.timeLimit(.minutes(1))) func aMasterThatFailedToLoadIsReadAgainForTheNextItem() async throws {
        let transport = StubTransport(status: 503, body: "")
        let model = model(transport)
        await model.open()
        let clip = try #require(Bundle.main.url(forResource: "player-fixture", withExtension: "mp4"))
        model.currentItem = AVPlayerItem(url: clip)
        await model.qualityLoad?.value
        #expect(model.qualityOptions == [.auto, .dataSaver], "the master answered 503")
        transport.answer(status: 200, body: Self.master)
        model.currentItem = AVPlayerItem(url: clip)              // e.g. the Data Saver pick's own rebuild
        #expect(model.qualityOptions == [.auto, .dataSaver], "Cubic r3: the re-read hid the two rows while it ran")
        await model.qualityLoad?.value
        #expect(model.qualityOptions == [.auto, Self.p720, Self.p360, .dataSaver])
    }

    private nonisolated static let master = "#EXTM3U\n#EXT-X-STREAM-INF:BANDWIDTH=2,RESOLUTION=1280x720\n720.m3u8\n"
        + "#EXT-X-STREAM-INF:BANDWIDTH=1,RESOLUTION=640x360\n360.m3u8\n"
    private static let native = Resolved(stream: .hls(url: URL(string: "https://fixture.invalid/master.m3u8")!, isLive: false,
                                                      audioOnlyURL: nil, captionTracks: []),
                                         client: .visionos, userAgent: "UA", resolvedAt: Date(), expiresAt: nil)

    @MainActor private func model(_ transport: StubTransport) -> PlayerViewModel {
        PlayerViewModel(resolver: OneStream(resolved: Self.native),
                        settings: UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "QualityCeilingTests.\(UUID().uuidString)")!),
                        args: PlayerArgs(videoId: "quality"), masterTransport: transport)
    }

    private nonisolated final class StubTransport: BoundedHTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var response: HTTPResponse
        private var sent: [(HTTPRequest, Int)] = []
        init(status: Int, body: String) { response = HTTPResponse(status: status, headers: [:], body: Data(body.utf8)) }
        func answer(status: Int, body: String) {
            lock.withLock { response = HTTPResponse(status: status, headers: [:], body: Data(body.utf8)) }
        }
        var requests: [HTTPRequest] { lock.withLock { sent.map(\.0) } }
        var caps: [Int] { lock.withLock { sent.map(\.1) } }
        func send(_ request: HTTPRequest, maxBodyBytes: Int) async throws -> HTTPResponse {
            lock.withLock {
                sent.append((request, maxBodyBytes))
                return response
            }
        }
    }

    // MARK: - Ceilings

    @Test func autoUsesTheLayerSizeWithNoBitrateCap() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
        #expect(item.preferredMaximumResolution == Self.layerSize)
        #expect(item.preferredPeakBitRate == 0)
    }

    @Test func aRenditionCapsToExactlyItsOwnSize() {
        let cases: [(QualityOption, CGSize, Double)] = [
            (Self.p1080, CGSize(width: 1920, height: 1080), 0),
            (Self.p360, CGSize(width: 640, height: 360), 0),
            (.rendition(width: 426, height: 240), CGSize(width: 426, height: 240), 0),
            (.dataSaver, CGSize(width: 854, height: 480), 1_200_000),
        ]
        for (option, resolution, bitrate) in cases {
            let item = item()
            option.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
            #expect(item.preferredMaximumResolution == resolution, "\(option)")
            #expect(item.preferredPeakBitRate == bitrate, "\(option)")
        }
    }

    /// Owner request 2026-11-02: a pick must deliver the picked rendition, so a rendition the user chose is not clamped
    /// by the network -- neither the cellular ceiling nor Low Data Mode, nor AVFoundation's own expensive-network
    /// backstop (Android parity: `QualityTrackSelector`'s manual pick overrides its network ceiling).
    @Test func aPickedRenditionWinsOverTheNetworkCeilings() {
        let item = item()
        Self.p1080.apply(to: item, layerSize: Self.layerSize, isExpensive: true, isConstrained: true)
        #expect(item.preferredMaximumResolution == CGSize(width: 1920, height: 1080))
        #expect(item.preferredPeakBitRate == 0)
        #expect(item.preferredMaximumResolutionForExpensiveNetworks == CGSize(width: 1920, height: 1080))
        #expect(item.preferredPeakBitRateForExpensiveNetworks == 0)
    }

    @Test func cellularCeilingClampsAutoTo720p() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: CGSize(width: 2532, height: 1170), isExpensive: true, isConstrained: false)
        #expect(item.preferredMaximumResolution == CGSize(width: 1280, height: 720))
        #expect(item.preferredPeakBitRate == 2_500_000)
    }

    /// An Auto cap already at or below the cellular ceiling is left alone on resolution -- only
    /// bitrate 0 ("unbounded") gets pulled down to the ceiling.
    @Test func cellularCeilingDoesNotRaiseAnAutoCapAlreadyBelowIt() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: CGSize(width: 854, height: 480), isExpensive: true, isConstrained: false)
        #expect(item.preferredMaximumResolution == CGSize(width: 854, height: 480))
        #expect(item.preferredPeakBitRate == 2_500_000)
    }

    @Test func lowDataModeForcesDataSaverOnAuto() {
        let item = item()
        QualityOption.auto.apply(to: item, layerSize: CGSize(width: 2532, height: 1170), isExpensive: false, isConstrained: true)
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

    /// Fix-round-1 F2: the native backstop is set on Auto and Data Saver, independent of the primary cap, so
    /// AVFoundation itself still enforces a ceiling on a mid-playback network handover.
    @Test func expensiveNetworkBackstopIsSetOnAutoAndDataSaver() {
        for option in [QualityOption.auto, .dataSaver] {
            let item = item()
            option.apply(to: item, layerSize: Self.layerSize, isExpensive: false, isConstrained: false)
            #expect(item.preferredMaximumResolutionForExpensiveNetworks == CGSize(width: 1280, height: 720), "\(option)")
            #expect(item.preferredPeakBitRateForExpensiveNetworks == 2_500_000, "\(option)")
        }
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
        QualityOption.auto.apply(to: item, layerSize: Self.layerSize, network: network)
        let expected = QualityOption.ceiling(for: .auto, layerSize: Self.layerSize,
                                             isExpensive: network.isExpensive, isConstrained: network.isConstrained)
        #expect(item.preferredMaximumResolution == expected.resolution)
        #expect(item.preferredPeakBitRate == expected.bitrate)
    }

    // MARK: - A lower pick needs a fresh item (owner report 2026-11-02, issue 2)

    /// A lower ceiling binds only the segments AVPlayer fetches after it: measured on the iPhone 17
    /// simulator against a catalog HLS (C3BXt0zJr3o), a 1080p -> 480p pick kept drawing 1920x1080 for
    /// 36 s and more (the buffered segments), while a fresh item on the same asset drew 854x480 within
    /// 3 s. A raise needs nothing: the same run drew 1080p within 3 s of the pick.
    private func needsFreshItem(_ old: QualityOption?, _ new: QualityOption, layer: CGSize = layerSize,
                                playing: CGSize = .zero, expensive: Bool = false, constrained: Bool = false) -> Bool {
        QualityOption.needsFreshItem(from: old, to: new, layerSize: layer, playing: playing, isExpensive: expensive,
                                     isConstrained: constrained)
    }

    /// Cubic r2 P2: a lower SIZE cap flushes only what is drawn above it. Picking the stream's top rendition from a
    /// fullscreen Auto lowers the cap past the layer, yet what plays already fits -- a rebuild would only restart ABR.
    /// Nothing drawn yet (`.zero`) is unknown: rebuild as before. A lower BITRATE cap (Data Saver) always rebuilds.
    @Test func aLowerSizeCapRebuildsOnlyWhenWhatPlaysIsAboveIt() {
        let fullscreen = CGSize(width: 2556, height: 1179)
        #expect(!needsFreshItem(.auto, Self.p1080, layer: fullscreen, playing: CGSize(width: 1920, height: 1080)))
        #expect(!needsFreshItem(.auto, Self.p720, layer: fullscreen, playing: CGSize(width: 640, height: 360)))
        #expect(needsFreshItem(.auto, Self.p720, layer: fullscreen, playing: CGSize(width: 1920, height: 1080)))
        #expect(needsFreshItem(.auto, Self.p1080, layer: fullscreen))
        #expect(needsFreshItem(Self.p480, .dataSaver, playing: CGSize(width: 854, height: 480)))
    }

    @Test func aLowerResolutionPickNeedsAFreshItem() {
        #expect(needsFreshItem(Self.p1080, Self.p480))
        #expect(needsFreshItem(Self.p1080, Self.p720))
        #expect(needsFreshItem(Self.p360, .rendition(width: 426, height: 240)))
        #expect(needsFreshItem(.auto, Self.p480, layer: CGSize(width: 1206, height: 678)))
        // Review of a2c737ba: the cap binds width and height apart. A portrait layer's Auto is a bigger AREA than 720p,
        // but its 1206-pixel width admits nothing wider than 854x480 -- a downgrade.
        #expect(needsFreshItem(Self.p720, .auto, layer: CGSize(width: 1206, height: 2622)))
    }

    @Test func aLowerBitratePickNeedsAFreshItem() {
        // Same 854x480 resolution; Data Saver adds the 1.2 Mbps peak.
        #expect(needsFreshItem(Self.p480, .dataSaver))
    }

    @Test func aHigherOrUnchangedPickKeepsTheItem() {
        #expect(!needsFreshItem(Self.p480, Self.p1080))
        #expect(!needsFreshItem(.dataSaver, Self.p480))
        #expect(!needsFreshItem(.auto, Self.p1080, layer: CGSize(width: 1206, height: 678)))
        #expect(!needsFreshItem(Self.p720, Self.p720))
        #expect(!needsFreshItem(nil, Self.p480), "the first apply is a prepare, not a pick")
    }

    /// The effective ceiling decides, not the label: on cellular a big layer's Auto already caps at 720p, and Low Data
    /// Mode caps Auto at Data Saver's.
    @Test func aPickThatLeavesTheEffectiveCeilingUnchangedKeepsTheItem() {
        #expect(!needsFreshItem(.auto, Self.p720, layer: CGSize(width: 2532, height: 1170), expensive: true))
        #expect(!needsFreshItem(.auto, .dataSaver, constrained: true))
    }

    @Test func labelsMatchTheAndroidFormat() {
        // I5 (B1 final review): the two word-labels are localized now -- pinned against the
        // catalog keys, not the English literals, so the ar/nl legs aren't silently English.
        #expect(QualityOption.auto.label == String(localized: "player_quality_auto"))
        #expect(Self.p1080.label == "1080p (1920×1080)")
        #expect(Self.p720.label == "720p (1280×720)")
        #expect(QualityOption.rendition(width: 426, height: 240).label == "240p (426×240)")
        #expect(QualityOption.dataSaver.label == String(localized: "player_quality_data_saver"))
    }

    /// The menu rows' accessibility identifiers (`player.qualityOption.<id>`, `shorts.qualityOption.<id>`).
    @Test func identifiersNameTheRow() {
        #expect(QualityOption.auto.id == "auto")
        #expect(Self.p720.id == "720")
        #expect(QualityOption.dataSaver.id == "dataSaver")
    }
}
