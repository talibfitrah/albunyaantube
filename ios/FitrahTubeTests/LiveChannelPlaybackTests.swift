import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Spec §9.1, §9.3: the iOS 1.0.4 live refresh and live-channel mode. No network: a scripted resolver, a scripted
/// lives lookup, and an injected sleep.
@MainActor @Suite(.perTest)
struct LiveChannelPlaybackTests {

    actor RecordingResolver: StreamResolving {
        private var outcomes: [Result<Resolved, Error>]
        private(set) var videoIds: [String] = []
        private(set) var sourceChannelIds: [String?] = []
        /// Stage 9 I16: the stream on the watched screen as each resolve went out (nil: nothing playable).
        private(set) var onScreenAtCall: [String?] = []
        private var screen: PlayerViewModel?

        init(_ outcomes: [Result<Resolved, Error>]) { self.outcomes = outcomes }

        func watch(_ vm: PlayerViewModel) { screen = vm }

        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            videoIds.append(videoId)
            sourceChannelIds.append(sourceChannelId)
            if let screen { onScreenAtCall.append(await MainActor.run { LiveChannelPlaybackTests.file(screen) }) }
            guard !outcomes.isEmpty else { throw ExtractionError.unavailable(videoId: videoId) }
            return try outcomes.removeFirst().get()
        }

        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    /// Records each sleep; the first `immediate` return at once, later ones park until cancelled.
    final class Sleeper: @unchecked Sendable {
        private let lock = NSLock()
        private var calls: [Duration] = []
        private let immediate: Int
        init(immediate: Int) { self.immediate = immediate }
        var recorded: [Duration] { lock.withLock { calls } }
        func sleep(_ duration: Duration) async throws {
            let count = lock.withLock { calls.append(duration); return calls.count }
            if count > immediate { try await Task.sleep(for: .seconds(3600)) }
        }
    }

    /// A sleep of a `gated` length parks until `wake()`, cancelled or not, so only a wait's own checks can stop it. Any
    /// other sleep (the live refresh timer) parks until cancelled.
    final class GatedSleeper: @unchecked Sendable {
        private let lock = NSLock()
        private let gated: Set<Duration>
        private var continuations: [CheckedContinuation<Void, Never>] = []
        private var gatedCalls: [Duration] = []
        init(gated: Duration...) { self.gated = Set(gated) }
        var parked: Int { lock.withLock { continuations.count } }
        /// Every gated sleep, in order: the wake schedule (stage 9 I18).
        var waits: [Duration] { lock.withLock { gatedCalls } }
        func sleep(_ duration: Duration) async throws {
            guard gated.contains(duration) else { return try await Task.sleep(for: .seconds(3600)) }
            await withCheckedContinuation { continuation in
                lock.withLock { gatedCalls.append(duration); continuations.append(continuation) }
            }
        }
        func wake() {
            let woken = lock.withLock { defer { continuations = [] }; return continuations }
            woken.forEach { $0.resume() }
        }
    }

    /// Stage 9 I18 follow-up: step 4's waits pass at once and move this clock (the incident's), so ten minutes of waits run
    /// in milliseconds. Any other sleep (the lookup's 10 s cap, the live refresh timer) parks until cancelled.
    nonisolated final class WaitClock: @unchecked Sendable {
        private let lock = NSLock()
        private var slept: [Duration] = []
        var waits: [Duration] { lock.withLock { slept } }
        var now: Date { Date(timeIntervalSince1970: TimeInterval(waits.reduce(0) { $0 + $1.components.seconds })) }
        func sleep(_ duration: Duration) async throws {
            guard LiveChannelRecovery.backoff.contains(where: { .seconds($0) == duration }) else {
                return try await Task.sleep(for: .seconds(3600))
            }
            lock.withLock { slept.append(duration) }
        }
    }

    /// Scripted lives answers in call order; the last one repeats.
    actor Lookup {
        private var answers: [Result<LiveChannelLives, Error>]
        private(set) var calls = 0
        init(_ answers: [Result<LiveChannelLives, Error>]) { self.answers = answers }
        func lives(_ channelId: String) throws -> LiveChannelLives {
            calls += 1
            return try (answers.count > 1 ? answers.removeFirst() : answers[0]).get()
        }
    }

    static func live(_ id: String, expiresIn: TimeInterval = 21_540, now: Bool = true, title: String? = nil) -> Resolved {
        Resolved(stream: .hls(url: URL(string: "https://manifest.test/\(id).m3u8")!, isLive: true, audioOnlyURL: nil, captionTracks: []),
                 client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(expiresIn), isLiveNow: now,
                 title: title)
    }

    func makeViewModel(_ resolver: RecordingResolver, args: PlayerArgs, sleeper: Sleeper,
                       lives: [String: LiveChannelLives] = [:]) -> PlayerViewModel {
        makeViewModel(resolver, args: args, sleep: { try await sleeper.sleep($0) }, lookup: { id in
            guard let found = lives[id] else { throw URLError(.notConnectedToInternet) }
            return found
        })
    }

    func makeViewModel(_ resolver: RecordingResolver, args: PlayerArgs, sleep: @escaping @Sendable (Duration) async throws -> Void,
                       lookup: @escaping @Sendable (String) async throws -> LiveChannelLives,
                       clock: @escaping @Sendable () -> Date = { Date() }) -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args, liveLookup: lookup, sleep: sleep, clock: clock)
    }

    func waitUntil(_ condition: () async -> Bool) async throws {
        for _ in 0..<200 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("condition never held")
    }

    static func file(_ vm: PlayerViewModel) -> String? {
        vm.state.resolved.map { PlayerHostView.streamURL($0.stream).lastPathComponent }
    }

    // MARK: - §9.3(a) the refresh (R13)

    @Test func theRefreshIsTenMinutesBeforeExpiry() {
        let now = Date()
        #expect(PlayerViewModel.liveRefreshDelay(expiresAt: now.addingTimeInterval(21_540), now: now) == 20_940)
        #expect(PlayerViewModel.liveRefreshDelay(expiresAt: now.addingTimeInterval(300), now: now) == 0)
    }

    @Test func aLiveStreamIsReResolvedBeforeItsURLsExpire() async throws {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 601)), .success(Self.live("a1fresh"))])
        let sleeper = Sleeper(immediate: 1)
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: sleeper)

        await vm.open()
        try await waitUntil { await resolver.videoIds.count == 2 }
        try await waitUntil { Self.file(vm) == "a1fresh.m3u8" }

        #expect((sleeper.recorded.first?.components.seconds ?? 99) <= 1)       // 601 s to expiry → ~1 s
        // The landed refresh re-arms from the FRESH expiry (21 540 s − 600 s), and its re-arm ends the retry loop.
        try await waitUntil { sleeper.recorded.count == 2 }
        #expect(abs(Int(sleeper.recorded[1].components.seconds) - 20_940) <= 5)
    }

    /// Fix round 1: one blip at T − 10 min must not bring back the 6 h cut (§9.3(a): "no error state"). A refresh that
    /// does not land is retried every 2 min inside the lead (Android parity).
    @Test func aRefreshThatDoesNotLandIsRetriedEveryTwoMinutes() async throws {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 601)), .failure(URLError(.timedOut)),
                                          .success(Self.live("a1fresh"))])
        let sleeper = Sleeper(immediate: 2)                                    // the lead, one retry; the re-arm parks
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: sleeper)

        await vm.open()
        try await waitUntil { await resolver.videoIds.count == 3 }
        try await waitUntil { Self.file(vm) == "a1fresh.m3u8" }

        #expect(sleeper.recorded.contains(.seconds(120)))
    }

    /// Fix round 1 (Minor 1): a proactive refresh (the live timer or the foreground check) arriving while an advance
    /// resolves would force-resolve on top of it and swallow the advance.
    @Test(.timeLimit(.minutes(1))) func aProactiveRefreshDuringAnAdvanceLeavesTheAdvanceAlone() async {
        let resolver = FitrahTubeTests.RecordingResolver(.hls)
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        settings.safeMode = false                                              // Safe Mode disables auto-advance
        let vm = PlayerViewModel(resolver: resolver, settings: settings, args: PlayerArgs(videoId: "a", playlistId: "PL"),
                                 queueSource: FakeQueueSource(pages: [(ids: ["a", "b"], next: nil)]))
        await vm.open()
        resolver.hold("b")
        let advancing = Task { await vm.playToEnd() }
        await resolver.waitUntilCalled(count: 3)                               // open: a, b(.prefetch); then b, held
        let refreshing = Task { await vm.reResolveIfExpiring(now: .distantFuture) }
        try? await Task.sleep(for: .milliseconds(50))                          // let a wrong refresh reach the resolver
        resolver.release(id: "b")
        resolver.release(id: "b")                                              // a wrong refresh's own walk of b, if any
        await refreshing.value
        await advancing.value

        #expect(!resolver.calls.contains { $0.kind == .proactiveTTLRefresh }, "refreshed on top of the advance")
        #expect(vm.args.videoId == "b")
    }

    /// A refresh only replaces a live stream with one that is still live now (never a recording, F28).
    @Test func aRefreshThatIsNoLongerLiveKeepsThePlayingStream() async throws {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 601)), .success(Self.live("ended", now: false))])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: Sleeper(immediate: 1))

        await vm.open()
        try await waitUntil { await resolver.videoIds.count == 2 }
        try await Task.sleep(for: .milliseconds(50))

        #expect(Self.file(vm) == "a1.m3u8")
    }

    @Test func aVodStreamSchedulesNoRefresh() async throws {
        let vod = Resolved(stream: .hls(url: URL(string: "https://manifest.test/v.m3u8")!, isLive: false, audioOnlyURL: nil, captionTracks: []),
                           client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(601))
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(RecordingResolver([.success(vod)]), args: PlayerArgs(videoId: "x"), sleeper: sleeper)

        await vm.open()

        #expect(sleeper.recorded.isEmpty)
    }

    /// F28: an ended broadcast still resolves `.hls(isLive: true)`. It is a recording, so it gets no live timer, and its
    /// foreground refresh still lands (the live-now check guards only a stream that is live now).
    @Test func anEndedBroadcastIsNotTreatedAsLive() async throws {
        let resolver = RecordingResolver([.success(Self.live("rec", expiresIn: 30, now: false)),
                                          .success(Self.live("recfresh", now: false))])
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "x"), sleeper: sleeper)

        await vm.open()
        await vm.reResolveIfExpiring()

        #expect(sleeper.recorded.isEmpty)
        #expect(Self.file(vm) == "recfresh.m3u8")
    }

    /// HD (spec R43) is VOD only: neither the 360p nor the committed HD stream arms a live timer, and the live refresh
    /// leaves the HD stream alone.
    @Test func aCommittedHDStreamNeverTouchesTheLiveRefresh() async throws {
        let sd = Resolved(stream: .progressive(url: URL(string: "https://media.test/sd.mp4")!, label: "360p"),
                          client: .android, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(601))
        let hdURL = URL(string: "fitrah-hls://\(UUID().uuidString)/master.m3u8")!
        let hd = Resolved(stream: .hls(url: hdURL, isLive: false, audioOnlyURL: nil, captionTracks: [],
                                       synthetic: SyntheticHLS(video: [], audio: [])),
                          client: .tvSamsung, userAgent: "tv", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(601))
        let resolver = RecordingResolver([.success(sd)])
        let sleeper = Sleeper(immediate: 0)
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        let vm = PlayerViewModel(resolver: resolver, settings: settings, args: PlayerArgs(videoId: "x"),
                                 hd: HDUpgrade(upgrade: { _ in hd }, failed: { _ in }, refused: {}),
                                 sleep: { try await sleeper.sleep($0) })

        await vm.open()
        try await waitUntil { vm.pendingUpgrade != nil }
        #expect(vm.commitUpgrade(hdURL))
        await vm.refreshLiveStream()

        #expect(vm.state.resolved?.client == .tvSamsung)
        #expect(sleeper.recorded.isEmpty)
        #expect(await resolver.videoIds.count == 1)
    }

    /// One refresh per expiry: the timer and the foreground check meeting (an app resumed past `expiresAt − 60 s`, where
    /// the timer's sleep also ends) join one walk. Two would trip the limiter's 30 s interval, and the second's
    /// generation bump would discard the first's good answer.
    @Test func theTimerAndTheForegroundCheckShareOneRefresh() async {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 30)), .success(Self.live("fresh")),
                                          .success(Self.live("second"))])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "x"), sleeper: Sleeper(immediate: 0))

        await vm.open()
        async let foreground: Void = vm.reResolveIfExpiring()
        async let timer: Void = vm.refreshLiveStream()
        _ = await (foreground, timer)

        #expect(await resolver.videoIds.count == 2)
        #expect(Self.file(vm) == "fresh.m3u8")
    }

    /// Regression pin (R13; passes before this task too): a timer does not fire while the app is suspended and
    /// paused, so the foreground check must stay. It fails only if a later change deletes or loosens it.
    @Test func theForegroundCheckStays() {
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(Self.live("a", expiresIn: 30)), now: Date()))
    }

    // MARK: - §9.1 live-channel mode

    @Test func aSingleNewLiveIsTakenAtOnce() {
        var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Zad", startedAt: Date(timeIntervalSince1970: 0))
        #expect(r.next(.ended, lives: [.init(id: "new0000000a", title: "Other")], now: Date(timeIntervalSince1970: 0))
                == .switchTo(id: "new0000000a", title: "Other"))
    }

    /// Review Focus 4 / RV M2: siblings stay live while the ended show restarts; wait 2 min for the same title.
    @Test func siblingRotationWaitsForTheSameTitleThenTakesTheFirst() {
        let t0 = Date(timeIntervalSince1970: 0)
        let siblings: [LiveChannelLives.Live] = [.init(id: "kazwa000001", title: "Kazwa & Bilal"), .init(id: "zaky0000001", title: "A Day With Zaky")]
        var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Laith & Layla", startedAt: t0)

        #expect(r.next(.ended, lives: siblings, now: t0) == .wait(seconds: 15))
        #expect(r.next(.ended, lives: siblings + [.init(id: "new0000000a", title: "Laith & Layla")], now: t0 + 20)
                == .switchTo(id: "new0000000a", title: "Laith & Layla"))
        var late = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Laith & Layla", startedAt: t0)
        #expect(late.next(.ended, lives: siblings, now: t0 + 120) == .switchTo(id: "kazwa000001", title: "Kazwa & Bilal"))
    }

    @Test func theSameIdAfterAFailureRecoversAndAfterAnEndWaits() {
        let same: [LiveChannelLives.Live] = [.init(id: "old0000000a", title: "x")]
        var a = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: Date())
        var b = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: Date())
        #expect(a.next(.failure, lives: same, now: Date()) == .sameId)
        #expect(b.next(.ended, lives: same, now: Date()) == .wait(seconds: 15))
    }

    @Test func noAnswerBacksOffThenGivesUpAfterTenMinutes() {
        let t0 = Date(timeIntervalSince1970: 0)
        var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: nil, startedAt: t0)
        #expect(r.next(.ended, lives: nil, now: t0) == .wait(seconds: 15))
        #expect(r.next(.ended, lives: [], now: t0 + 15) == .wait(seconds: 30))
        #expect(r.next(.ended, lives: nil, now: t0 + 45) == .wait(seconds: 60))
        #expect(r.next(.ended, lives: nil, now: t0 + 105) == .wait(seconds: 120))
        #expect(r.next(.ended, lives: nil, now: t0 + 225) == .wait(seconds: 120))
        #expect(r.next(.ended, lives: nil, now: t0 + 600) == .giveUp)
    }

    @Test func anEndSwitchesToTheChannelsNewLiveAndNeverAdvances() async throws {
        let channel = "UCOll3M-P7oKs5cSrQ9ytt6g"
        let resolver = RecordingResolver([.success(Self.live("pqC-1yfFvu8")), .success(Self.live("eaOtcUNHDB8"))])
        var args = PlayerArgs(videoId: "pqC-1yfFvu8", title: "ZAD TV Channel Live", channelId: channel)
        args.liveChannelId = channel
        let vm = makeViewModel(resolver, args: args, sleeper: Sleeper(immediate: 0),
                               lives: [channel: .init(lives: [.init(id: "eaOtcUNHDB8", title: "Zad")])])

        await vm.open()
        await vm.playToEnd()

        #expect(vm.args.videoId == "eaOtcUNHDB8")
        #expect(await resolver.videoIds == ["pqC-1yfFvu8", "eaOtcUNHDB8"])
        #expect(await resolver.sourceChannelIds.allSatisfy { $0 == channel })    // §9.3(b): the gate checks the channel
    }

    /// Stage 6 I-M5: a new broadcast is a new stream, so the switch takes the same per-stream resets as a queue advance:
    /// the zoom, the orientation guess (and the AirPlay fallback and the cast claim, shared through the one helper).
    @Test func aSwitchToANewBroadcastResetsThePerStreamState() async throws {
        let channel = "UCOll3M-P7oKs5cSrQ9ytt6g"
        let resolver = RecordingResolver([.success(Self.live("pqC-1yfFvu8")), .success(Self.live("eaOtcUNHDB8"))])
        var args = PlayerArgs(videoId: "pqC-1yfFvu8", title: "ZAD TV Channel Live", channelId: channel)
        args.liveChannelId = channel
        let vm = makeViewModel(resolver, args: args, sleeper: Sleeper(immediate: 0),
                               lives: [channel: .init(lives: [.init(id: "eaOtcUNHDB8", title: "Zad")])])

        await vm.open()
        vm.videoZoomed = true
        vm.videoIsPortrait = true
        await vm.playToEnd()

        #expect(vm.args.videoId == "eaOtcUNHDB8")
        #expect(!vm.videoZoomed)
        #expect(!vm.videoIsPortrait)
    }

    /// Stage 9 I16 (Cubic r6): a switch keeps the ended broadcast on screen until the new one lands, as a queue advance
    /// does. A `.loading` hop dismantles the host, which detaches the audio session and the lock screen, so a switch
    /// with the screen locked never started the next broadcast.
    @Test func aSwitchKeepsThePlayerUntilTheNewBroadcastLands() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("eaOtcUNHDB8"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0),
                               lives: [Self.channel: Self.onAir("eaOtcUNHDB8")])

        await vm.open()
        await resolver.watch(vm)
        await vm.playToEnd()

        let onScreen = await resolver.onScreenAtCall
        #expect(onScreen == ["Rs7St51oDDc.m3u8"])                             // the switch's resolve: no loading hop
        #expect(Self.file(vm) == "eaOtcUNHDB8.m3u8")
        #expect(vm.state.isLiveNow)
    }

    /// RV I11: the September recording (sticky isLive, not live now) is never played in live-channel mode.
    @Test func aNotLiveResolveNeverPlaysTheRecording() async throws {
        let channel = "UCOll3M-P7oKs5cSrQ9ytt6g"
        var args = PlayerArgs(videoId: "pqC-1yfFvu8", channelId: channel)
        args.liveChannelId = channel
        let vm = makeViewModel(RecordingResolver([.success(Self.live("pqC-1yfFvu8", now: false))]), args: args,
                               sleeper: Sleeper(immediate: 0),
                               lives: [channel: .init(lives: [])])

        await vm.open()

        #expect(vm.state.resolved == nil)
        #expect(vm.state == .loading)                                            // step 4: the existing reconnecting state
    }

    @Test func aFailureWithTheSameIdStillLiveRunsTodaysRecovery() async throws {
        let channel = "UCROKYPep-UuODNwyipe6JMw"
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc"))])
        var args = PlayerArgs(videoId: "Rs7St51oDDc", channelId: channel)
        args.liveChannelId = channel
        let vm = makeViewModel(resolver, args: args, sleeper: Sleeper(immediate: 0),
                               lives: [channel: .init(lives: [.init(id: "Rs7St51oDDc", title: "Sunnah")])])

        await vm.open()
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)

        #expect(await resolver.videoIds == ["Rs7St51oDDc", "Rs7St51oDDc"])
        #expect(vm.args.videoId == "Rs7St51oDDc")
    }

    /// Regression pin (passes before this task too): live-channel mode never touches an ordinary video's end.
    @Test func anOrdinaryVideoKeepsTodaysEndBehaviour() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: Sleeper(immediate: 0))

        await vm.open()
        await vm.playToEnd()

        #expect(await resolver.videoIds == ["Rs7St51oDDc"])
        #expect(vm.args.videoId == "Rs7St51oDDc")
    }

    // MARK: - §9.1 hand-offs (Android Tasks 27/28 reviews, spec Revision 5)

    static let channel = "UCROKYPep-UuODNwyipe6JMw"

    static func channelArgs(_ videoId: String, title: String? = nil) -> PlayerArgs {
        var args = PlayerArgs(videoId: videoId, title: title, channelId: channel)
        args.liveChannelId = channel
        args.liveTitle = title
        return args
    }

    static func onAir(_ ids: String...) -> LiveChannelLives {
        LiveChannelLives(lives: ids.map { .init(id: $0, title: "Sunnah") })
    }

    /// Stage 9 Cubic r3 P2: the open's caller is cancelled (the screen's `.task`, on a tab switch) while the resolve is
    /// out. The not-live follow-up must still end in the error state, never an endless Loading with no Retry.
    @Test func aCancelledOpenOfANotLiveChannelStillEndsInTheErrorState() async throws {
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc", now: false))]),
                               args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                               lookup: { _ in      // slow and deaf to cancellation, so the cancelled timeout wins the race
                                   await Task.detached { try? await Task.sleep(for: .milliseconds(100)) }.value
                                   throw LiveChannelNotFound()
                               })
        let opening = Task { await vm.open() }
        opening.cancel()
        await opening.value
        try await waitUntil { vm.state != .loading }
        #expect(vm.state == .error(messageKey: "player_error_message"))
    }

    /// Stage 9 I13 (re-review of the r3 fix): that follow-up outlives the screen's `.task`. A screen that went away (a pop,
    /// or a tab switch) while its lookup was out never acts on the answer: no switch, no resolve, no cast reconcile for a
    /// screen nobody is on. The screen's return runs it, so a tab switch still never ends in an endless Loading.
    @Test func aFollowUpWhoseScreenLeftDuringItsLookupWaitsForTheReturn() async throws {
        let gate = GatedSleeper(gated: .seconds(1))
        let onAir = Self.onAir("kazwa000001")
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc", now: false)), .success(Self.live("kazwa000001"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { duration in
            // The lookup's 10 s cap is deaf to cancellation for 200 ms, so the answer always lands first.
            guard duration == .seconds(10) else { return try await Task.sleep(for: .seconds(3600)) }
            await Task.detached { try? await Task.sleep(for: .milliseconds(200)) }.value
            throw URLError(.timedOut)
        }, lookup: { _ in
            try await gate.sleep(.seconds(1))
            return onAir
        })
        vm.reconcile(.appear)                                                 // the mount
        let opening = Task { await vm.open() }
        try await waitUntil { gate.parked == 1 }
        vm.reconcile(.disappear)                                              // gone as the answer lands
        gate.wake()
        await opening.value

        #expect(await resolver.videoIds == ["Rs7St51oDDc"])
        #expect(vm.args.videoId == "Rs7St51oDDc")
        #expect(vm.state == .loading)

        vm.reconcile(.appear)                                                 // back on screen
        try await waitUntil { gate.parked == 1 }
        gate.wake()
        try await waitUntil { vm.state.isLiveNow }
        #expect(vm.args.videoId == "kazwa000001")
    }

    /// Stage 9 I13: a screen that went away before the resolve landed asks the backend nothing until it is back.
    @Test func aFollowUpWhoseScreenLeftDuringTheResolveAsksNothingUntilTheReturn() async throws {
        let lookup = Lookup([.success(Self.onAir("kazwa000001"))])
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc", now: false)), .success(Self.live("kazwa000001"))])
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                               lookup: { try await lookup.lives($0) })
        vm.reconcile(.appear)
        vm.reconcile(.disappear)
        await vm.open()

        #expect(await lookup.calls == 0)
        #expect(await resolver.videoIds == ["Rs7St51oDDc"])

        vm.reconcile(.appear)
        try await waitUntil { vm.state.isLiveNow }
        #expect(vm.args.videoId == "kazwa000001")
    }

    /// Stage 9 I13: the backend's 503 (its live states still load after a start) is no answer, and its Retry-After is
    /// the next wait, not the 15 s backoff.
    @Test func aBusyBackendsRetryAfterIsTheNextWait() async throws {
        let sleeper = GatedSleeper(gated: .seconds(60))
        let lookup = Lookup([.failure(LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 60))])
        let vm = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                               sleep: { try await sleeper.sleep($0) }, lookup: { try await lookup.lives($0) })

        await vm.open()
        await vm.playToEnd()                                                  // an end: straight to the wait
        #expect(vm.state == .loading)
        try await waitUntil { sleeper.parked == 1 }
    }

    /// Hand-off 1: a 404 is the backend saying this is not a servable live channel. Stop at once, after an end and after
    /// a failure alike: no same-id try and no ten minutes of waits.
    @Test func aNotFoundChannelStopsInsteadOfWaiting() async throws {
        for ended in [true, false] {
            let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc"))])
            let sleeper = Sleeper(immediate: 0)
            let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                                   lookup: { _ in throw LiveChannelNotFound() })

            await vm.open()
            if ended { await vm.playToEnd() } else { await vm.handleRecoveryEvent(.playbackError, httpStatus: 403) }
            try await Task.sleep(for: .milliseconds(50))                         // let a wrong wait reach its sleep

            #expect(vm.state == .error(messageKey: "player_error_message"))
            #expect(await resolver.videoIds == ["Rs7St51oDDc"])
            #expect(!sleeper.recorded.contains(.seconds(15)))
        }
    }

    /// Hand-off 1: every other lookup error (a 5xx, transport, decode) is no answer, so step 4 waits and asks again.
    @Test func anyOtherLookupErrorWaitsAndAsksAgain() async throws {
        let sleeper = GatedSleeper(gated: .seconds(15))
        let lookup = Lookup([.failure(LiveChannelUnexpectedStatus(statusCode: 503))])
        let vm = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                               sleep: { try await sleeper.sleep($0) }, lookup: { try await lookup.lives($0) })

        await vm.open()
        await vm.playToEnd()
        #expect(vm.state == .loading)
        try await waitUntil { sleeper.parked == 1 }
        sleeper.wake()

        try await waitUntil { await lookup.calls == 2 }                       // the wait asked again
        #expect(vm.state == .loading)
    }

    /// Hand-off 2 (spec Revision 5): a failure whose lookup gets no answer runs ONE same-id recovery before the waits.
    /// The live check still applies, so a recording never plays.
    @Test func aFailureWithNoBackendAnswerTriesTheSameIdOnce() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0))   // unreachable

        await vm.open()
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)

        #expect(await resolver.videoIds == ["Rs7St51oDDc", "Rs7St51oDDc"])
        #expect(vm.state.isLiveNow)

        let recording = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc", now: false))])
        let blind = makeViewModel(recording, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0))

        await blind.open()
        await blind.handleRecoveryEvent(.playbackError, httpStatus: 403)

        #expect(blind.state == .loading)
        #expect(blind.state.resolved == nil)
    }

    @Test func theBlindSameIdTryIsOncePerIncidentAndOnlyForAFailureWithNoAnswer() {
        let t0 = Date(timeIntervalSince1970: 0)
        var failure = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(failure.next(.failure, lives: nil, now: t0) == .sameId)
        #expect(failure.next(.failure, lives: nil, now: t0 + 1) == .wait(seconds: 15))
        var ended = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(ended.next(.ended, lives: nil, now: t0) == .wait(seconds: 15))
        var offline = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(offline.next(.failure, lives: [], now: t0) == .wait(seconds: 15))  // "no lives" is an answer
    }

    /// Hand-off 3 (F3: an ended 24/7 id FAILS to resolve): a failed same-id re-resolve, a switched-to id that fails, a cold
    /// open and Retry all go to step 4's waits, never the plain error state.
    @Test func aFailedResolveGoesToStepFourNotTheErrorState() async throws {
        let recovery = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                                     sleeper: Sleeper(immediate: 0), lives: [Self.channel: Self.onAir("Rs7St51oDDc")])
        await recovery.open()
        await recovery.handleRecoveryEvent(.playbackError, httpStatus: 403)  // listed: same-id re-resolve, which fails
        #expect(recovery.state == .loading)

        let switched = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                                     sleeper: Sleeper(immediate: 0), lives: [Self.channel: Self.onAir("eaOtcUNHDB8")])
        await switched.open()
        await switched.playToEnd()
        #expect(switched.args.videoId == "eaOtcUNHDB8")
        #expect(switched.state == .loading)

        let cold = makeViewModel(RecordingResolver([]), args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0),
                                 lives: [Self.channel: Self.onAir("Rs7St51oDDc")])
        await cold.open()
        #expect(cold.state == .loading)
        await cold.retry()
        #expect(cold.state == .loading)
    }

    /// Hand-off 4: a wait belongs to its incident and its stream. One that wakes after the stream came back (here a Retry
    /// that landed) does nothing: no lookup, and no loading state over a healthy stream.
    @Test func aWaitThatWakesAfterItsIncidentEndedDoesNothing() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc"))])
        let sleeper = GatedSleeper(gated: .seconds(15))
        let lookup = Lookup([.success(Self.onAir("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                               lookup: { try await lookup.lives($0) })

        await vm.open()
        await vm.playToEnd()                                                  // still listed after an end: wait 15 s
        try await waitUntil { sleeper.parked == 1 }
        await vm.retry()                                                      // the stream is back; the incident is over
        #expect(vm.state.isLiveNow)
        sleeper.wake()
        try await Task.sleep(for: .milliseconds(100))

        #expect(vm.state.isLiveNow)
        #expect(await lookup.calls == 1)
    }

    /// Hand-off 5: each wait is clamped so the D12 title wait ends at 2 min and the give-up lands at 10 min.
    @Test func eachWaitEndsByTheTitleDeadlineAndTheGiveUp() {
        let t0 = Date(timeIntervalSince1970: 0)
        let siblings: [LiveChannelLives.Live] = [.init(id: "kazwa000001", title: "Kazwa & Bilal"), .init(id: "zaky0000001", title: "A Day With Zaky")]
        var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Laith & Layla", startedAt: t0)
        #expect(r.next(.ended, lives: siblings, now: t0) == .wait(seconds: 15))
        #expect(r.next(.ended, lives: siblings, now: t0 + 15) == .wait(seconds: 30))
        #expect(r.next(.ended, lives: siblings, now: t0 + 45) == .wait(seconds: 60))
        #expect(r.next(.ended, lives: siblings, now: t0 + 105) == .wait(seconds: 15))            // not 120: 2 min is at 120
        #expect(r.next(.ended, lives: siblings, now: t0 + 120) == .switchTo(id: "kazwa000001", title: "Kazwa & Bilal"))

        var late = LiveChannelRecovery(currentId: "old0000000a", currentTitle: nil, startedAt: t0)
        #expect(late.next(.ended, lives: nil, now: t0 + 590) == .wait(seconds: 10))              // not 15: 10 min is at 600
        #expect(late.next(.ended, lives: nil, now: t0 + 600) == .giveUp)
    }

    /// Hand-off 6: a wait re-sends its own trigger. A failure that waited (the backend briefly listed nothing) still gets
    /// its same-id recovery once the id is listed again; re-sent as an end, it would wait out the ten minutes.
    @Test func aWaitRepeatsTheFailureThatStartedIt() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc"))])
        let sleeper = GatedSleeper(gated: .seconds(15))
        let lookup = Lookup([.success(Self.onAir()), .success(Self.onAir("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                               lookup: { try await lookup.lives($0) })

        await vm.open()
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)       // nothing listed: wait 15 s
        #expect(vm.state == .loading)
        try await waitUntil { sleeper.parked == 1 }
        sleeper.wake()

        try await waitUntil { vm.state.isLiveNow }
        #expect(await resolver.videoIds == ["Rs7St51oDDc", "Rs7St51oDDc"])
    }

    /// Stage 7 I-P1 (Android e632edbb; replaces hand-off 7's "keep the backend title"): a live item shows the stream's
    /// original title like every other video (owner issue 1), and D12 matches the backend's own title (`liveTitle`),
    /// which no retitle touches. A retitle that returns a different title still finds the restart, not the sibling.
    @Test func aRetitledLiveItemStillMatchesTheBackendTitle() async throws {
        let resolver = RecordingResolver([.success(Self.live("old0000000a", title: "Laith and Layla | One4Kids LIVE 24/7")),
                                          .success(Self.live("new0000000a"))])
        let lives = LiveChannelLives(
            lives: [.init(id: "kazwa000001", title: "Kazwa & Bilal"), .init(id: "new0000000a", title: "Laith & Layla")])
        let vm = makeViewModel(resolver, args: Self.channelArgs("old0000000a", title: "Laith & Layla"), sleeper: Sleeper(immediate: 0),
                               lives: [Self.channel: lives])

        await vm.open()
        #expect(vm.args.title == "Laith and Layla | One4Kids LIVE 24/7")     // displayed: the original title
        await vm.playToEnd()

        #expect(vm.args.videoId == "new0000000a")                            // matched on the backend title
        #expect(vm.args.liveTitle == "Laith & Layla")                        // the switch carries the list's title on
    }

    /// Stage 9 I-C10.3 (Cubic r2, Android `title.ifBlank { previous }`): the backend sends "" when it has no title for a
    /// live. The switch keeps showing the previous title until the stream's own lands; recovery keeps the backend's.
    @Test func aSwitchToABlankTitledLiveKeepsThePreviousTitle() async throws {
        let resolver = RecordingResolver([.success(Self.live("old0000000a")), .success(Self.live("new0000000a"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("old0000000a", title: "Zad TV"), sleeper: Sleeper(immediate: 0),
                               lives: [Self.channel: LiveChannelLives(lives: [.init(id: "new0000000a", title: "")])])

        await vm.open()
        await vm.playToEnd()

        #expect(vm.args.videoId == "new0000000a")
        #expect(vm.args.title == "Zad TV")
        #expect(vm.args.liveTitle == "")
    }

    /// Stage 9 I-C10.4 (Cubic r2, Android stage 4 S2): the live id goes into the thumbnail URL and the resolve, so a
    /// switch target that is not an 11-character video id is no answer.
    @Test func aMalformedSwitchTargetIsNoAnswer() {
        let t0 = Date(timeIntervalSince1970: 0)
        var ended = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Zad", startedAt: t0)
        #expect(ended.next(.ended, lives: [.init(id: "../watch?v=", title: "Zad")], now: t0) == .wait(seconds: 15))
        var failed = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Zad", startedAt: t0)
        #expect(failed.next(.failure, lives: [.init(id: "short", title: "Other")], now: t0) == .sameId)   // the blind try
        var fine = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "Zad", startedAt: t0)
        #expect(fine.next(.ended, lives: [.init(id: "new_000-00a", title: "Zad")], now: t0) == .switchTo(id: "new_000-00a", title: "Zad"))
    }

    /// I-P1: a blank backend title is no title. It matches no other live, even one whose title is blank too.
    @Test func aBlankBackendTitleMatchesNoOtherLive() {
        let t0 = Date(timeIntervalSince1970: 0)
        for blank in ["", "  "] {
            var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: blank, startedAt: t0)
            #expect(r.next(.ended, lives: [.init(id: "kazwa000001", title: blank), .init(id: "zaky0000001", title: "Zaky")], now: t0)
                    == .wait(seconds: 15))
        }
    }

    /// Hand-off 9: live-channel mode never advances a queue, whatever Safe Mode says.
    @Test func aLiveChannelItemNeverAdvancesItsQueue() async throws {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        settings.safeMode = false
        var args = Self.channelArgs("Rs7St51oDDc")
        args.playlistId = "PL"
        let onAir = Self.onAir("Rs7St51oDDc")
        let sleeper = Sleeper(immediate: 0)
        let vm = PlayerViewModel(resolver: RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), settings: settings, args: args,
                                 queueSource: FakeQueueSource(pages: [(ids: ["Rs7St51oDDc", "b"], next: nil)]),
                                 liveLookup: { _ in onAir }, sleep: { try await sleeper.sleep($0) })

        await vm.open()
        await vm.playToEnd()

        #expect(vm.advanceCalls == 0)
        #expect(vm.args.videoId == "Rs7St51oDDc")
    }

    /// Stage 9 I13 (Cubic r4 P3): live-channel mode is one predicate, `isLiveChannelMode`, and it needs the lookup too.
    /// A live item's args with no lookup (an offline player's shape) play as an ordinary video, so the queue advances.
    @Test func anItemWithNoLiveLookupAdvancesItsQueueLikeAnyVideo() async throws {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        settings.safeMode = false
        var args = Self.channelArgs("Rs7St51oDDc")
        args.playlistId = "PL"
        let vm = PlayerViewModel(resolver: RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("b"))]),
                                 settings: settings, args: args,
                                 queueSource: FakeQueueSource(pages: [(ids: ["Rs7St51oDDc", "b"], next: nil)]))

        await vm.open()
        await vm.playToEnd()

        #expect(vm.advanceCalls == 1)
        #expect(vm.args.videoId == "b")
    }

    // MARK: - §9.1 fix round 1

    /// Important 1: a failed open, Retry or switched-to id is a FAILURE, so a listed id gets its same-id re-resolve
    /// before step 4 (a blip is not ten minutes of loading). That re-resolve's own failure waits for the next wake (stage 9
    /// I18: one try per wake), so nothing loops, and a recording is an END from the start.
    @Test func aFailedOpenRetryOrSwitchGetsTheSameIdTryFirst() async throws {
        let blip = URLError(.timedOut)
        let listed = [Self.channel: Self.onAir("Rs7St51oDDc")]

        let cold = RecordingResolver([.failure(blip), .success(Self.live("Rs7St51oDDc"))])
        let opened = makeViewModel(cold, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0), lives: listed)
        await opened.open()
        #expect(opened.state.isLiveNow)
        #expect(await cold.videoIds == ["Rs7St51oDDc", "Rs7St51oDDc"])

        let flaky = RecordingResolver([.failure(blip), .failure(blip), .failure(blip), .success(Self.live("Rs7St51oDDc"))])
        let retried = makeViewModel(flaky, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0), lives: listed)
        await retried.open()
        #expect(retried.state == .loading)
        #expect(await flaky.videoIds.count == 2)                              // the same-id try's miss waits: no loop
        await retried.retry()
        #expect(retried.state.isLiveNow)
        #expect(await flaky.videoIds.count == 4)

        let switching = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .failure(blip), .success(Self.live("eaOtcUNHDB8"))])
        let switched = makeViewModel(switching, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0),
                                     lives: [Self.channel: Self.onAir("eaOtcUNHDB8")])
        await switched.open()
        await switched.playToEnd()
        #expect(switched.state.isLiveNow)
        #expect(await switching.videoIds == ["Rs7St51oDDc", "eaOtcUNHDB8", "eaOtcUNHDB8"])

        let recorded = RecordingResolver([.success(Self.live("Rs7St51oDDc", now: false)), .success(Self.live("Rs7St51oDDc"))])
        let recording = makeViewModel(recorded, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0), lives: listed)
        await recording.open()
        #expect(recording.state == .loading)
        #expect(await recorded.videoIds == ["Rs7St51oDDc"])
    }

    /// Important 2: a 24/7 id plays for days and every network change is an incident. In live-channel mode the lifetime
    /// retries refund after five minutes of sustained playback (Android counts per episode); an ordinary video keeps its
    /// lifetime cap.
    @Test func aLongLivedLiveRecoversEveryIncidentSpacedByFiveMinutesOfPlay() async throws {
        let incidents = RecoveryBudget.maxRetries + 1
        for liveChannel in [true, false] {
            let resolver = RecordingResolver(Array(repeating: .success(Self.live("Rs7St51oDDc")), count: 1 + incidents))
            let args = liveChannel ? Self.channelArgs("Rs7St51oDDc") : PlayerArgs(videoId: "Rs7St51oDDc")
            let vm = makeViewModel(resolver, args: args, sleeper: Sleeper(immediate: 0), lives: [Self.channel: Self.onAir("Rs7St51oDDc")])
            await vm.open()
            var now = Date(timeIntervalSince1970: 0)
            for _ in 0..<incidents {
                for _ in 0...300 { vm.recordPlaybackProgress(now: now); now += 1 }        // five minutes of play
                await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)
            }

            #expect(vm.state.isLiveNow == liveChannel)
            #expect(await resolver.videoIds.count == (liveChannel ? 1 + incidents : incidents))
        }
    }

    /// Re-review minor: an offline player built from a live item's args has no lookup, so it is not in live-channel mode
    /// and keeps the lifetime cap.
    @Test func anOfflinePlayerWithALiveItemsArgsGetsNoRefund() async throws {
        let incidents = RecoveryBudget.maxRetries + 1
        let resolver = RecordingResolver(Array(repeating: .success(Self.live("Rs7St51oDDc")), count: 1 + incidents))
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        let sleeper = Sleeper(immediate: 0)
        let vm = PlayerViewModel(resolver: resolver, settings: settings, args: Self.channelArgs("Rs7St51oDDc"),
                                 sleep: { try await sleeper.sleep($0) })
        await vm.open()
        var now = Date(timeIntervalSince1970: 0)
        for _ in 0..<incidents {
            for _ in 0...300 { vm.recordPlaybackProgress(now: now); now += 1 }
            await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)
        }

        #expect(!vm.state.isLiveNow)                                          // the Retry card
        #expect(await resolver.videoIds.count == incidents)                   // the 4th incident spent no resolve
    }

    /// Important 3: in live-channel mode a spent budget goes to step 4's waits, never the Retry card.
    @Test func aSpentBudgetGoesToStepFour() async throws {
        let resolver = RecordingResolver(Array(repeating: .success(Self.live("Rs7St51oDDc")), count: 1 + RecoveryBudget.maxRetries))
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0),
                               lives: [Self.channel: Self.onAir("Rs7St51oDDc")])

        await vm.open()
        for _ in 0..<RecoveryBudget.maxRetries { await vm.handleRecoveryEvent(.playbackError, httpStatus: 403) }
        #expect(vm.state.isLiveNow)
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)

        #expect(vm.state == .loading)
        #expect(await resolver.videoIds.count == 1 + RecoveryBudget.maxRetries)
    }

    /// Important 3: a proactive refresh that lands a recording goes to step 4, but a refresh that fails keeps the playing
    /// stream: a refresh blip is not an incident.
    @Test func aRefreshThatLandsARecordingEndsButAFailedRefreshKeepsTheStream() async throws {
        let sleeper = Sleeper(immediate: 0)
        let recorded = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .success(Self.live("Rs7St51oDDc", now: false))])
        let endedLookup = Lookup([.success(Self.onAir("Rs7St51oDDc"))])
        let ended = makeViewModel(recorded, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                                  lookup: { try await endedLookup.lives($0) })
        await ended.open()
        await ended.refreshLiveStream()
        #expect(ended.state == .loading)
        #expect(await endedLookup.calls == 1)

        let failing = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .failure(URLError(.timedOut))])
        let blipLookup = Lookup([.success(Self.onAir("Rs7St51oDDc"))])
        let blip = makeViewModel(failing, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                                 lookup: { try await blipLookup.lives($0) })
        await blip.open()
        await blip.refreshLiveStream()
        #expect(await failing.videoIds.count == 2)
        #expect(Self.file(blip) == "Rs7St51oDDc.m3u8")
        #expect(await blipLookup.calls == 0)
    }

    /// Minor: a cancelled lookup is not an answer. It spends neither the blind same-id try nor a backoff step.
    @Test func aCancelledLookupSpendsNothing() async throws {
        let lookup = Lookup([.failure(URLError(.notConnectedToInternet))])
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                               sleep: { try await sleeper.sleep($0) }, lookup: { id in
            if await lookup.calls == 0 { _ = try? await lookup.lives(id); try await Task.sleep(for: .seconds(3600)) }  // parks
            return try await lookup.lives(id)
        })

        await vm.open()
        let cancelled = Task { await vm.liveChannelTrigger(.failure) }
        try await waitUntil { await lookup.calls == 1 }
        cancelled.cancel()

        #expect(await cancelled.value)                                        // handled, nothing done
        #expect(vm.state.isLiveNow)
        #expect(await vm.liveChannelTrigger(.failure) == false)               // the blind try is still there
    }

    /// Minor: a lookup that never answers times out after 10 s as no answer, so step 4 waits.
    @Test func aLookupThatNeverAnswersTimesOut() async throws {
        let vm = makeViewModel(RecordingResolver([.success(Self.live("Rs7St51oDDc"))]), args: Self.channelArgs("Rs7St51oDDc"),
                               sleep: { if $0 != .seconds(10) { try await Task.sleep(for: .seconds(3600)) } },
                               lookup: { _ in try await Task.sleep(for: .seconds(3600)); throw URLError(.timedOut) })

        await vm.open()
        let ending = Task { await vm.playToEnd() }
        try await waitUntil { vm.state == .loading }
        ending.cancel()
    }

    // MARK: - §9.1 Revision 8 (stage 9 I18, Cubic r8 #2 and #4)

    /// Cubic r8 #2: in an incident that began with a failure, each wake whose answer still lists the id re-resolves it
    /// once, never more often: a failed try (or no answer) waits for the next wake. A try that comes back not live makes
    /// the incident an end one, which never re-resolves.
    @Test func aFailureIncidentReResolvesAListedIdOncePerWakeUntilItIsNotLive() {
        let t0 = Date(timeIntervalSince1970: 0)
        let listed: [LiveChannelLives.Live] = [.init(id: "old0000000a", title: "x")]
        var r = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(r.next(.failure, lives: listed, now: t0) == .sameId)                      // step 3
        #expect(r.next(.failure, lives: listed, now: t0 + 1) == .wait(seconds: 15))       // its failure waits
        #expect(r.next(.failure, lives: listed, now: t0 + 16) == .sameId)                 // the wake: one try
        #expect(r.next(.failure, lives: nil, now: t0 + 17) == .wait(seconds: 30))         // not the blind one either
        #expect(r.next(.failure, lives: listed, now: t0 + 47) == .sameId)
        #expect(r.next(.ended, lives: listed, now: t0 + 48) == .wait(seconds: 60))        // not live: an end from here
        #expect(r.next(.failure, lives: listed, now: t0 + 108) == .wait(seconds: 120))
    }

    /// Cubic r8 #2: a transient failure of the same-id try is not an end. While the backend still lists the stream, each
    /// wake re-resolves it once, so a short outage costs a wait or two, not ten minutes of loading with no Retry.
    @Test func eachWakeReResolvesTheListedStreamOnceAfterAFailedTry() async throws {
        let blip = URLError(.notConnectedToInternet)
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .failure(blip), .failure(blip),
                                          .success(Self.live("Rs7St51oDDc"))])
        let sleeper = GatedSleeper(gated: .seconds(15), .seconds(30))
        let onAir = Self.onAir("Rs7St51oDDc")
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await sleeper.sleep($0) },
                               lookup: { _ in onAir })

        await vm.open()
        await resolver.watch(vm)
        await vm.handleRecoveryEvent(.stall)                                  // step 3's same-id try fails: wait 15 s
        try await waitUntil { sleeper.parked == 1 }
        let beforeWake = await resolver.videoIds.count
        #expect(beforeWake == 2)                                              // no second try before the wake
        let onScreen = await resolver.onScreenAtCall
        #expect(onScreen == ["Rs7St51oDDc.m3u8"])                             // the stalled player stayed for it

        sleeper.wake()                                                        // listed: one try, which fails; wait 30 s
        try await waitUntil { sleeper.waits.count == 2 && sleeper.parked == 1 }
        let afterFirstWake = await resolver.videoIds.count
        #expect(afterFirstWake == 3)

        sleeper.wake()                                                        // listed: one try, which lands
        try await waitUntil { vm.state.isLiveNow }
        let afterSecondWake = await resolver.videoIds.count
        #expect(afterSecondWake == 4)
        #expect(sleeper.waits == [.seconds(15), .seconds(30)])
    }

    /// I18 follow-up (Android A15 parity: its same-id branch needs `elapsed < GIVE_UP_MS`): no same-id try at or after the
    /// 10-min give-up, listed or blind. The wake that lands at 10:00 gives up instead of starting one more resolve.
    @Test func theTenMinuteWakeGivesUpWithoutASameIdTry() {
        let t0 = Date(timeIntervalSince1970: 0)
        var listed = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(listed.next(.failure, lives: [], now: t0 + 590) == .wait(seconds: 10))          // the last wait
        #expect(listed.next(.failure, lives: [.init(id: "old0000000a", title: "x")], now: t0 + 600) == .giveUp)
        var blind = LiveChannelRecovery(currentId: "old0000000a", currentTitle: "x", startedAt: t0)
        #expect(blind.next(.failure, lives: [], now: t0 + 590) == .wait(seconds: 10))
        #expect(blind.next(.failure, lives: nil, now: t0 + 600) == .giveUp)
    }

    /// I18 follow-up (controller ruling, Android A15 `setRecoveryExhaustedState`): a spent step-3 budget is an END. A stream
    /// that resolves live but whose player fails every time would otherwise open a new incident at each landing (one
    /// resolve per 15 s, forever) and never reach the 10-min give-up.
    @Test func aFlappingStreamWithASpentBudgetGivesUpAtTenMinutes() async throws {
        let resolver = RecordingResolver(Array(repeating: .success(Self.live("Rs7St51oDDc")), count: 40))
        let clock = WaitClock()
        let onAir = Self.onAir("Rs7St51oDDc")
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleep: { try await clock.sleep($0) },
                               lookup: { _ in onAir }, clock: { clock.now })

        await vm.open()
        for _ in 0..<RecoveryBudget.maxRetries { await vm.handleRecoveryEvent(.playbackError, httpStatus: 403) }
        let spent = await resolver.videoIds.count                            // each recovered; the budget is spent
        for _ in 0..<10 {                                                     // the player fails whenever it lands
            await vm.handleRecoveryEvent(.playbackError, httpStatus: 403)
            try await waitUntil { vm.state.isLiveNow || vm.state == .error(messageKey: "player_error_message") }
            if !vm.state.isLiveNow { break }
        }

        #expect(vm.state == .error(messageKey: "player_error_message"))
        let calls = await resolver.videoIds.count
        #expect(calls == spent)                                               // no same-id resolve after the budget
        #expect(clock.now == Date(timeIntervalSince1970: 600))                // the give-up, at 10 min of incident time
    }

    /// Cubic r8 #4: `liveStep`'s same-id try keeps the player on screen, as the switch does (I16). Here the switch to the
    /// new broadcast fails, and its same-id try goes out with the ended broadcast still on screen: no `.loading` hop,
    /// which would dismantle the host and its background audio session before the try lands.
    @Test func aSameIdTryKeepsThePlayerOnScreen() async throws {
        let resolver = RecordingResolver([.success(Self.live("Rs7St51oDDc")), .failure(URLError(.timedOut)),
                                          .success(Self.live("eaOtcUNHDB8"))])
        let vm = makeViewModel(resolver, args: Self.channelArgs("Rs7St51oDDc"), sleeper: Sleeper(immediate: 0),
                               lives: [Self.channel: Self.onAir("eaOtcUNHDB8")])

        await vm.open()
        await resolver.watch(vm)
        await vm.playToEnd()

        let onScreen = await resolver.onScreenAtCall
        #expect(onScreen == ["Rs7St51oDDc.m3u8", "Rs7St51oDDc.m3u8"])        // the switch, then its try
        #expect(Self.file(vm) == "eaOtcUNHDB8.m3u8")
    }
}
