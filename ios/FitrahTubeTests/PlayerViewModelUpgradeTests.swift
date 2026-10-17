import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Spec R43 (revisions 3–4): 360p first, HD in the background, a commit the host asks for, a drop-back that spends
/// nothing, and the reports that keep one link from carrying two streams.
@MainActor @Suite(.perTest)
struct PlayerViewModelUpgradeTests {
    private static let standard = Resolved(stream: .progressive(url: URL(string: "https://example.com/a.mp4")!, label: "360p"),
                                           client: .android, userAgent: "ua", resolvedAt: Date(), expiresAt: nil)
    private static let native = Resolved(stream: .hls(url: URL(string: "https://example.com/a.m3u8")!, isLive: false,
                                                      audioOnlyURL: nil, captionTracks: []),
                                         client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: nil)

    private static func hd() -> Resolved {
        Resolved(stream: .hls(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!, isLive: false,
                              audioOnlyURL: URL(string: "https://example.com/a.m4a"), captionTracks: [],
                              synthetic: SyntheticHLS(video: [], audio: [])),
                 client: .tvSamsung, userAgent: "tv", resolvedAt: Date(), expiresAt: nil)
    }

    private static func masterURL(_ resolved: Resolved) -> URL {
        guard case .hls(let url, _, _, _, _) = resolved.stream else { preconditionFailure("not an .hls stream") }
        return url
    }

    /// Opens on demand, for an upgrade that must still be in flight when the test moves on.
    actor AsyncGate {
        private var isOpen = false
        private var waiters: [CheckedContinuation<Void, Never>] = []
        func wait() async { if !isOpen { await withCheckedContinuation { waiters.append($0) } } }
        func open() { isOpen = true; waiters.forEach { $0.resume() }; waiters = [] }
    }

    /// Scripts `HDUpgrade` and records what was asked, failed and refused.
    nonisolated final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var _asked: [String] = []
        private var _failed: [String] = []
        private var _refused = 0
        var answer: Resolved?
        var gate: AsyncGate?

        var asked: [String] { lock.withLock { _asked } }
        var failed: [String] { lock.withLock { _failed } }
        var refused: Int { lock.withLock { _refused } }

        var upgrade: HDUpgrade {
            HDUpgrade(upgrade: { id in
                self.lock.withLock { self._asked.append(id) }
                await self.gate?.wait()
                return self.answer
            }, failed: { id in self.lock.withLock { self._failed.append(id) } },
               refused: { self.lock.withLock { self._refused += 1 } })
        }
    }

    private actor ScriptedResolver: StreamResolving {
        private var outcomes: [Resolved]
        private(set) var calls = 0
        init(_ outcomes: [Resolved]) { self.outcomes = outcomes }
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            calls += 1
            return outcomes.count > 1 ? outcomes.removeFirst() : outcomes[0]
        }
        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    private func model(_ resolver: ScriptedResolver, hd: HDUpgrade?,
                       args: PlayerArgs = PlayerArgs(videoId: "abcdefghijk", channelId: "ch1"),
                       queueSource: (any PlaylistQueueSource)? = nil) -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "PlayerViewModelUpgradeTests.\(UUID().uuidString)")!)
        settings.safeMode = false   // Safe Mode defaults on and disables auto-advance (`playToEnd`)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args, queueSource: queueSource, hd: hd)
    }

    private func waitForPending(_ vm: PlayerViewModel) async {
        for _ in 0..<500 where vm.pendingUpgrade == nil { try? await Task.sleep(for: .milliseconds(2)) }
    }

    @Test func a360pStartAsksForHDAndPublishesItWithoutLeavingThe360p() async {
        let recorder = Recorder()
        let upgrade = Self.hd()
        recorder.answer = upgrade
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        #expect(recorder.asked == ["abcdefghijk"])
        #expect(vm.pendingUpgrade.map(Self.masterURL) == Self.masterURL(upgrade))
        #expect(vm.state == .rung2Progressive(Self.standard))
    }

    @Test func aNativeHLSStartNeverAsks() async {
        let recorder = Recorder()
        let vm = model(ScriptedResolver([Self.native]), hd: recorder.upgrade)
        await vm.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.asked.isEmpty)
    }

    @Test func anOfflinePlayerNeverAsks() async {   // R8
        let recorder = Recorder()
        recorder.answer = Self.hd()
        var args = PlayerArgs(videoId: "abcdefghijk", channelId: "ch1")
        args.offlineItemId = "saved-1"
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade, args: args)
        await vm.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.asked.isEmpty)
    }

    @Test func aCommitMovesToReadyOnceAndKeepsThe360p() async {
        let recorder = Recorder()
        let upgrade = Self.hd()
        recorder.answer = upgrade
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        #expect(vm.commitUpgrade(Self.masterURL(upgrade)))
        #expect(vm.state == .ready(upgrade))
        #expect(vm.pendingUpgrade == nil)
        #expect(!vm.commitUpgrade(Self.masterURL(upgrade)))
    }

    @Test func aRecoveryEventOnTheHDStreamDropsBackTo360pWithoutAResolveOrBudget() async {
        let recorder = Recorder()
        let upgrade = Self.hd()
        recorder.answer = upgrade
        let resolver = ScriptedResolver([Self.standard])
        let vm = model(resolver, hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        _ = vm.commitUpgrade(Self.masterURL(upgrade))
        await vm.handleRecoveryEvent(.stall)
        #expect(vm.state == .rung2Progressive(Self.standard))
        #expect(await resolver.calls == 1)
        #expect(recorder.failed == ["abcdefghijk"])
        await vm.handleRecoveryEvent(.stall)          // the 360p's own incident: Phase 1's same-rung re-resolve, budget intact
        #expect(await resolver.calls == 2)
    }

    /// Plan review I4: a 360p incident while HD stages means the link cannot carry both. HD is off for the video, and the
    /// 360p gets exactly 1.0.3's recovery.
    @Test func a360pIncidentWhileHDStagesDeniesHDThenRecoversAsBefore() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let resolver = ScriptedResolver([Self.standard])
        let vm = model(resolver, hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        await vm.handleRecoveryEvent(.stall)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.failed == ["abcdefghijk"])
        #expect(await resolver.calls == 2)            // Phase 1's same-rung re-resolve, as in 1.0.3
        #expect(vm.state == .rung2Progressive(Self.standard))
    }

    @Test func aStagedFailureReportsHDFailedOnceAndStaysOn360p() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        vm.upgradeFailed()
        vm.upgradeFailed()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.failed == ["abcdefghijk"])
        #expect(vm.pendingUpgrade == nil)
        #expect(vm.state == .rung2Progressive(Self.standard))
    }

    @Test func aRefusedSidxIsReportedToTheResolver() async {   // R42 (revision 4)
        let recorder = Recorder()
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        vm.upgradeRefused()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.refused == 1)
    }

    /// Plan review M11: a screen that went away stops asking, and a late answer never lands.
    @Test func leavingTheScreenCancelsAnUpgradeInFlight() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let gate = AsyncGate()
        recorder.gate = gate
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        vm.stopUpgrade()
        await gate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.pendingUpgrade == nil)
    }

    /// Review Focus 4: an advance, Up Next or Retry is a new resolve, and the late answer belongs to the old stream.
    @Test func aLateUpgradeForThePreviousVideoIsNeverCommitted() async {
        let recorder = Recorder()
        let late = Self.hd()
        recorder.answer = late
        let gate = AsyncGate()
        recorder.gate = gate
        let vm = model(ScriptedResolver([Self.standard, Self.native]), hd: recorder.upgrade)
        await vm.open()                 // 360p; its upgrade now waits at the gate
        await vm.retry()                // a new resolve lands VISIONOS
        await gate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.pendingUpgrade == nil)
        #expect(!vm.commitUpgrade(Self.masterURL(late)))
        #expect(vm.state == .ready(Self.native))
    }

    /// Pre-flight scan M8 (spec §6.2): the same for an advance to the next playlist item.
    @Test func aLateUpgradeIsNeverCommittedAfterAnAdvance() async {
        let recorder = Recorder()
        let late = Self.hd()
        recorder.answer = late
        let gate = AsyncGate()
        recorder.gate = gate
        let vm = model(ScriptedResolver([Self.standard, Self.native]), hd: recorder.upgrade,
                       args: PlayerArgs(videoId: "abcdefghijk", playlistId: "PL"),
                       queueSource: FakeQueueSource(pages: [(ids: ["abcdefghijk", "bcdefghijkl"], next: nil)]))
        await vm.open()                 // the first item's 360p; its upgrade waits at the gate
        await vm.playToEnd()            // the advance lands the second item on VISIONOS
        await gate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.args.videoId == "bcdefghijkl")
        #expect(vm.pendingUpgrade == nil)
        #expect(!vm.commitUpgrade(Self.masterURL(late)))
        #expect(vm.state == .ready(Self.native))
    }

    /// Pre-flight scan (refused commit): an upgrade the view model can no longer take is dropped and HD is denied for
    /// that video, so no state can re-stage it every second.
    @Test func aRefusedCommitDropsTheUpgradeAndDeniesHDForThatVideo() async {
        let recorder = Recorder()
        let upgrade = Self.hd()
        recorder.answer = upgrade
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        vm.debugForceRecoveryExhausted()
        #expect(!vm.commitUpgrade(Self.masterURL(upgrade)))
        #expect(vm.pendingUpgrade == nil)
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.failed == ["abcdefghijk"])
    }
}
