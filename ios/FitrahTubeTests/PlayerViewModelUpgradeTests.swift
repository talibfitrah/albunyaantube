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

    private static func hd(expiresAt: Date? = nil) -> Resolved {
        Resolved(stream: .hls(url: URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!, isLive: false,
                              audioOnlyURL: URL(string: "https://example.com/a.m4a"), captionTracks: [],
                              synthetic: SyntheticHLS(video: [], audio: [])),
                 client: .tvSamsung, userAgent: "tv", resolvedAt: Date(), expiresAt: expiresAt)
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

    /// Scripts `HDUpgrade` and records what was asked, failed and refused. `failedGate` holds a `failed` report
    /// until opened; `cancelledAsks` counts asks whose task was cancelled by the time the answer came back.
    nonisolated final class Recorder: @unchecked Sendable {
        private let lock = NSLock()
        private var _asked: [String] = []
        private var _failed: [String] = []
        private var _refused = 0
        private var _cancelledAsks = 0
        var answer: Resolved?
        var gate: AsyncGate?
        var failedGate: AsyncGate?

        var asked: [String] { lock.withLock { _asked } }
        var failed: [String] { lock.withLock { _failed } }
        var refused: Int { lock.withLock { _refused } }
        var cancelledAsks: Int { lock.withLock { _cancelledAsks } }

        var upgrade: HDUpgrade {
            HDUpgrade(upgrade: { id in
                self.lock.withLock { self._asked.append(id) }
                await self.gate?.wait()
                if Task.isCancelled { self.lock.withLock { self._cancelledAsks += 1 } }
                return self.answer
            }, failed: { id in
                await self.failedGate?.wait()
                self.lock.withLock { self._failed.append(id) }
            }, refused: { self.lock.withLock { self._refused += 1 } })
        }
    }

    /// `throwingOnCall` (1-based) makes that one call throw; `demoted` records every step-down.
    private actor ScriptedResolver: StreamResolving {
        private var outcomes: [Resolved]
        private let throwingOnCall: Int?
        private(set) var calls = 0
        private(set) var demoted: [ClientFamily] = []
        init(_ outcomes: [Resolved], throwingOnCall: Int? = nil) {
            self.outcomes = outcomes
            self.throwingOnCall = throwingOnCall
        }
        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            calls += 1
            if calls == throwingOnCall { throw URLError(.timedOut) }
            return outcomes.count > 1 ? outcomes.removeFirst() : outcomes[0]
        }
        func demote(_ videoId: String, failed: ClientFamily) async { demoted.append(failed) }
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
        #expect(await resolver.demoted.isEmpty)       // a spent budget would have stepped down here
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

    /// Fix round 1: leaving the screen also drops an upgrade that was already published.
    @Test func leavingTheScreenDropsAPublishedUpgrade() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        #expect(vm.pendingUpgrade != nil)
        vm.stopUpgrade()
        #expect(vm.pendingUpgrade == nil)
    }

    /// Fix round 1 (spec §4.9): a silent TTL refresh that never lands keeps the kept 360p, so an HD incident still
    /// drops back and the HD stream never reaches the budget machine (which could demote `.tvSamsung`).
    @Test func aFailedRefreshOfTheHDStreamKeepsTheDropBack() async {
        let recorder = Recorder()
        let upgrade = Self.hd(expiresAt: Date())
        recorder.answer = upgrade
        let resolver = ScriptedResolver([Self.standard], throwingOnCall: 2)
        let vm = model(resolver, hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        #expect(vm.commitUpgrade(Self.masterURL(upgrade)))
        await vm.reResolveIfExpiring()                // throws; `silent` keeps the HD stream on screen
        #expect(await resolver.calls == 2)
        #expect(vm.state == .ready(upgrade))
        await vm.handleRecoveryEvent(.stall)
        #expect(vm.state == .rung2Progressive(Self.standard))
        #expect(recorder.failed == ["abcdefghijk"])
        #expect(await resolver.calls == 2)
        #expect(await resolver.demoted.isEmpty)
    }

    /// Fix round 1: video A's published HD never lands on video B's 360p. B's 360p passes the commit's own rung-2 and
    /// video checks, so clearing `pendingUpgrade` when a resolve starts is the guard.
    @Test func aPublishedUpgradeIsNeverCommittedOntoTheNextVideos360p() async {
        let recorder = Recorder()
        let old = Self.hd()
        recorder.answer = old
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade,
                       args: PlayerArgs(videoId: "abcdefghijk", playlistId: "PL"),
                       queueSource: FakeQueueSource(pages: [(ids: ["abcdefghijk", "bcdefghijkl"], next: nil)]))
        await vm.open()
        await waitForPending(vm)                      // A's HD is published
        recorder.answer = nil                         // B gets no HD of its own
        await vm.playToEnd()                          // B lands on 360p
        try? await Task.sleep(for: .milliseconds(50))
        #expect(vm.args.videoId == "bcdefghijkl")
        #expect(vm.state == .rung2Progressive(Self.standard))
        #expect(!vm.commitUpgrade(Self.masterURL(old)))
    }

    /// Fix round 1 (R41): a denial reaches the resolver before the next ask, so the ask a 360p incident's own
    /// re-resolve starts is already refused there.
    @Test func anHDFailureIsReportedBeforeTheNextAsk() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let failedGate = AsyncGate()
        recorder.failedGate = failedGate
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        await waitForPending(vm)
        await vm.handleRecoveryEvent(.stall)          // I4: HD denied, then the 360p's same-rung re-resolve lands
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.asked.count == 1)
        await failedGate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(recorder.failed == ["abcdefghijk"])
        #expect(recorder.asked.count == 2)
    }

    /// Fix round 1: giving up also stops the ask the last re-resolve started; nothing could take its answer.
    @Test func exhaustionCancelsTheUpgradeInFlight() async {
        let recorder = Recorder()
        recorder.answer = Self.hd()
        let gate = AsyncGate()
        recorder.gate = gate
        let vm = model(ScriptedResolver([Self.standard]), hd: recorder.upgrade)
        await vm.open()
        for _ in 0..<4 { await vm.handleRecoveryEvent(.stall) }   // same rung, two step-downs, then exhausted
        guard case .recoveryExhausted = vm.state else { Issue.record("not exhausted: \(vm.state)"); return }
        await gate.open()
        try? await Task.sleep(for: .milliseconds(50))
        #expect(!recorder.asked.isEmpty)
        #expect(recorder.cancelledAsks == recorder.asked.count)
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
