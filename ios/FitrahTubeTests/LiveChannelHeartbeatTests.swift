import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Owner 2026-11-02: "The admin can always remove a channel or a specific live stream, and this must immediately reflect
/// on the client side apps." A phone already watching a live-channel item asks the backend every 60 s whether its video
/// is still allowed (`GET /live/channels/{id}?live=<videoId>`, catalog only). No network: a scripted resolver, a scripted
/// check, and a sleep whose 60 s ticks the test releases one at a time.
@MainActor @Suite(.perTest)
struct LiveChannelHeartbeatTests {
    typealias Playback = LiveChannelPlaybackTests

    /// A 60 s sleep (the heartbeat's) parks until `tick()` releases it; any other sleep parks until cancelled.
    nonisolated final class Ticker: @unchecked Sendable {
        private let lock = NSLock()
        private var tickets = 0
        private var _begun = 0
        private var _parked = 0
        private var _cancelled = 0
        /// Heartbeat sleeps started, ever.
        var begun: Int { lock.withLock { _begun } }
        var parked: Int { lock.withLock { _parked } }
        /// Heartbeat sleeps ended by cancellation.
        var cancelled: Int { lock.withLock { _cancelled } }
        func tick() { lock.withLock { tickets += 1 } }
        func sleep(_ duration: Duration) async throws {
            guard duration == .seconds(60) else { return try await Task.sleep(for: .seconds(3600)) }
            lock.withLock { _begun += 1; _parked += 1 }
            defer { lock.withLock { _parked -= 1 } }
            do {
                while !lock.withLock({ () -> Bool in
                    guard tickets > 0 else { return false }
                    tickets -= 1
                    return true
                }) { try await Task.sleep(for: .milliseconds(2)) }
            } catch {
                lock.withLock { _cancelled += 1 }
                throw error
            }
        }
    }

    /// Scripted check answers in call order (the last one repeats), and every (channel, video) it was asked with.
    actor Checks {
        private var answers: [Result<LiveChannelLives, Error>]
        private(set) var calls: [[String]] = []
        init(_ answers: [Result<LiveChannelLives, Error>]) { self.answers = answers }
        func check(_ channelId: String, _ videoId: String) throws -> LiveChannelLives {
            calls.append([channelId, videoId])
            return try (answers.count > 1 ? answers.removeFirst() : answers[0]).get()
        }
        /// The link check: a success is the 204, a failure what it throws.
        func link(_ videoId: String) throws {
            calls.append([videoId])
            _ = try (answers.count > 1 ? answers.removeFirst() : answers[0]).get()
        }
    }

    actor LookupCount {
        private(set) var calls = 0
        func hit() { calls += 1 }
    }

    let channel = Playback.channel
    let ticker = Ticker()
    let lookups = LookupCount()

    func makeViewModel(_ resolver: Playback.RecordingResolver, args: PlayerArgs, checks: Checks,
                       lookup: (@Sendable (String) async throws -> LiveChannelLives)? = nil,
                       queueSource: (any PlaylistQueueSource)? = nil, cast: CastController? = nil) -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelHeartbeatTests.\(UUID().uuidString)")!)
        let lookups = lookups
        return PlayerViewModel(resolver: resolver, settings: settings, args: args, queueSource: queueSource, cast: cast,
                               liveLookup: { id, _ in
                                   guard let lookup else { await lookups.hit(); throw URLError(.notConnectedToInternet) }
                                   return try await lookup(id)
                               },
                               liveCheck: { try await checks.check($0, $1) },
                               linkCheck: { try await checks.link($0) },
                               sleep: { [ticker] in try await ticker.sleep($0) })
    }

    func waitUntil(_ condition: () async -> Bool, sourceLocation: SourceLocation = #_sourceLocation) async throws {
        for _ in 0..<500 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("condition never held", sourceLocation: sourceLocation)
    }

    /// Releases one heartbeat tick and waits until the loop is asleep again (its next tick armed), so the tick is done.
    func tickAndSettle() async throws {
        let begun = ticker.begun
        ticker.tick()
        try await waitUntil { ticker.begun == begun + 1 && ticker.parked == 1 }
    }

    // MARK: - removal

    @Test func aNotFoundTickStopsPlaybackAsUnavailable() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.failure(LiveChannelNotFound())])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm.open()
        #expect(vm.state.isLiveNow)
        try await waitUntil { ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        #expect(await checks.calls == [[channel, "Rs7St51oDDc"]])
        try await Task.sleep(for: .milliseconds(50))
        #expect(ticker.begun == 1)                                              // stopped: no next tick
        #expect(await lookups.calls == 0)                                       // a removal is not an incident
        #expect(await resolver.videoIds == ["Rs7St51oDDc"])
        #expect(vm.state == .contentUnavailable)
    }

    @Test func anEmptyListTickStopsPlaybackAsUnavailable() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.success(LiveChannelLives(lives: []))])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        try await Task.sleep(for: .milliseconds(50))
        #expect(ticker.begun == 1)
        #expect(await lookups.calls == 0)
        #expect(await resolver.videoIds == ["Rs7St51oDDc"])
    }

    // MARK: - still allowed, or no answer

    @Test func aTickListingTheCurrentIdKeepsPlaying() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.success(Playback.onAir("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        try await tickAndSettle()
        try await tickAndSettle()

        #expect(await checks.calls == [[channel, "Rs7St51oDDc"], [channel, "Rs7St51oDDc"]])
        #expect(vm.state.isLiveNow)
        #expect(Playback.file(vm) == "Rs7St51oDDc.m3u8")
    }

    /// Cubic r8: a re-resolve of the same live keeps the running tick, so refreshes under 60 s apart never starve it.
    @Test func aReResolveOfTheSameLiveKeepsTheRunningTick() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc")),
                                                   .success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.success(Playback.onAir("Rs7St51oDDc"))])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        await vm.refreshLiveStream()
        #expect(await resolver.videoIds.count == 2)
        #expect(vm.state.isLiveNow)                                             // the same live landed again
        try await Task.sleep(for: .milliseconds(50))

        #expect(ticker.begun == 1)                                              // the running tick was kept
        #expect(ticker.cancelled == 0)
        try await tickAndSettle()
        #expect(await checks.calls == [[channel, "Rs7St51oDDc"]])
    }

    @Test func aBusyOrUnreachableServerNeverCutsAnAllowedStream() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.failure(LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 5)),
                             .failure(LiveChannelUnexpectedStatus(statusCode: 500)),
                             .failure(LiveChannelUnexpectedStatus(statusCode: 400)),
                             .failure(URLError(.notConnectedToInternet)),
                             .failure(URLError(.timedOut))])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        for _ in 0..<5 { try await tickAndSettle() }

        #expect(await checks.calls.count == 5)
        #expect(vm.state.isLiveNow)
        #expect(await lookups.calls == 0)
        #expect(await resolver.videoIds == ["Rs7St51oDDc"])
    }

    // MARK: - when it asks

    /// A live-channel incident owns the stream: its lookup is out (the player still on screen), and a tick asks nothing.
    @Test func noTickWhileAnIncidentIsInProgress() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.failure(LiveChannelNotFound())])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks,
                               lookup: { _ in try await Task.sleep(for: .seconds(3600)); throw CancellationError() })

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        let incident = Task { await vm.liveChannelTrigger(.failure) }          // the lookup parks: incident open
        try await Task.sleep(for: .milliseconds(50))
        try await tickAndSettle()

        #expect(await checks.calls.isEmpty)
        #expect(vm.state.isLiveNow)
        incident.cancel()
    }

    /// Codex r5: a phone recovery in progress does not pause the check of the live the TV plays.
    @Test func aPhoneRecoveryKeepsCheckingTheLiveTheTVPlays() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.failure(LiveChannelNotFound())])
        let cast = liveCast()
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks,
                               lookup: { _ in try await Task.sleep(for: .seconds(3600)); throw CancellationError() }, cast: cast)

        await vm.open()
        cast.recordLoad("Rs7St51oDDc", owner: vm.castOwner, contentURL: Self.castURL)
        try await waitUntil { ticker.parked == 1 }
        let incident = Task { await vm.liveChannelTrigger(.failure) }          // the lookup parks: incident open
        try await Task.sleep(for: .milliseconds(50))
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        #expect(await checks.calls == [[channel, "Rs7St51oDDc"]])
        #expect(cast.loadedClaim == nil)                                        // the TV was told to stop
        incident.cancel()
    }

    @Test func afterASwitchTheTickAsksWithTheNewId() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc")), .success(Playback.live("eaOtcUNHDB8"))])
        let next = Playback.onAir("eaOtcUNHDB8")
        let checks = Checks([.success(next)])
        let vm = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks, lookup: { _ in next })

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        await vm.playToEnd()                                                    // the end switches to the new broadcast
        #expect(vm.args.videoId == "eaOtcUNHDB8")
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }         // the old id's heartbeat ended, the new one's armed
        try await tickAndSettle()

        #expect(await checks.calls == [[channel, "eaOtcUNHDB8"]])
        #expect(ticker.cancelled == 1)
    }

    // MARK: - when it stops

    @Test func noTicksAfterLeavingLiveChannelMode() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc")), .success(Playback.live("vod00000001")),
                                                   .success(Playback.live("vod00000001", now: false))])
        let checks = Checks([.failure(LiveChannelNotFound())])
        var args = Playback.channelArgs("Rs7St51oDDc")
        args.playlistId = "PL"
        let vm = makeViewModel(resolver, args: args, checks: checks,
                               queueSource: FakeQueueSource(pages: [(ids: ["Rs7St51oDDc", "vod00000001"], next: nil)]))

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        await vm.play(at: 1)                                                    // an ordinary video: live-channel mode ends
        #expect(vm.args.liveChannelId == nil)
        try await waitUntil { ticker.parked == 0 }
        ticker.tick()
        try await Task.sleep(for: .milliseconds(100))

        #expect(await checks.calls.isEmpty)
        #expect(ticker.begun == 1)
        #expect(vm.state.isPlayable)
    }

    @Test func theViewModelGoingAwayCancelsTheHeartbeat() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("Rs7St51oDDc"))])
        let checks = Checks([.failure(LiveChannelNotFound())])
        var vm: PlayerViewModel? = makeViewModel(resolver, args: Playback.channelArgs("Rs7St51oDDc"), checks: checks)

        await vm?.open()
        try await waitUntil { ticker.parked == 1 }
        vm = nil
        try await waitUntil { ticker.cancelled == 1 }
        ticker.tick()
        try await Task.sleep(for: .milliseconds(100))

        #expect(await checks.calls.isEmpty)
        #expect(ticker.begun == 1)
    }
    // MARK: - exact live links (`liveLink`, GET /live/links/{videoId})

    static func linkArgs(_ videoId: String) -> PlayerArgs {
        var args = PlayerArgs(videoId: videoId)
        args.liveLink = true
        return args
    }

    @Test func aLink204TickKeepsPlaying() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let checks = Checks([.success(LiveChannelLives(lives: []))])
        let vm = makeViewModel(resolver, args: Self.linkArgs("eaOtcUNHDB8"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        try await tickAndSettle()
        try await tickAndSettle()

        #expect(await checks.calls == [["eaOtcUNHDB8"], ["eaOtcUNHDB8"]])
        #expect(vm.state.isLiveNow)
    }

    @Test func aLinkNotFoundTickStopsPlaybackAsUnavailable() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let checks = Checks([.failure(LiveLinkNotFound())])
        let vm = makeViewModel(resolver, args: Self.linkArgs("eaOtcUNHDB8"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        try await Task.sleep(for: .milliseconds(50))
        #expect(await checks.calls == [["eaOtcUNHDB8"]])
        #expect(ticker.begun == 1)                                              // stopped: no next tick
        #expect(await lookups.calls == 0)
        #expect(await resolver.videoIds == ["eaOtcUNHDB8"])
    }

    @Test func aBusyOrUnreachableServerNeverCutsALink() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let checks = Checks([.failure(LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 60)),
                             .failure(LiveChannelUnexpectedStatus(statusCode: 400)),
                             .failure(URLError(.notConnectedToInternet)),
                             .failure(URLError(.timedOut))])
        let vm = makeViewModel(resolver, args: Self.linkArgs("eaOtcUNHDB8"), checks: checks)

        await vm.open()
        try await waitUntil { ticker.parked == 1 }
        for _ in 0..<4 { try await tickAndSettle() }

        #expect(await checks.calls.count == 4)
        #expect(vm.state.isLiveNow)
    }

    @Test func noTicksForAnItemThatIsNeitherALinkNorALiveChannel() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let checks = Checks([.failure(LiveLinkNotFound())])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "eaOtcUNHDB8"), checks: checks)

        await vm.open()
        #expect(vm.state.isLiveNow)
        ticker.tick()
        try await Task.sleep(for: .milliseconds(100))

        #expect(ticker.begun == 0)
        #expect(await checks.calls.isEmpty)
        #expect(vm.state.isLiveNow)
    }

    // MARK: - casting

    /// A removal while this screen's load is on the TV stops the receiver too, not only the phone.
    @Test(arguments: [false, true])
    func aRemovalStopsThisScreensCast(link: Bool) async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let checks = Checks([.failure(link ? LiveLinkNotFound() as Error : LiveChannelNotFound())])
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        let vm = makeViewModel(resolver, args: link ? Self.linkArgs("eaOtcUNHDB8") : Playback.channelArgs("eaOtcUNHDB8"),
                               checks: checks, cast: cast)

        await vm.open()
        cast.recordLoad("eaOtcUNHDB8", owner: vm.castOwner)                     // the receiver plays this screen's load
        try await waitUntil { ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        #expect(cast.loadedClaim == nil)                                        // the receiver was told to stop
    }

    /// Codex r4: a phone resolve that failed leaves the TV playing this screen's load; the tick still checks it, and a
    /// removal stops the TV.
    @Test func aFailedPhoneResolveKeepsCheckingALinkTheTVStillPlays() async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8")), .failure(URLError(.timedOut))])
        let checks = Checks([.failure(LiveLinkNotFound())])
        let cast = liveCast()
        let vm = makeViewModel(resolver, args: Self.linkArgs("eaOtcUNHDB8"), checks: checks, cast: cast)

        await vm.open()
        cast.recordLoad("eaOtcUNHDB8", owner: vm.castOwner, contentURL: Self.castURL)
        try await waitUntil { ticker.parked == 1 }
        await vm.retry()                                                        // the phone's own resolve fails
        #expect(!vm.state.isPlayable)
        try await waitUntil { ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { vm.state == .contentUnavailable }

        #expect(cast.loadedClaim == nil)                                        // the TV was told to stop
    }

    // MARK: - a closed player's cast (Cubic round 7)

    /// A cast live plays on the TV after its player is popped. The removal check moves to the app-lifetime cast seam
    /// (`CastController.watchRemoval`) while the receiver plays this screen's load.
    func castClosedPlayer(_ args: PlayerArgs, checks: Checks, cast: CastController, load: Bool = true) async throws {
        let resolver = Playback.RecordingResolver([.success(Playback.live(args.videoId))])
        var vm: PlayerViewModel? = makeViewModel(resolver, args: args, checks: checks, cast: cast)
        await vm?.open()
        if load, let owner = vm?.castOwner { cast.recordLoad(args.videoId, owner: owner, contentURL: Self.castURL) }
        try await waitUntil { ticker.parked == 1 }
        vm = nil                                                                // the player is popped
        try await waitUntil { ticker.cancelled == 1 }
    }

    func liveCast() -> CastController {
        let cast = CastController()
        cast.sessionDidBegin(deviceName: "Living Room TV")
        return cast
    }

    @Test func aClosedPlayerWhileCastingKeepsChecking() async throws {
        let checks = Checks([.success(Playback.onAir("eaOtcUNHDB8"))])
        let cast = liveCast()
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }         // the watch's tick is armed
        try await tickAndSettle()
        try await tickAndSettle()

        #expect(await checks.calls == [[channel, "eaOtcUNHDB8"], [channel, "eaOtcUNHDB8"]])
        #expect(cast.loadedClaim != nil)                                        // allowed: the TV plays on
    }

    @Test(arguments: [false, true])
    func aRemovalStopsTheClosedPlayersCast(link: Bool) async throws {
        let checks = Checks([.failure(link ? LiveLinkNotFound() as Error : LiveChannelNotFound())])
        let cast = liveCast()
        try await castClosedPlayer(link ? Self.linkArgs("eaOtcUNHDB8") : Playback.channelArgs("eaOtcUNHDB8"),
                                   checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }
        ticker.tick()
        try await waitUntil { cast.loadedClaim == nil }                         // the receiver was told to stop

        try await Task.sleep(for: .milliseconds(50))
        #expect(ticker.begun == 2)                                              // stopped: no next tick
        #expect(await checks.calls.count == 1)
    }

    @Test func aBusyServerNeverStopsTheClosedPlayersCast() async throws {
        let checks = Checks([.failure(LiveChannelUnexpectedStatus(statusCode: 503, retryAfter: 5)),
                             .failure(URLError(.timedOut))])
        let cast = liveCast()
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }
        try await tickAndSettle()
        try await tickAndSettle()

        #expect(await checks.calls.count == 2)
        #expect(cast.loadedClaim != nil)
    }

    @Test(arguments: [false, true])
    func theWatchEndsWithTheCastOrAnotherVideo(anotherVideo: Bool) async throws {
        let checks = Checks([.failure(LiveChannelNotFound())])
        let cast = liveCast()
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }
        if anotherVideo { cast.recordLoad("Rs7St51oDDc", owner: UUID()) } else { cast.sessionDidEnd() }
        ticker.tick()
        try await waitUntil { ticker.parked == 0 }
        try await Task.sleep(for: .milliseconds(50))

        #expect(await checks.calls.isEmpty)
        #expect(ticker.begun == 2)                                              // ended: no next tick
    }

    static let castURL = URL(string: "https://cast.example/ours")!

    /// Codex r3: another sender's load replacing ours on the TV ends the watch; its removal never stops their video.
    /// Cubic r8: so does ours finishing on the TV.
    @Test(arguments: [false, true])
    func anotherSendersLoadOrOursFinishingEndsTheWatch(finished: Bool) async throws {
        let checks = Checks([.failure(LiveChannelNotFound())])
        let cast = liveCast()
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }
        cast.receiverDidUpdate(finished
            ? .init(playerState: .idle, idleReason: .finished, contentURL: Self.castURL, position: 0)
            : .init(playerState: .playing, idleReason: .none,
                    contentURL: URL(string: "https://cast.example/another-sender"), position: 0))
        ticker.tick()
        try await waitUntil { ticker.parked == 0 }
        try await Task.sleep(for: .milliseconds(50))

        #expect(await checks.calls.isEmpty)
        #expect(ticker.begun == 2)                                              // ended: no next tick
    }

    /// The stop rule itself: only the receiver still playing the URL this app loaded for that video.
    @Test func theReceiverIsOursOnlyWhilePlayingOurURL() {
        let ours = CastController.CastLoad(videoId: "eaOtcUNHDB8", url: Self.castURL)
        let theirs = URL(string: "https://cast.example/another-sender")
        #expect(CastController.receiverPlays("eaOtcUNHDB8", load: ours, receiverURL: Self.castURL))
        #expect(!CastController.receiverPlays("eaOtcUNHDB8", load: ours, receiverURL: theirs))   // another sender
        #expect(!CastController.receiverPlays("eaOtcUNHDB8", load: ours, receiverURL: nil))      // nothing playing
        #expect(!CastController.receiverPlays("Rs7St51oDDc", load: ours, receiverURL: Self.castURL)) // another video
        #expect(!CastController.receiverPlays("eaOtcUNHDB8", load: nil, receiverURL: Self.castURL))  // nothing loaded
    }

    @Test func nothingCastMeansNoChecksAfterTheClose() async throws {
        let checks = Checks([.failure(LiveChannelNotFound())])
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: liveCast(), load: false)
        ticker.tick()
        try await Task.sleep(for: .milliseconds(100))

        #expect(ticker.begun == 1)
        #expect(await checks.calls.isEmpty)
    }

    /// The reopened player takes the watch back: one question per tick, not one from each.
    @Test func aReopenedPlayerAsksOncePerTick() async throws {
        let checks = Checks([.success(Playback.onAir("eaOtcUNHDB8"))])
        let cast = liveCast()
        try await castClosedPlayer(Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        try await waitUntil { ticker.begun == 2 && ticker.parked == 1 }

        let resolver = Playback.RecordingResolver([.success(Playback.live("eaOtcUNHDB8"))])
        let reopened = makeViewModel(resolver, args: Playback.channelArgs("eaOtcUNHDB8"), checks: checks, cast: cast)
        await reopened.open()
        try await waitUntil { ticker.begun == 3 && ticker.cancelled == 2 && ticker.parked == 1 }
        try await tickAndSettle()
        try await tickAndSettle()

        #expect(await checks.calls.count == 2)
        #expect(reopened.state.isLiveNow)
    }
}
