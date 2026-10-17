import Foundation
import Testing
@testable import InnerTubeKit

/// Spec §4.2 (revision 3): the `tv_samsung` HD upgrade. nil always means "stay on 360p"; it never throws.
@Suite struct StreamResolverUpgradeTests {
    final class FakeSolver: ChallengeSolving, @unchecked Sendable {
        private let lock = NSLock()
        private var infoCalls = 0
        private var solveCalls = 0
        var infoDelay: Duration = .zero
        var solveError: ChallengeSolverError?
        var dropAnswers = false

        var playerInfoCalls: Int { lock.withLock { infoCalls } }
        var solveCount: Int { lock.withLock { solveCalls } }

        func playerInfo() async throws -> SolverPlayer {
            lock.withLock { infoCalls += 1 }
            if infoDelay > .zero { try await Task.sleep(for: infoDelay) }
            return SolverPlayer(id: "0229cd64", signatureTimestamp: 20739)
        }

        func solve(playerId: String, n: [String], s: [String]) async throws -> SolvedChallenges {
            lock.withLock { solveCalls += 1 }
            if let solveError { throw solveError }
            if dropAnswers { return SolvedChallenges(n: [:], s: [:]) }
            return SolvedChallenges(n: Dictionary(uniqueKeysWithValues: n.map { ($0, "SOLVEDN" + $0) }),
                                    s: Dictionary(uniqueKeysWithValues: s.map { ($0, "SOLVEDS" + $0) }))
        }
    }

    private struct AlwaysAvailable: AvailabilityGate {
        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
    }

    private struct Rig {
        let resolver: StreamResolver
        let session: SessionStore
        let clock: ManualClock
    }

    private func fixture(_ name: String) throws -> HTTPResponse {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return HTTPResponse(status: 200, headers: [:], body: try Data(contentsOf: url))
    }

    private func body(_ json: String) -> HTTPResponse { HTTPResponse(status: 200, headers: [:], body: Data(json.utf8)) }

    /// The bundled default with `order` (by default the published Phase 2 order: the TV rung appended).
    private func rig(_ transport: HTTPTransport, solver: ChallengeSolving?,
                     order: [String] = ["visionosHLS", "androidItag18", "tvSamsungAdaptive"],
                     locale: InnerTubeLocale = InnerTubeLocale(hl: "en", gl: "US"), delay: Duration = .zero) async -> Rig {
        var config = RemoteConfig.bundledDefault
        config.resolverOrder = order
        let configBody = try! JSONEncoder().encode(config)
        let store = RemoteConfigStore(
            transport: FixtureTransport(routes: [.init(match: { _ in true }, response: .init(status: 200, headers: [:], body: configBody))]),
            keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/config.json")!)
        await store.refresh()
        let clock = ManualClock()
        let session = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        let resolver = StreamResolver(
            transport: transport, remoteConfigStore: store, sessionStore: session, cache: ManifestCache(remoteConfig: store),
            gate: AlwaysAvailable(), monotonicClock: clock, wallClock: clock, locale: locale, minPostSpacing: .zero,
            challengeSolver: solver, hdFirstFetchDelay: delay)
        return Rig(resolver: resolver, session: session, clock: clock)
    }

    @Test func anUpgradeIsOneTVPostAndOneSolveAndReturnsASyntheticHLS() async throws {
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let solver = FakeSolver()
        let rig = await rig(transport, solver: solver)
        let hd = try #require(await rig.resolver.upgrade("Ix7uzPm3cU8"))
        #expect(transport.callCount == 1)
        #expect(transport.recorded[0].url.host() == "www.youtube.com")
        #expect(transport.capturedBodies[0].contains("\"signatureTimestamp\":20739"))
        #expect(solver.playerInfoCalls == 1)
        #expect(solver.solveCount == 1)
        #expect(hd.client == .tvSamsung)
        #expect(hd.userAgent == RemoteConfig.bundledDefault.clients["tvSamsung"]?.userAgent)
        #expect(!hd.isLiveNow)                                              // hand-off: HD is VOD only
        guard case .hls(let url, let isLive, let audioOnlyURL, _, let synthetic?) = hd.stream else {
            Issue.record("expected a synthetic .hls"); return
        }
        #expect(url.scheme == "fitrah-hls")
        #expect(url.lastPathComponent == "master.m3u8")
        #expect(!isLive)
        #expect(synthetic.video.first?.height == 1080)
        #expect((synthetic.video + synthetic.audio).allSatisfy { $0.url.scheme == "https" && $0.url.absoluteString.contains("n=SOLVEDN") })
        #expect(audioOnlyURL == synthetic.audio.first?.url)
        #expect(rig.resolver.hasChallengeSolver)
    }

    @Test func withoutASolverOrThePublishedRungTheUpgradeIsInert() async throws {   // R2, R20
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let noSolver = await rig(transport, solver: nil)
        #expect(await noSolver.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(!noSolver.resolver.hasChallengeSolver)
        let solver = FakeSolver()
        let unpublished = await rig(transport, solver: solver, order: ["visionosHLS", "androidItag18"])
        #expect(await unpublished.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.callCount == 0)
        #expect(solver.playerInfoCalls == 0)
    }

    @Test func theResultArrivesNoEarlierThanTheGateAfterThePlayerResponse() async throws {   // R42, scaled to 300 ms
        let rig = await rig(RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")]), solver: FakeSolver(), delay: .milliseconds(300))
        let start = ContinuousClock.now
        _ = try #require(await rig.resolver.upgrade("Ix7uzPm3cU8"))
        #expect(ContinuousClock.now - start >= .milliseconds(300))
    }

    @Test func playerInfoIsCachedThirtyMinutesOnTheMonotonicClock() async throws {   // R23
        let solver = FakeSolver()
        let rig = await rig(RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")]), solver: solver)
        _ = await rig.resolver.upgrade("Ix7uzPm3cU8")
        _ = await rig.resolver.upgrade("HCWGshgFlqE")
        #expect(solver.playerInfoCalls == 1)
        rig.clock.advance(by: .seconds(1801))
        _ = await rig.resolver.upgrade("uxeSkxtZU5I")
        #expect(solver.playerInfoCalls == 2)
    }

    @Test func a409ClearsThePlayerCacheAndOpensNoWindow() async throws {   // M14
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let solver = FakeSolver()
        solver.solveError = .unknownPlayer
        let rig = await rig(transport, solver: solver)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        solver.solveError = nil
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
        #expect(solver.playerInfoCalls == 2)
        #expect(transport.callCount == 2)
    }

    @Test(arguments: zip([Int?.some(3600), nil, 30, -5], [120, 120, 30, 0]))
    func aSolverRefusalPausesHDForItsRetryAfterClampedTo120Seconds(_ retryAfter: Int?, _ pause: Int) async throws {   // R23
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let solver = FakeSolver()
        solver.solveError = .unavailable(retryAfterSeconds: retryAfter)
        let rig = await rig(transport, solver: solver)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        solver.solveError = nil
        if pause > 0 {
            rig.clock.advance(by: .seconds(pause - 1))
            #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
            #expect(transport.callCount == 1)
            rig.clock.advance(by: .seconds(2))
        }
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    @Test(.timeLimit(.minutes(1))) func aPlayerInfoTimeoutPausesHD() async throws {   // R22: 1.5 s
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let solver = FakeSolver()
        solver.infoDelay = .seconds(3)
        let rig = await rig(transport, solver: solver)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.callCount == 0)
        solver.infoDelay = .zero
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
        rig.clock.advance(by: .seconds(121))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    @Test func aMissingAnswerPausesHD() async throws {   // R11
        let solver = FakeSolver()
        solver.dropAnswers = true
        let rig = await rig(RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")]), solver: solver)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        solver.dropAnswers = false
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
    }

    @Test func noByteRangesMeansNoHDForThatVideoOnly() async throws {   // R7
        var json = try JSONSerialization.jsonObject(with: try fixture("player-tv-Ix7uzPm3cU8").body) as! [String: Any]
        var streaming = json["streamingData"] as! [String: Any]
        streaming["adaptiveFormats"] = (streaming["adaptiveFormats"] as! [[String: Any]])
            .map { var f = $0; f.removeValue(forKey: "indexRange"); return f }
        json["streamingData"] = streaming
        let live = HTTPResponse(status: 200, headers: [:], body: try JSONSerialization.data(withJSONObject: json))
        let transport = RecordingTransport([live, try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.callCount == 1)
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    /// Hand-off (Task 11 review): a defect in YouTube's data is that video's HD failure, never R23's backend window.
    /// `n=%ZZ` survives `select` (`URL(string:)` escapes it) but cannot be decoded, so no challenge goes out for it and
    /// the rewrite throws `RewriteError.missingAnswer` with every backend answer present.
    @Test func aRewriteErrorDeniesThatVideoOnlyAndOpensNoWindow() async throws {
        var json = try JSONSerialization.jsonObject(with: try fixture("player-tv-Ix7uzPm3cU8").body) as! [String: Any]
        var streaming = json["streamingData"] as! [String: Any]
        streaming["adaptiveFormats"] = (streaming["adaptiveFormats"] as! [[String: Any]]).map { format in
            var f = format
            if f["itag"] as? Int == 137 { f["url"] = (f["url"] as! String).replacingOccurrences(of: "&n=", with: "&n=%ZZ&n0=") }
            return f
        }
        json["streamingData"] = streaming
        let broken = HTTPResponse(status: 200, headers: [:], body: try JSONSerialization.data(withJSONObject: json))
        let selection = try #require(TVAdaptive.select(try PlayerResponseParser().adaptiveFormats(broken.body)))
        let challenges = TVAdaptive.challenges(selection)
        let allAnswered = SolvedChallenges(n: Dictionary(uniqueKeysWithValues: challenges.n.map { ($0, "x") }),
                                           s: Dictionary(uniqueKeysWithValues: challenges.s.map { ($0, "x") }))
        #expect(throws: TVAdaptive.RewriteError.missingAnswer) { try TVAdaptive.synthetic(selection, answers: allAnswered) }

        let transport = RecordingTransport([broken, try fixture("player-tv-Ix7uzPm3cU8")])
        let solver = FakeSolver()
        let rig = await rig(transport, solver: solver)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(solver.solveCount == 1)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)          // denied for this video, no second POST
        #expect(transport.callCount == 1)
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)          // no window: the next video upgrades at once
    }

    /// Hand-off (Task 11 review): an undecodable TV answer is the same: this video only.
    @Test func anUndecodableTVAnswerDeniesThatVideoOnly() async throws {
        let transport = RecordingTransport([body("<html>"), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    /// R5: synthetic shapes (no TVHTML5 refusal was ever captured, spec §9.6). None is a verdict, none pauses HD.
    @Test(arguments: [
        #"{"playabilityStatus":{"status":"UNPLAYABLE","reason":"x"}}"#,
        #"{"playabilityStatus":{"status":"ERROR","reason":"x"}}"#,
        #"{"playabilityStatus":{"status":"LIVE_STREAM_OFFLINE","reason":"x"}}"#,
        #"{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"x","desktopLegacyAgeGateReason":1}}"#,
        #"{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"x","messages":["private"]}}"#,
    ])
    func everyNonOKTVAnswerMeansNoHDAndNoVerdict(_ json: String) async throws {
        let transport = RecordingTransport([body(json), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(await rig.session.cooldownRemaining(now: rig.clock.wallNow) == nil)
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    @Test func theTokenlessBootstrapAdoptsTheFreshVisitorAndRetriesOnce() async throws {   // R6
        let transport = RecordingTransport([try fixture("player-tv-botcheck-tokenless"), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") != nil)
        #expect(transport.callCount == 2)
        #expect(await rig.session.visitorData(for: .tvSamsung) == "FIXTURE_VISITOR_DATA")
        #expect(transport.recorded[1].headers["X-Goog-Visitor-Id"] == "FIXTURE_VISITOR_DATA")
        #expect(await rig.session.visitorData(for: .visionos) == nil)       // one session per family (R3)
    }

    @Test func aTokenedBotCheckPausesHDForThirtyMinutesKeepsTheVisitorAndNeverArmsTheCooldown() async throws {   // R44
        let transport = RecordingTransport([try fixture("player-tv-botcheck-tokened"), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        await rig.session.setVisitorData("STORED_TV_VISITOR", for: .tvSamsung)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.callCount == 1)
        #expect(await rig.session.visitorData(for: .tvSamsung) == "STORED_TV_VISITOR")
        #expect(await rig.session.cooldownRemaining(now: rig.clock.wallNow) == nil)
        rig.clock.advance(by: .seconds(1799))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
        #expect(transport.callCount == 1)
        rig.clock.advance(by: .seconds(2))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    @Test(arguments: [403, 429])
    func anHTTPRefusalOnTheTVPlayerIsABotCheck(_ status: Int) async throws {   // R44
        let transport = RecordingTransport([HTTPResponse(status: status, headers: [:], body: Data()), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        await rig.session.setVisitorData("STORED_TV_VISITOR", for: .tvSamsung)
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(await rig.session.visitorData(for: .tvSamsung) == "STORED_TV_VISITOR")    // never rotated
        rig.clock.advance(by: .seconds(1799))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
        #expect(transport.callCount == 1)
    }

    /// Review Focus 5. A synthetic shape: the reason is localized, the structure decides (as P1's ar bot check).
    @Test func anArabicTVBotCheckStillPausesHD() async throws {
        let botCheck = #"{"playabilityStatus":{"status":"LOGIN_REQUIRED","reason":"يرجى تسجيل الدخول"}}"#
        let transport = RecordingTransport([body(botCheck), try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver(), locale: InnerTubeLocale(hl: "ar", gl: "MA"))
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.capturedBodies[0].contains(#""hl":"ar""#))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)          // paused, not just this video
        #expect(transport.callCount == 1)
    }

    @Test func hdFailedDeniesThatVideoOnly() async throws {   // R41 (revision 4)
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        await rig.resolver.hdFailed("Ix7uzPm3cU8")
        #expect(await rig.resolver.upgrade("Ix7uzPm3cU8") == nil)
        #expect(transport.callCount == 0)
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)        // no window: another video upgrades at once
    }

    @Test func hdRefusedPausesHDForThirtyMinutes() async throws {   // R42 (revision 4)
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        await rig.resolver.hdRefused()
        rig.clock.advance(by: .seconds(1799))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
        #expect(transport.callCount == 0)
        rig.clock.advance(by: .seconds(2))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }

    /// Hand-off (Task 13 review): the loader and the app may both report one refusal. A second call 10 s later ends the
    /// pause at its own now + 30 min, never 30 min after the first pause ends.
    @Test func hdRefusedTwiceDoesNotStackThePause() async throws {
        let transport = RecordingTransport([try fixture("player-tv-Ix7uzPm3cU8")])
        let rig = await rig(transport, solver: FakeSolver())
        await rig.resolver.hdRefused()
        rig.clock.advance(by: .seconds(10))
        await rig.resolver.hdRefused()
        rig.clock.advance(by: .seconds(1799))
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") == nil)
        #expect(transport.callCount == 0)
        rig.clock.advance(by: .seconds(2))                                   // 10 + 1801 s: past now + 30 min of the second call
        #expect(await rig.resolver.upgrade("HCWGshgFlqE") != nil)
    }
}
