import AVFoundation
import Foundation
import InnerTubeKit
import Testing
import UIKit
@testable import FitrahTube

/// Spec R43 (revision 4): the stall watchdog on a handed-over item, the loader on synthetic assets, the pure staging rules,
/// and the PRODUCTION decision (`HDStager.step`) driven with fakes: a real view model, a real AVPlayer, local files.
@MainActor @Suite(.perTest)
struct HDStagerTests {
    private static let model = SyntheticHLS(
        video: [.init(url: URL(string: "https://fixture.invalid/v0")!, codecs: "avc1.640028", bandwidth: 1, initRange: 0...1,
                      indexRange: 2...3, contentLength: 9)],
        audio: [.init(url: URL(string: "https://fixture.invalid/a0")!, codecs: "mp4a.40.2", bandwidth: 1, initRange: 0...1,
                      indexRange: 2...3, contentLength: 9, isDefault: true)])

    /// `.invalid` never resolves, so the players these tests build make no network traffic.
    private static func hd() -> Resolved {
        Resolved(stream: .hls(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!, isLive: false,
                              audioOnlyURL: URL(string: "https://fixture.invalid/a0.m4a")!, captionTracks: [], synthetic: model),
                 client: .tvSamsung, userAgent: "TV-UA", resolvedAt: Date(), expiresAt: nil)
    }

    /// The bundled 2 s clip (Debug only), and the same file under another URL string -- a different stream to AVPlayer.
    /// `nonisolated`: the `Standard360` actor reads it.
    nonisolated private static var clip: URL { Bundle.main.url(forResource: "player-fixture", withExtension: "mp4")! }
    nonisolated private static var clipElsewhere: URL {
        URL(string: clip.deletingLastPathComponent().absoluteString + "./" + clip.lastPathComponent)!
    }

    // MARK: - C1: the watchdog on an item that is already ready

    @Test(.timeLimit(.minutes(1))) func anItemAlreadyReadyWhenObservedArmsTheStallWatchdog() async throws {
        let player = AVPlayer(url: Self.clip)
        for _ in 0..<300 where player.currentItem?.status != .readyToPlay { try await Task.sleep(for: .milliseconds(10)) }
        let item = try #require(player.currentItem)
        let coordinator = PlayerHostView.Coordinator(backgroundPlay: false)
        coordinator.observe(item: item, player: player, model: nil, isLive: false, playToEnd: .none)
        defer { coordinator.stopObserving() }
        var watchdog = coordinator.watchdog
        #expect(watchdog.armed)
        let start = Date()
        _ = watchdog.tick(playbackTime: 1, bufferedEnd: 2, isStalled: true, isLive: false, now: start)
        #expect(watchdog.tick(playbackTime: 1, bufferedEnd: 2, isStalled: true, isLive: false,
                              now: start.addingTimeInterval(7)).fire)          // R43's stall drop-back can fire
    }

    // MARK: - the loader on synthetic assets (R12)

    @Test func aSyntheticStreamGetsTheLoader() throws {
        let player = try #require(PlayerHostView.player(for: .ready(Self.hd()), replacing: nil))
        let asset = try #require(player.currentItem?.asset as? AVURLAsset)
        #expect(asset.url.scheme == "fitrah-hls")
        #expect(asset.resourceLoader.delegate is SyntheticHLSLoader)
        player.replaceCurrentItem(with: nil)
    }

    @Test func audioOnlyOnASyntheticStreamPlaysTheDirectM4a() throws {
        let player = try #require(PlayerHostView.player(for: .ready(Self.hd()), replacing: nil, audioOnly: true))
        let asset = try #require(player.currentItem?.asset as? AVURLAsset)
        #expect(asset.url.absoluteString == "https://fixture.invalid/a0.m4a")
        #expect(asset.resourceLoader.delegate == nil)
        player.replaceCurrentItem(with: nil)
    }

    @Test func rebuildingTheHDMasterAfterAudioOnlyReattachesTheLoader() throws {   // Review Focus 3
        let state = StreamState.ready(Self.hd())
        let player = try #require(PlayerHostView.player(for: state, replacing: nil, audioOnly: true))
        let back = try #require(PlayerHostView.player(for: state, replacing: player, audioOnly: false))
        #expect(back === player)
        #expect((back.currentItem?.asset as? AVURLAsset)?.resourceLoader.delegate is SyntheticHLSLoader)
        player.replaceCurrentItem(with: nil)
    }

    // MARK: - the pure staging rules

    @Test func needClampsToWhatIsLeftUnlessTheItemLoops() {   // Review Focus 1
        #expect(HDStager.need(position: 3, duration: 600, loops: false) == 10)
        #expect(HDStager.need(position: 592, duration: 600, loops: false) == 7.5)
        #expect(HDStager.need(position: 592, duration: 600, loops: true) == nil)     // a looping Short waits for the loop
        #expect(HDStager.need(position: 2, duration: 8, loops: true) == 5.5)          // shorter than 10.5 s: what is left
        #expect(HDStager.need(position: 3, duration: nil, loops: false) == 10)        // unknown length: the full 10 s
    }

    @Test func isBufferedNeedsOneRangeCoveringWhatIsNeeded() {
        #expect(HDStager.isBuffered([(0, 15)], position: 3, need: 10))
        #expect(!HDStager.isBuffered([(0, 12)], position: 3, need: 10))
        #expect(!HDStager.isBuffered([(4, 20)], position: 3, need: 10))
        #expect(HDStager.isBuffered([(3.4, 14)], position: 3, need: 10))              // a seek that landed 0.4 s late
        #expect(!HDStager.isBuffered([(0, 5), (6, 20)], position: 3, need: 10))       // a gap
    }

    @Test func mustReseekWhenTheLivePositionLeavesTheWindow() {   // plan review I3, Review Focus 2
        #expect(!HDStager.mustReseek(position: 30, seekedTo: 28, need: 10))   // inside the 20 s window: it is already filling
        #expect(HDStager.mustReseek(position: 10, seekedTo: 28, need: 10))    // a seek back, or a Short's loop
        #expect(HDStager.mustReseek(position: 40, seekedTo: 28, need: 10))    // a seek ahead, or a slow fill left behind
    }

    @Test func the360pMustHoldFifteenSecondsAheadOrToItsEnd() {   // plan review I4
        #expect(HDStager.standardHasHeadroom([(0, 20)], position: 4, duration: 600))
        #expect(!HDStager.standardHasHeadroom([(0, 12)], position: 4, duration: 600))
        #expect(HDStager.standardHasHeadroom([(0, 600)], position: 590, duration: 600))
    }

    @Test func stagingStopsOnlyInTheBackgroundInPiPOrOnAnExternalRoute() {   // M13, R18
        #expect(HDStager.mayStage(appState: .active, pictureInPicture: false, external: false))
        #expect(HDStager.mayStage(appState: .inactive, pictureInPicture: false, external: false))   // Control Center
        #expect(!HDStager.mayStage(appState: .background, pictureInPicture: false, external: false))
        #expect(!HDStager.mayStage(appState: .active, pictureInPicture: true, external: false))
        #expect(!HDStager.mayStage(appState: .active, pictureInPicture: false, external: true))
    }

    // MARK: - the production decision with fakes (plan review I5)

    nonisolated final class Reports: @unchecked Sendable {
        private let lock = NSLock()
        private var ids: [String] = []
        func add(_ id: String) { lock.withLock { ids.append(id) } }
        var failed: [String] { lock.withLock { ids } }
    }

    private actor Standard360: StreamResolving {
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            Resolved(stream: .progressive(url: HDStagerTests.clip, label: "360p"), client: .android, userAgent: "ua",
                     resolvedAt: Date(), expiresAt: nil)
        }
        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    /// A live broadcast: native `.hls(isLive: true)`, which lands `.ready`, never `.rung2Progressive`.
    private actor LiveNow: StreamResolving {
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            Resolved(stream: .hls(url: URL(string: "https://fixture.invalid/live.m3u8")!, isLive: true, audioOnlyURL: nil,
                                  captionTracks: []),
                     client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: nil, isLiveNow: true)
        }
        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    private static func settings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "HDStagerTests.\(UUID().uuidString)")!)
    }

    /// A view model on the bundled clip with an upgrade pending, and its player paused half a second in.
    private func playing(_ reports: Reports) async throws -> (PlayerViewModel, AVPlayer, Resolved) {
        let upgrade = Self.hd()
        let vm = PlayerViewModel(
            resolver: Standard360(), settings: Self.settings(), args: PlayerArgs(videoId: "abcdefghijk", channelId: "ch1"),
            hd: HDUpgrade(upgrade: { _ in upgrade }, failed: { reports.add($0) }, refused: {}))
        await vm.open()
        for _ in 0..<500 where vm.pendingUpgrade == nil { try await Task.sleep(for: .milliseconds(2)) }
        let player = try #require(PlayerHostView.player(for: vm.state, replacing: nil))
        for _ in 0..<300 where player.currentTime().seconds < 0.5 { try await Task.sleep(for: .milliseconds(10)) }
        player.pause()
        return (vm, player, upgrade)
    }

    /// Stands in for the host's item settings (the quality ceiling) so a test can see which items got them.
    private static let configuredPeak: Double = 1_234

    /// The fake `make`: a stager over the bundled clip instead of the loader and the network. Everything `step` decides
    /// and `handOver` does is the production code.
    private func localStager(_ upgrade: Resolved, _ masterURL: URL, _ position: TimeInterval) -> HDStager {
        HDStager(item: AVPlayerItem(url: Self.clipElsewhere), masterURL: masterURL, position: position,
                 configure: { $0.preferredPeakBitRate = Self.configuredPeak })
    }

    private func runSteps(_ vm: PlayerViewModel, _ player: AVPlayer,
                          make: (Resolved, URL, TimeInterval) -> HDStager) async throws -> AVPlayerItem? {
        var stager = HDStager.step(nil, model: vm, player: player, eligible: true, loops: false, make: make)
        let staged = stager?.item
        for _ in 0..<300 where stager != nil {
            try await Task.sleep(for: .milliseconds(10))
            stager = HDStager.step(stager, model: vm, player: player, eligible: true, loops: false, make: make)
        }
        return staged
    }

    /// Gate 1 ruling (B1): an item never moves between players, so the visible player gets a NEW item on the staged
    /// item's asset, with the host's item settings.
    @Test(.timeLimit(.minutes(1))) func stepHandsAReadyStagerOverAtTheLivePositionAndStaysPaused() async throws {   // Review Focus 2
        let reports = Reports()
        let (vm, player, upgrade) = try await playing(reports)
        let position = player.currentTime().seconds
        let staged = try #require(try await runSteps(vm, player, make: localStager))
        let visible = try #require(player.currentItem)
        #expect(visible !== staged)
        #expect(visible.asset === staged.asset)
        #expect(staged.preferredPeakBitRate == Self.configuredPeak)
        #expect(visible.preferredPeakBitRate == Self.configuredPeak)
        #expect(vm.state == .ready(upgrade))
        try await Task.sleep(for: .milliseconds(300))
        #expect(abs(player.currentTime().seconds - position) <= 0.1)
        #expect(player.timeControlStatus == .paused)
        #expect(reports.failed.isEmpty)
        player.replaceCurrentItem(with: nil)
    }

    @Test(.timeLimit(.minutes(1))) func aRefusedCommitLeavesThe360pAndDropsTheUpgrade() async throws {
        let reports = Reports()
        let (vm, player, _) = try await playing(reports)
        let standardItem = player.currentItem
        vm.debugForceRecoveryExhausted()            // the view model moved on while the upgrade was pending
        let staged = try await runSteps(vm, player, make: localStager)
        try await Task.sleep(for: .milliseconds(50))
        #expect(staged != nil)                       // pre-flight scan: the stager was built and reached the commit
        #expect(player.currentItem === standardItem)
        #expect(vm.pendingUpgrade == nil)            // nothing left to re-stage every second
        #expect(reports.failed == ["abcdefghijk"])
        player.replaceCurrentItem(with: nil)
    }

    @Test(.timeLimit(.minutes(1))) func aFailedStagerCostsHDForThatVideoOnly() async throws {   // R41
        let reports = Reports()
        let (vm, player, _) = try await playing(reports)
        let standardItem = player.currentItem
        _ = try await runSteps(vm, player) { _, masterURL, position in
            HDStager(item: AVPlayerItem(url: URL(fileURLWithPath: "/nonexistent/hd.mp4")), masterURL: masterURL, position: position)
        }
        try await Task.sleep(for: .milliseconds(50))
        #expect(reports.failed == ["abcdefghijk"])
        #expect(vm.pendingUpgrade == nil)
        #expect(player.currentItem === standardItem)
        player.replaceCurrentItem(with: nil)
    }

    @Test(.timeLimit(.minutes(1))) func anIneligibleTickStagesNothing() async throws {
        let (vm, player, _) = try await playing(Reports())
        var made = 0
        let stager = HDStager.step(nil, model: vm, player: player, eligible: false, loops: false) { upgrade, url, position in
            made += 1
            return localStager(upgrade, url, position)
        }
        #expect(stager == nil)
        #expect(made == 0)
        player.replaceCurrentItem(with: nil)
    }

    /// Live streams are native `.hls`, never `.rung2Progressive`, so nothing is pending and no stager is ever built.
    @Test func aLiveStreamNeverCreatesAStager() async throws {
        var asked = 0
        let upgrade = Self.hd()
        let vm = PlayerViewModel(resolver: LiveNow(), settings: Self.settings(),
                                 args: PlayerArgs(videoId: "abcdefghijk", channelId: "ch1"),
                                 hd: HDUpgrade(upgrade: { _ in upgrade }, failed: { _ in }, refused: {}))
        await vm.open()
        try await Task.sleep(for: .milliseconds(50))
        let stager = HDStager.step(nil, model: vm, player: AVPlayer(), eligible: true, loops: false) { upgrade, url, position in
            asked += 1
            return localStager(upgrade, url, position)
        }
        #expect(vm.state.isLiveNow)
        #expect(vm.pendingUpgrade == nil)
        #expect(stager == nil)
        #expect(asked == 0)
    }
}
