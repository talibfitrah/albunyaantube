import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// The pure half of Task 7: the budget machine + the stall decider. Every AVFoundation-facing part
/// (KVO on `AVPlayerItem.status`, the `AVPlayerItemFailedToPlayToEndTime` notification, the
/// periodic buffered-position sampler) is thin glue in `PlayerHostView.Coordinator` and is NOT
/// covered here -- it needs a real decoding player, which the unit target has no way to drive.
@Suite(.perTest)
struct PlaybackRecoveryTests {
    // MARK: - decide(): incident classes get ONE same-rung re-resolve, then step down

    @Test func failedBeforeFirstFrameStepsDownImmediately() {
        // spec §10: "`AVPlayerItem.status == .failed` before first frame -> next rung" -- no
        // same-rung re-resolve for this class, the rung itself never produced a frame.
        #expect(PlaybackRecovery.decide(event: .failedBeforeFirstFrame, state: RecoveryBudget()) == .stepDownRung)
    }

    @Test(arguments: [RecoveryEvent.playbackError, .stall])
    func recoverableClassReResolvesTheSameRungOnceThenStepsDown(_ event: RecoveryEvent) {
        var budget = RecoveryBudget()

        let first = PlaybackRecovery.decide(event: event, state: budget)
        #expect(first == .reResolveSameRung)
        budget.apply(first, for: event)

        let second = PlaybackRecovery.decide(event: event, state: budget)
        #expect(second == .stepDownRung)
    }

    @Test func eachIncidentClassGetsItsOwnSameRungAllowance() {
        var budget = RecoveryBudget()
        budget.apply(.reResolveSameRung, for: .playbackError)

        // A stall is a different incident class -- it hasn't spent its own same-rung re-resolve.
        #expect(PlaybackRecovery.decide(event: .stall, state: budget) == .reResolveSameRung)
        // M5: ...and the before-first-frame class still steps down, spent allowance or not.
        #expect(PlaybackRecovery.decide(event: .failedBeforeFirstFrame, state: budget) == .stepDownRung)
    }

    // MARK: - budgets: retries 3 / re-resolves 2 (spec §10)

    @Test func theThirdRecoveryAttemptIsTheLast() {
        var budget = RecoveryBudget()
        let script: [RecoveryEvent] = [.playbackError, .playbackError, .stall, .stall]
        var actions: [RecoveryAction] = []
        for event in script {
            let action = PlaybackRecovery.decide(event: event, state: budget)
            budget.apply(action, for: event)
            actions.append(action)
        }
        // 1: same-rung re-resolve; 2: step down (class spent); 3: the stall class's own same-rung
        // re-resolve (2nd and last re-resolve); 4: retries budget gone.
        #expect(actions == [.reResolveSameRung, .stepDownRung, .reResolveSameRung, .exhausted])
        #expect(budget.retries == 3)
        #expect(budget.reResolves == 2)
    }

    @Test func theReResolveBudgetIsTwo() {
        var budget = RecoveryBudget()
        budget.apply(.reResolveSameRung, for: .playbackError)
        budget.apply(.reResolveSameRung, for: .stall)
        #expect(budget.reResolves == 2)
        // Honest note: with three incident classes (one of which -- `.failedBeforeFirstFrame` --
        // never re-resolves at all) and `maxRetries == 3`, the retry budget always binds first, so
        // the `reResolves < maxReResolves` guard in `decide` is spec-mandated belt-and-braces
        // rather than a reachable branch today. It becomes load-bearing the moment a fourth class
        // or a budget refund lands, which is exactly why it's asserted here.
        #expect(RecoveryBudget.maxReResolves == 2)
    }

    @Test func stepDownsAloneAlsoExhaustTheRetryBudget() {
        var budget = RecoveryBudget()
        for _ in 0..<RecoveryBudget.maxRetries {
            let action = PlaybackRecovery.decide(event: .failedBeforeFirstFrame, state: budget)
            #expect(action == .stepDownRung)
            budget.apply(action, for: .failedBeforeFirstFrame)
        }
        #expect(PlaybackRecovery.decide(event: .failedBeforeFirstFrame, state: budget) == .exhausted)
    }

    // MARK: - reset semantics (Android parity, PlayerFragment.kt:1154-1165)

    @Test func playbackProgressRefundsTheReResolveBudgetButNotTheLifetimeRetries() {
        var budget = RecoveryBudget()
        budget.apply(.reResolveSameRung, for: .playbackError)
        budget.apply(.stepDownRung, for: .playbackError)

        budget.recordPlaybackProgress()

        #expect(budget.reResolves == 0)
        // The same class may re-resolve again: the failure episode is over.
        #expect(PlaybackRecovery.decide(event: .playbackError, state: budget) == .reResolveSameRung)
        // ...but the lifetime retry count only ever resets once, on the FIRST successful playback,
        // so a genuinely flapping stream still terminates.
        #expect(budget.retries == 0) // first progress of this stream: retries refunded
        budget.apply(.stepDownRung, for: .playbackError)
        budget.recordPlaybackProgress()
        #expect(budget.retries == 1) // second progress: no further refund
    }

    // MARK: - stall watchdog (player.md §3.2: armed after first READY, VOD 6 s / live 45 s,
    // only when the buffered position has NOT advanced)

    @Test func stallNeverFiresBeforeTheWatchdogIsArmed() {
        #expect(PlaybackRecovery.shouldFireStall(armed: false, elapsedSinceProgress: 600, isLive: false) == false)
    }

    @Test(arguments: [(5.9, false), (6.0, true), (44.0, true)])
    func vodStallFiresAtSixSeconds(_ elapsed: Double, _ expected: Bool) {
        #expect(PlaybackRecovery.shouldFireStall(armed: true, elapsedSinceProgress: elapsed, isLive: false) == expected)
    }

    @Test(arguments: [(6.0, false), (44.9, false), (45.0, true)])
    func liveStallFiresOnlyAtFortyFiveSeconds(_ elapsed: Double, _ expected: Bool) {
        #expect(PlaybackRecovery.shouldFireStall(armed: true, elapsedSinceProgress: elapsed, isLive: true) == expected)
    }

    // MARK: - StallWatchdog (fix round 1, C1): the clock only runs while the player is ACTUALLY
    // stalled, and any playback-position change resets it

    private static let t0 = Date(timeIntervalSinceReferenceDate: 0)

    /// Drives `seconds.count` one-second ticks and returns each tick's answer. `playbackTime` is
    /// held by `positions` (one per tick), so a frozen position is just a repeated value.
    private func run(_ watchdog: inout StallWatchdog, positions: [TimeInterval], buffered: TimeInterval = 100,
                     isStalled: Bool, isLive: Bool = false, from second: Int = 0) -> [StallWatchdog.Tick] {
        positions.enumerated().map { offset, position in
            watchdog.tick(playbackTime: position, bufferedEnd: buffered, isStalled: isStalled, isLive: isLive,
                          now: Self.t0.addingTimeInterval(TimeInterval(second + offset)))
        }
    }

    @Test func aBufferFlushingSeekDoesNotFalseFireWhileTheRebufferIsStillDownloading() {
        // Cubic #11: the re-arm branch required bufferedEnd to EXCEED the pre-seek high-water mark
        // (+0.1), but a buffer-flushing seek regresses bufferedEnd far below it -- so a legitimate
        // post-seek rebuffer that was visibly downloading the whole time could never re-arm and
        // false-fired `.stall` at 6 s. A regression is a flush: re-base the mark and re-arm.
        var watchdog = StallWatchdog(playbackTime: 300)
        watchdog.armed = true
        // Playing normally with a high buffered mark.
        _ = watchdog.tick(playbackTime: 301, bufferedEnd: 600, isStalled: true, isLive: false, now: Self.t0)
        // Seek back to 20: the position jump registers as progress, the buffer is flushed to just
        // past the target, and it grows ~1 s/s for 9 s while the player rebuffers (stalled).
        for i in 0..<9 {
            let tick = watchdog.tick(playbackTime: 20, bufferedEnd: 25 + Double(i), isStalled: true,
                                     isLive: false, now: Self.t0.addingTimeInterval(TimeInterval(2 + i)))
            #expect(!tick.fire, "tick \(i) fired on a rebuffer that was still downloading")
        }
    }

    @Test func aFullyBufferedItemPlayingNormallyNeverStalls() {
        // The pre-fix bug: a rung-2 MP4 finishes downloading in seconds, `loadedTimeRanges` stops
        // growing, and the watchdog fired 6 s later on a perfectly healthy stream.
        var watchdog = StallWatchdog()
        watchdog.armed = true
        let ticks = run(&watchdog, positions: (1...30).map(TimeInterval.init), isStalled: false)
        #expect(ticks.allSatisfy { !$0.fire })
        #expect(ticks.allSatisfy { $0.progressed })
    }

    @Test func aLongPauseDoesNotFireOnResume() {
        // Pre-fix: `timeControlStatus == .paused` isn't stalled, but the progress mark went stale
        // anyway, so the first tick after a >6 s pause fired instantly.
        var watchdog = StallWatchdog(playbackTime: 12)
        watchdog.armed = true
        // A minute paused: the position is frozen, so nothing here is "progress" -- only the fact
        // that a paused player isn't stalled keeps the clock from running.
        let paused = run(&watchdog, positions: Array(repeating: 12, count: 60), isStalled: false)
        #expect(paused.allSatisfy { !$0.fire })
        #expect(paused.allSatisfy { !$0.progressed })

        let resumed = run(&watchdog, positions: [12.5], isStalled: false, from: 60)
        #expect(resumed[0].fire == false)
        #expect(resumed[0].progressed == true)
    }

    @Test func aBackwardSeekCountsAsProgress() {
        // Pre-fix: the mark tracked a lifetime maximum, so seeking backwards froze it.
        var watchdog = StallWatchdog(playbackTime: 300)
        watchdog.armed = true
        let ticks = run(&watchdog, positions: [10], isStalled: true)
        #expect(ticks[0].progressed == true)
        #expect(ticks[0].fire == false)
    }

    @Test func aRealStallFiresOnceAfterSixSeconds() {
        var watchdog = StallWatchdog(playbackTime: 12)
        watchdog.armed = true
        let ticks = run(&watchdog, positions: Array(repeating: 12, count: 9), isStalled: true)
        // t0..t5 accumulate; the tick at +6 s fires; the episode then restarts its clock, so the
        // remaining ticks stay quiet until another full threshold passes.
        #expect(ticks.map(\.fire) == [false, false, false, false, false, false, true, false, false])
        #expect(ticks.allSatisfy { !$0.progressed })
    }

    @Test func progressDuringAStallRestartsTheClock() {
        var watchdog = StallWatchdog(playbackTime: 0)
        watchdog.armed = true
        // Five stalled seconds, then one frame of real progress, then five more stalled seconds:
        // never a full 6 s window, so nothing fires.
        var positions = Array(repeating: TimeInterval(0), count: 5)
        positions.append(1)
        positions.append(contentsOf: Array(repeating: TimeInterval(1), count: 5))
        let ticks = run(&watchdog, positions: positions, isStalled: true)
        #expect(ticks.allSatisfy { !$0.fire })
        #expect(ticks.filter(\.progressed).count == 1)
    }

    @Test func aStalledButStillDownloadingStreamReArmsInsteadOfFiring() {
        // player.md §3.2: a slow-but-working network re-arms. Buffered end creeps up every tick.
        var watchdog = StallWatchdog(playbackTime: 12)
        watchdog.armed = true
        let ticks = (0..<12).map { second in
            watchdog.tick(playbackTime: 12, bufferedEnd: 20 + TimeInterval(second), isStalled: true,
                          isLive: false, now: Self.t0.addingTimeInterval(TimeInterval(second)))
        }
        #expect(ticks.allSatisfy { !$0.fire })
    }

    @Test func anUnarmedWatchdogNeverFiresHoweverLongTheStall() {
        var watchdog = StallWatchdog(playbackTime: 0) // no first READY yet
        let ticks = run(&watchdog, positions: Array(repeating: 0, count: 60), isStalled: true)
        #expect(ticks.allSatisfy { !$0.fire })
    }

    @Test func liveStreamsWaitFortyFiveSecondsBeforeFiring() {
        var watchdog = StallWatchdog(playbackTime: 12)
        watchdog.armed = true
        let ticks = run(&watchdog, positions: Array(repeating: 12, count: 46), isStalled: true, isLive: true)
        #expect(ticks.prefix(45).allSatisfy { !$0.fire })
        #expect(ticks[45].fire == true)
    }

    @Test func theSeededPositionIsNotMistakenForProgress() {
        // I4: a replacement item is seeked back to the outgoing item's position; seeding from 0
        // would read that offset as a whole item's worth of playback and refund the budget.
        var watchdog = StallWatchdog(playbackTime: 42)
        watchdog.armed = true
        #expect(run(&watchdog, positions: [42], isStalled: true)[0].progressed == false)
    }

    // MARK: - VM integration (fake resolver; the same `StreamResolving` seam Task 2 introduced)

    private static func resolved(_ stream: ResolvedStream, at date: Date = Date()) -> Resolved {
        Resolved(stream: stream, client: .visionos, userAgent: "ua", resolvedAt: date, expiresAt: nil)
    }

    private static let hls = resolved(.hls(url: URL(string: "https://127.0.0.1:9/a.m3u8")!, isLive: false,
                                           audioOnlyURL: nil, captionTracks: []))
    private static let freshHLS = resolved(.hls(url: URL(string: "https://127.0.0.1:9/fresh.m3u8")!, isLive: false,
                                                audioOnlyURL: nil, captionTracks: []),
                                           at: Date().addingTimeInterval(30))
    private static let progressive = resolved(.progressive(url: URL(string: "https://127.0.0.1:9/a.mp4")!, label: "360p"))

    private actor FakeResolver: StreamResolving {
        private let outcomes: [Result<Resolved, Error>]
        private let gate: Gate?
        private let gatedCallIndex: Int
        private(set) var calls: [Bool] = [] // forceRefresh per call

        init(_ outcomes: [Result<Resolved, Error>], gate: Gate? = nil, gatedCallIndex: Int = 0) {
            self.outcomes = outcomes
            self.gate = gate
            self.gatedCallIndex = gatedCallIndex
        }

        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                     sourceChannelId: String?, forceRefresh: Bool,
                 requiresMuxed: Bool) async throws -> Resolved {
            let index = calls.count
            calls.append(forceRefresh)
            if calls.count == gatedCallIndex, let gate { await gate.block() }
            guard outcomes.indices.contains(index) else { fatalError("FakeResolver ran out of scripted outcomes") }
            return try outcomes[index].get()
        }
    }

    @MainActor private func makeViewModel(_ resolver: FakeResolver) -> PlayerViewModel {
        PlayerViewModel(resolver: resolver,
                        settings: UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "PlaybackRecoveryTests.\(UUID().uuidString)")!),
                        args: PlayerArgs(videoId: "abcdefghijk", channelId: "ch1"))
    }

    @Test @MainActor func recoveryReResolvesWithForceRefreshAndPublishesTheFreshStream() async {
        let resolver = FakeResolver([.success(Self.hls), .success(Self.freshHLS)])
        let vm = makeViewModel(resolver)
        await vm.open()

        await vm.handleRecoveryEvent(.playbackError)

        #expect(vm.state == .ready(Self.freshHLS))
        #expect(await resolver.calls == [false, true])
    }

    @Test @MainActor func recoveryNeverPassesThroughLoading() async {
        // Position preservation depends on this: `PlayerHostView.player(for:replacing:)` only keeps
        // the existing `AVPlayer` (and seeks the replacement item back to its `currentTime()`) while
        // the state stays playable. A `.loading` hop would blank the host, tear the player down and
        // restart the replacement at 0.
        let gate = Gate()
        let resolver = FakeResolver([.success(Self.hls), .success(Self.freshHLS)], gate: gate, gatedCallIndex: 2)
        let vm = makeViewModel(resolver)
        await vm.open()

        let recovering = Task { await vm.handleRecoveryEvent(.stall) }
        await gate.waitUntilBlocked() // the recovery re-resolve is genuinely in flight

        #expect(vm.state == .ready(Self.hls)) // still the old stream, never `.loading`

        await gate.release()
        await recovering.value
        #expect(vm.state == .ready(Self.freshHLS))
    }

    @Test @MainActor func demotionToProgressiveKeepsThePlayerAliveSoCurrentTimeCarriesOver() async {
        let resolver = FakeResolver([.success(Self.hls), .success(Self.progressive)])
        let vm = makeViewModel(resolver)
        await vm.open()

        await vm.handleRecoveryEvent(.failedBeforeFirstFrame)

        #expect(vm.state == .rung2Progressive(Self.progressive))
        // The host reuses the live player across the demotion (its `replacing:` path), which is what
        // carries `currentTime` over -- proven directly here at the host level.
        let player = PlayerHostView.player(for: .ready(Self.hls), replacing: nil)
        let demoted = PlayerHostView.player(for: vm.state, replacing: player)
        #expect(demoted === player)
    }

    @Test @MainActor func exhaustedBudgetLandsOnRecoveryExhaustedWithTheLastResolvedStream() async {
        let resolver = FakeResolver([.success(Self.hls), .success(Self.hls), .success(Self.hls), .success(Self.hls)])
        let vm = makeViewModel(resolver)
        await vm.open()

        for _ in 0..<RecoveryBudget.maxRetries { await vm.handleRecoveryEvent(.playbackError) }
        await vm.handleRecoveryEvent(.playbackError)

        #expect(vm.state == .recoveryExhausted(Self.hls))
        #expect(await resolver.calls.count == 1 + RecoveryBudget.maxRetries) // the 4th spends no network call
    }

    @Test @MainActor func concurrentEventsFromOneFailureSpendOneAttempt() async {
        // I3: a dead stream raises `AVPlayerItemFailedToPlayToEndTime` and `status == .failed`
        // together. Both used to be honoured: two budget slots, two resolves, one of them thrown
        // away by the generation guard.
        let gate = Gate()
        let resolver = FakeResolver([.success(Self.hls), .success(Self.freshHLS), .success(Self.hls),
                                     .success(Self.hls)], gate: gate, gatedCallIndex: 2)
        let vm = makeViewModel(resolver)
        await vm.open()

        let first = Task { await vm.handleRecoveryEvent(.playbackError) }
        await gate.waitUntilBlocked()
        await vm.handleRecoveryEvent(.failedBeforeFirstFrame) // arrives mid-recovery: same incident
        await gate.release()
        await first.value

        #expect(vm.state == .ready(Self.freshHLS))
        #expect(await resolver.calls.count == 2) // open + ONE recovery resolve

        // One slot spent, not two: three further events are still available before exhaustion.
        for _ in 0..<2 { await vm.handleRecoveryEvent(.playbackError) }
        #expect(vm.state == .ready(Self.hls)) // still recovering, not exhausted
        await vm.handleRecoveryEvent(.playbackError)
        #expect(vm.state == .recoveryExhausted(Self.hls))
    }

    @Test @MainActor func cooldownDuringRecoveryLandsOnTheCooldownStateNotAGenericError() async {
        // CF-B1: `ExtractionError.cooldown` must surface as `.cooldown` on every path, recovery's
        // forced re-resolve included -- that's exactly the traffic the cooldown exists to suppress.
        let until = Date().addingTimeInterval(300)
        let resolver = FakeResolver([.success(Self.hls), .failure(ExtractionError.cooldown(until: until))])
        let vm = makeViewModel(resolver)
        await vm.open()

        await vm.handleRecoveryEvent(.playbackError)

        #expect(vm.state == .cooldown(until: until))
    }

    @Test @MainActor func recoveryIsANoOpWhenNothingIsPlaying() async {
        let resolver = FakeResolver([.failure(ExtractionError.transport("boom"))])
        let vm = makeViewModel(resolver)
        await vm.open()

        await vm.handleRecoveryEvent(.stall)

        #expect(vm.state == .error(messageKey: "player_error_message"))
        #expect(await resolver.calls.count == 1) // no recovery traffic off a non-playing state
    }

    @Test @MainActor func aManualRetryHandsTheStreamAFreshBudget() async {
        let resolver = FakeResolver([.success(Self.hls), .success(Self.hls), .success(Self.hls),
                                     .success(Self.hls), .success(Self.freshHLS), .success(Self.hls)])
        let vm = makeViewModel(resolver)
        await vm.open()
        for _ in 0...RecoveryBudget.maxRetries { await vm.handleRecoveryEvent(.playbackError) }
        #expect(vm.state == .recoveryExhausted(Self.hls))

        await vm.retry()
        #expect(vm.state == .ready(Self.freshHLS))

        // Budget refilled: recovery works again after the manual escape hatch.
        await vm.handleRecoveryEvent(.playbackError)
        #expect(vm.state == .ready(Self.hls))
    }
}
