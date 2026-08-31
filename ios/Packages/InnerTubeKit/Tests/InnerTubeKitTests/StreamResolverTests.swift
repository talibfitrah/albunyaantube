import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct StreamResolverTests {
    private static let videoId = "xc7keR2piUM"

    // MARK: - test doubles

    /// Hangs the first `hangCount` calls until each is cancelled; later calls answer immediately.
    /// Lets a test hold N resolves open at the transport while later callers each supersede the one
    /// before (a `hangCount` of 1 is the plain single-supersede case).
    private final class GatedTransport: HTTPTransport, @unchecked Sendable {
        // Sendable: all mutable state is guarded by `lock`.
        private let lock = NSLock()
        private var count = 0
        private let response: HTTPResponse
        private let hangCount: Int

        init(_ response: HTTPResponse, hangCount: Int = 1) {
            self.response = response
            self.hangCount = hangCount
        }

        var callCount: Int { lock.withLock { count } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let ordinal = lock.withLock { count += 1; return count }
            if ordinal <= hangCount { try await Task.sleep(for: .seconds(30)) }
            return response
        }

        /// Bounded wait so a wiring regression fails the assertions instead of hanging the suite.
        func waitForCall(_ ordinal: Int = 1) async {
            var attempts = 0
            while callCount < ordinal, attempts < 1000 {
                try? await Task.sleep(for: .milliseconds(1))
                attempts += 1
            }
        }
    }

    /// Records the real (wall) instant of each POST, relative to construction. Distinct from
    /// `RecordingTransport`, which records requests but not their firing time — the ≥spacing
    /// assertion needs the actual inter-POST gaps, which the resolver produces via `Task.sleep`
    /// (real time), independent of the injected ManualClock.
    private final class TimestampTransport: HTTPTransport, @unchecked Sendable {
        // Sendable: all mutable state is guarded by `lock`.
        private let lock = NSLock()
        private let response: HTTPResponse
        private let clock = ContinuousClock()
        private let start: ContinuousClock.Instant
        private var stamps: [Duration] = []

        init(_ response: HTTPResponse) {
            self.response = response
            self.start = clock.now
        }

        var timestamps: [Duration] { lock.withLock { stamps } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let elapsed = clock.now - start
            lock.withLock { stamps.append(elapsed) }
            return response
        }
    }

    private struct StubGate: AvailabilityGate {
        var available: Bool = true
        var error: Error?

        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool {
            if let error { throw error }
            return available
        }
    }

    // MARK: - fixtures

    private func fixtureResponse(_ name: String) throws -> HTTPResponse {
        let url = try #require(Bundle.module.url(forResource: name, withExtension: "json", subdirectory: "Fixtures"))
        return HTTPResponse(status: 200, headers: [:], body: try Data(contentsOf: url))
    }

    // Synthetic ANDROID player response: the real fixtures carry only `adaptiveFormats`,
    // so the itag-18 progressive path (a SEPARATE ANDROID request per ios-app-plan.md §6.2
    // step 4) needs a body that actually has `formats[itag 18]`.
    private var androidItag18Response: HTTPResponse {
        let body = Data("""
        {"playabilityStatus":{"status":"OK"},"streamingData":{"formats":[{"itag":18,"url":"https://rr1.googlevideo.com/videoplayback?itag=18"}]},"videoDetails":{"isLive":false}}
        """.utf8)
        return HTTPResponse(status: 200, headers: [:], body: body)
    }

    // MARK: - builder

    private func makeResolver(
        transport: HTTPTransport,
        gate: AvailabilityGate = StubGate(),
        clock: ManualClock = ManualClock(),
        cache: ManifestCache? = nil,
        locale: InnerTubeLocale = InnerTubeLocale(hl: "en", gl: "US"),
        configStore: RemoteConfigStore? = nil,
        minPostSpacing: Duration = .zero
    ) -> (StreamResolver, SessionStore) {
        let session = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        let resolvedConfigStore = configStore ?? RemoteConfigStore(
            transport: NoopTransport(),
            keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.com/config.json")!
        )
        // Un-refreshed store => the bundled default's 3600 s TTL, the value this builder pinned before.
        let resolvedCache = cache ?? ManifestCache(remoteConfig: resolvedConfigStore)
        let resolver = StreamResolver(
            transport: transport,
            remoteConfigStore: resolvedConfigStore,
            sessionStore: session,
            cache: resolvedCache,
            gate: gate,
            monotonicClock: clock,
            wallClock: clock,
            locale: locale,
            minPostSpacing: minPostSpacing
        )
        return (resolver, session)
    }

    private func defaultConfigStore() -> RemoteConfigStore {
        RemoteConfigStore(
            transport: NoopTransport(), keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.com/config.json")!)
    }

    /// The bundled default with a different `resolverOrder`, seeded as last-good so `current()`
    /// returns it without a refresh (the same route d7 below uses for a bad client table).
    private func configStore(resolverOrder: [String]) -> RemoteConfigStore {
        var config = RemoteConfig.bundledDefault
        config.resolverOrder = resolverOrder
        let keyValueStore = InMemoryKeyValueStore()
        keyValueStore.set(RemoteConfigStore.lastGoodKey, try! JSONEncoder().encode(config))
        return RemoteConfigStore(
            transport: NoopTransport(), keyValueStore: keyValueStore,
            url: URL(string: "https://example.com/config.json")!)
    }

    private struct NoopTransport: HTTPTransport {
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            HTTPResponse(status: 200, headers: [:], body: Data())
        }
    }

    private func expectThrows(_ expected: ExtractionError, _ body: () async throws -> Void) async {
        do {
            try await body()
            Issue.record("expected \(expected) to be thrown")
        } catch let error as ExtractionError {
            #expect(error == expected)
        } catch {
            Issue.record("expected \(expected), got \(error)")
        }
    }

    // MARK: - a) VISIONOS ok -> .hls, and the manifest is cached

    @Test func visionosOkYieldsHLSAndCachesSecondResolve() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport)

        let first = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = first.stream else { Issue.record("expected .hls, got \(first.stream)"); return }

        let second = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = second.stream else { Issue.record("expected cached .hls, got \(second.stream)"); return }

        #expect(transport.callCount == 1)
    }

    // MARK: - b) VISIONOS unplayableKids -> ANDROID itag18 -> .progressive("360p")

    @Test func unplayableKidsFallsToAndroidItag18() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-unplayable-kids"), androidItag18Response])
        let (resolver, _) = makeResolver(transport: transport)

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .progressive(_, let label) = resolved.stream else {
            Issue.record("expected .progressive, got \(resolved.stream)"); return
        }
        #expect(label == "360p")
        #expect(transport.callCount == 2)
    }

    // MARK: - c) ageGate is terminal, never rotates, and never hands off to YouTube

    /// OWNER DIRECTIVE 2026-08-27: an age gate used to jump the ladder to a YouTube hand-off. There
    /// is no hand-off any more, so it is simply terminal (`ageRestricted`, which the app already
    /// maps onto its one "not playable" surface -- ruling 14).
    @Test func ageGateIsTerminalAndNeverHandsOffToYouTube() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-age-gated")])
        let (resolver, session) = makeResolver(transport: transport)
        await session.setVisitorData("original-visitor", for: .visionos)

        await #expect(throws: ExtractionError.ageRestricted) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(await session.visitorData(for: .visionos) == "original-visitor")
        #expect(transport.callCount == 1)
    }

    // MARK: - d) botCheck rotates once and retries the same rung, succeeding on retry

    @Test func botCheckRotatesAndRetriesSameRung() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-botcheck"), try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport)
        await session.setVisitorData("v1", for: .visionos)

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = resolved.stream else { Issue.record("expected .hls after retry, got \(resolved.stream)"); return }

        #expect(transport.callCount == 2)
        // The retry must carry a REFRESHED session, not a byte-identical repeat: rotate() dropped
        // the tripped visitor, so the retry goes out tokenless and YouTube mints a new one.
        let sent = transport.recorded
        #expect(sent.count == 2)
        #expect(sent.first?.headers["X-Goog-Visitor-Id"] == "v1")
        #expect(sent.last?.headers["X-Goog-Visitor-Id"] == nil)
        #expect(sent.first?.body != sent.last?.body)
        // ...and the visitor the successful retry came back with is captured for the next call
        // (§6.3). Without the `setVisitorData` wiring this is nil and the ladder stays tokenless.
        let captured = try #require(await session.visitorData(for: .visionos))
        let expected = try #require(try PlayerResponseParser().parse(fixtureResponse("player-ok-hls").body).visitorData)
        #expect(captured == expected)
    }

    // MARK: - d1) a tokenless first call bootstraps its session from the bot-check response

    /// The first-ever call under a family has no `visitorData`, so YouTube bot-checks it — but
    /// that response carries a freshly-minted `responseContext.visitorData` (probe.py A.1's
    /// "a bare request establishes the session"). Adopting it and retrying the SAME rung is what
    /// turns the live ladder's first play into `.hls`; without it the retry repeats the tokenless
    /// request, trips again, and the ladder demotes to ANDROID itag-18.
    @Test func tokenlessFirstCallAdoptsTheBotCheckVisitorAndRetriesTheSameRung() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-botcheck"), try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport)  // no stored visitor

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = resolved.stream else { Issue.record("expected .hls after bootstrap, got \(resolved.stream)"); return }

        #expect(transport.callCount == 2)
        let sent = transport.recorded
        #expect(sent.first?.headers["X-Goog-Visitor-Id"] == nil)  // bare, as it must be
        let bootstrapped = try #require(
            try PlayerResponseParser().parse(fixtureResponse("player-botcheck").body).visitorData)
        #expect(sent.last?.headers["X-Goog-Visitor-Id"] == bootstrapped)
        #expect(String(data: try #require(sent.last?.body), encoding: .utf8)?.contains(bootstrapped) == true)
        // The rotation budget is untouched — a bootstrap is not a rotation, so a genuine bot check
        // later in this 10-minute window can still rotate.
        #expect(await session.rotate(.visionos) == true)
    }

    // MARK: - d2) the captured visitor is replayed on the next resolve (§6.3)

    @Test func capturedVisitorDataIsReplayedOnTheNextResolve() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport)

        _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        let captured = try #require(await session.visitorData(for: .visionos))
        #expect(transport.recorded.first?.headers["X-Goog-Visitor-Id"] == nil)  // nothing to send yet

        _ = try await resolver.resolve("abcdefghijk", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        let replayed = try #require(transport.recorded.last)
        #expect(replayed.headers["X-Goog-Visitor-Id"] == captured)
        // Sent as `context.client.visitorData` too, not only as the header.
        let body = try #require(replayed.body)
        #expect(String(data: body, encoding: .utf8)?.contains(captured) == true)
    }

    // MARK: - d3) Accept-Language is pinned to the injected locale, not the device's

    @Test func acceptLanguageIsPinnedToTheInjectedLocale() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport, locale: InnerTubeLocale(hl: "ar", gl: "MA"))

        _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        #expect(transport.recorded.first?.headers["Accept-Language"] == "ar")
    }

    // MARK: - d4) fallback rungs are neither cached nor counted as a clean fetch

    @Test func embedFallbackIsNotCachedAndDoesNotRecordSuccess() async throws {
        // Both player rungs answer UNPLAYABLE, so the ladder bottoms out on `embed` -- which ships
        // DARK (not in the bundled default), so this is the published-config shape that enables it.
        let clock = ManualClock()
        let configStore = configStore(resolverOrder: ["visionosHLS", "androidItag18", "embed"])
        let cache = ManifestCache(remoteConfig: configStore)
        let transport = RecordingTransport([try fixtureResponse("player-unplayable-kids")])
        let (resolver, session) = makeResolver(transport: transport, clock: clock, cache: cache, configStore: configStore)
        await session.recordBotCheck()
        clock.advanceWall(by: .seconds(8 * 24 * 3600))  // past the 7-day clean-streak reset window

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .embed = resolved.stream else { Issue.record("expected .embed, got \(resolved.stream)"); return }

        #expect(await cache.get(Self.videoId, now: clock.wallNow) == nil)
        #expect(await session.loadCooldown().tripCount == 1)
    }

    /// B3 embed ruling (2026-08-27): the bundled default is visionosHLS -> androidItag18 and then
    /// TERMINAL. Both rungs answering UNPLAYABLE must surface as `allRungsFailed` (which
    /// `PlayerViewModel.map` renders as the generic player error), never silently resolve to `embed`.
    @Test func defaultLadderIsTerminalAfterAndroidItag18() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-unplayable-kids")])
        let (resolver, _) = makeResolver(transport: transport)

        await expectThrows(.allRungsFailed) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 2)   // one POST per native rung, then nothing
    }

    @Test func nativeStreamIsCachedAndRecordsSuccess() async throws {
        let clock = ManualClock()
        let cache = ManifestCache(remoteConfig: defaultConfigStore())
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport, clock: clock, cache: cache)
        await session.recordBotCheck()
        clock.advanceWall(by: .seconds(8 * 24 * 3600))

        _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)

        #expect(await cache.get(Self.videoId, now: clock.wallNow) != nil)
        #expect(await session.loadCooldown().tripCount == 0)
    }

    // MARK: - d5) an awaiter superseded by another caller's forceRefresh adopts the winner

    @Test func supersededAwaiterAdoptsWinnerInsteadOfCancellation() async throws {
        let transport = GatedTransport(try fixtureResponse("player-ok-hls"))
        let (resolver, _) = makeResolver(transport: transport)

        async let superseded = resolver.resolve(
            Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        await transport.waitForCall()  // the first job is registered and out on the wire

        let winner = try await resolver.resolve(
            Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: true)
        guard case .hls = winner.stream else { Issue.record("expected .hls, got \(winner.stream)"); return }

        // The cancelled job's awaiter gets the winner's stream, not a spurious CancellationError.
        let adopted = try await superseded
        guard case .hls = adopted.stream else { Issue.record("expected adopted .hls, got \(adopted.stream)"); return }
        #expect(transport.callCount == 2)
    }

    /// Cubic #1: `URLSessionTransport` does no error mapping, so a job cancelled mid-request can
    /// surface `URLError(.cancelled)` instead of `CancellationError`. First call hangs and throws
    /// exactly that on cancellation; later calls answer immediately.
    private final class URLErrorCancellingTransport: HTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        private let response: HTTPResponse

        init(_ response: HTTPResponse) { self.response = response }

        var callCount: Int { lock.withLock { count } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let ordinal = lock.withLock { count += 1; return count }
            if ordinal == 1 {
                do { try await Task.sleep(for: .seconds(30)) } catch { throw URLError(.cancelled) }
            }
            return response
        }

        func waitForCall() async {
            var attempts = 0
            while callCount < 1, attempts < 1000 {
                try? await Task.sleep(for: .milliseconds(1))
                attempts += 1
            }
        }
    }

    /// First call throws the given error synchronously; later calls answer with the response.
    private final class FailFirstTransport: HTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        private let error: Error
        private let response: HTTPResponse

        init(error: Error, then response: HTTPResponse) {
            self.error = error
            self.response = response
        }

        var callCount: Int { lock.withLock { count } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let ordinal = lock.withLock { count += 1; return count }
            if ordinal == 1 { throw error }
            return response
        }
    }

    @Test func aTransportLevelURLErrorCancelledStopsTheLadderInsteadOfWalkingIt() async throws {
        // Deterministic half of Cubic #1: a rung that dies with `URLError(.cancelled)` must read as
        // cancellation -- stop the ladder, surface `.cancelled` -- not as a rung failure that walks
        // on down and spends more POSTs on a resolve nobody is waiting for.
        let transport = FailFirstTransport(error: URLError(.cancelled), then: try fixtureResponse("player-ok-hls"))
        let (resolver, _) = makeResolver(transport: transport)

        await #expect(throws: ExtractionError.cancelled) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 1)
    }

    @Test func supersededAwaiterAdoptsWinnerWhenTheCancelledJobSurfacesURLErrorCancelled() async throws {
        // Same contract as d5, but the cancelled job dies with `URLError(.cancelled)` -- the shape
        // the real transport produces -- rather than a clean `CancellationError`. The adoption path
        // must treat both as cancellation, or the superseded awaiter gets a spurious failure.
        let transport = URLErrorCancellingTransport(try fixtureResponse("player-ok-hls"))
        let (resolver, _) = makeResolver(transport: transport)

        async let superseded = resolver.resolve(
            Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        await transport.waitForCall()

        let winner = try await resolver.resolve(
            Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: true)
        guard case .hls = winner.stream else { Issue.record("expected .hls, got \(winner.stream)"); return }

        let adopted = try await superseded
        guard case .hls = adopted.stream else { Issue.record("expected adopted .hls, got \(adopted.stream)"); return }
    }

    // MARK: - d6) reason -> terminal error mapping (ruling 14)

    @Test func terminalErrorMapsReasonToItsTerminalError() {
        let cases: [(String, ExtractionError)] = [
            ("This video is private", .private),
            ("This video has been removed by the uploader", .removed),
            ("This video is no longer available due to a copyright claim", .removed),
            ("The uploader has not made this video available in your country", .geoBlocked),
            ("Sign in to confirm your age", .ageRestricted),
            ("This video is unavailable", .unavailable(videoId: Self.videoId)),
        ]
        for (reason, expected) in cases {
            #expect(StreamResolver.terminalError(reason: reason, videoId: Self.videoId) == expected, "reason: \(reason)")
            #expect(expected.terminal, "reason: \(reason)")
        }
    }

    // MARK: - d7) a sanitized-away client (renamed clientName) advances the rung, no crash

    @Test func resolverAdvancesPastRungWhoseClientWasSanitizedAwayForMismatchedClientName() async throws {
        let badConfig = RemoteConfig(
            schemaVersion: 1, minAppVersion: "1.0.0", resolverOrder: ["visionosHLS", "embed"],
            manifestCacheSeconds: 3600,
            clients: [
                "visionos": ClientContext(clientName: "RENAMED_CLIENT", clientVersion: "1", clientNameId: 101)
            ])
        let keyValueStore = InMemoryKeyValueStore()
        keyValueStore.set(RemoteConfigStore.lastGoodKey, try JSONEncoder().encode(badConfig))
        let configStore = RemoteConfigStore(
            transport: NoopTransport(), keyValueStore: keyValueStore, url: URL(string: "https://example.com/config.json")!)
        #expect(await configStore.current().clients["visionos"] == nil)  // sanitized away at load

        // Never called: the "visionos" client is gone, so `runPlayerRung` never reaches
        // `PlayerRequestBuilder.build` — the rung advances straight to `embed`, the last rung there
        // is (the YouTube hand-off rung was removed by owner directive 2026-08-27).
        let transport = RecordingTransport([])
        let (resolver, _) = makeResolver(transport: transport, configStore: configStore)

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .embed = resolved.stream else {
            Issue.record("expected .embed (advanced past the sanitized rung), got \(resolved.stream)"); return
        }
        #expect(transport.callCount == 0)
    }

    // MARK: - d5b) chained supersede: an awaiter of a superseded awaiter still adopts the final winner

    @Test func chainedSupersedeAdoptsFinalWinnerNotCancellation() async throws {
        let transport = GatedTransport(try fixtureResponse("player-ok-hls"), hangCount: 2)
        let (resolver, _) = makeResolver(transport: transport)

        async let first = resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        await transport.waitForCall(1)  // job 1 is registered and out on the wire

        async let second = resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: true)
        await transport.waitForCall(2)  // job 2 (job 1's supersede) is registered and out on the wire

        let third = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: true)
        guard case .hls = third.stream else { Issue.record("expected .hls, got \(third.stream)"); return }

        // job 1's awaiter was superseded by job 2, which was itself superseded by job 3 before job 1
        // ever looked at job 2's outcome — it must adopt job 3's stream, not a raw CancellationError.
        let firstResult = try await first
        guard case .hls = firstResult.stream else { Issue.record("expected chained-adopted .hls, got \(firstResult.stream)"); return }
        let secondResult = try await second
        guard case .hls = secondResult.stream else { Issue.record("expected adopted .hls, got \(secondResult.stream)"); return }
        #expect(transport.callCount == 3)
    }

    // MARK: - d8) one resolve walk records at most ONE bot-check trip (review F2a)

    /// Both rungs see the same LOGIN_REQUIRED, so a single walk used to call `recordBotCheck()`
    /// once PER RUNG: one video escalated trip 1 -> trip 2 = a 4 h app-wide persisted cooldown
    /// in seconds. A walk is one incident; it records one trip (the 1 h tier).
    @Test func aTwoRungWalkAgainstABotCheckedTransportRecordsExactlyOneTrip() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-botcheck")])
        let (resolver, session) = makeResolver(transport: transport)
        await session.setVisitorData("v1", for: .visionos)   // skip the bootstrap path: this is a
        await session.setVisitorData("v1", for: .android)    // genuine bot check on both rungs

        await expectThrows(.botCheck) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 4)   // per rung: POST + rotate + retry POST
        #expect(await session.loadCooldown().tripCount == 1)
    }

    // MARK: - d9) an HTTP-level 429/403 routes into the bot-check path (Cubic r3 #4)

    /// `sendPlayerPost` used to ignore HTTP status: a raw 429 body failed Wire decode -> generic
    /// rung failure -> the ladder walked on and rotation/cooldown never engaged. A 429/403 IS a
    /// bot block: same handling as a parsed LOGIN_REQUIRED (rotate once, then ONE recorded trip).
    @Test func http429RoutesIntoTheBotCheckPathNotAGenericRungFailure() async throws {
        let transport = RecordingTransport(
            [HTTPResponse(status: 429, headers: [:], body: Data("Too Many Requests".utf8))])
        let (resolver, session) = makeResolver(transport: transport)

        await expectThrows(.botCheck) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(await session.loadCooldown().tripCount == 1)
    }

    @Test func otherNon200IsARetryableTransportErrorNotABotTrip() async throws {
        let transport = RecordingTransport([HTTPResponse(status: 503, headers: [:], body: Data())])
        let (resolver, session) = makeResolver(transport: transport)

        await expectThrows(.transport("HTTP 503")) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(await session.loadCooldown().tripCount == 0)
    }

    // MARK: - e) two concurrent resolves for the same id issue ONE player POST

    @Test func concurrentResolvesShareOnePost() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport)

        async let a = resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        async let b = resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        let (ra, rb) = try await (a, b)

        guard case .hls = ra.stream, case .hls = rb.stream else {
            Issue.record("expected both .hls, got \(ra.stream) / \(rb.stream)"); return
        }
        #expect(transport.callCount == 1)
    }

    // MARK: - e2) concurrent DISTINCT-id resolves space their POSTs ≥ minPostSpacing apart (I1)

    /// The spacing throttle must survive actor reentrancy: three distinct videoIds resolve
    /// concurrently (single-flight does NOT dedupe them), each reserving a slot BEFORE its
    /// `Task.sleep`. With the old write-after-sleep ordering, concurrent callers read the same
    /// stale `lastPostInstant` and fire together — the ≥500 ms control collapses under burst load.
    @Test func concurrentDistinctIdResolvesSpacePlayerPostsAtLeastSpacingApart() async throws {
        let spacing: Duration = .milliseconds(200)
        let transport = TimestampTransport(try fixtureResponse("player-ok-hls"))
        let (resolver, _) = makeResolver(transport: transport, minPostSpacing: spacing)

        async let a = resolver.resolve("xc7keR2piUM", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        async let b = resolver.resolve("abcdefghijk", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        async let c = resolver.resolve("ABCDEFGHIJK", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        _ = try await (a, b, c)

        let stamps = transport.timestamps.sorted()
        #expect(stamps.count == 3)
        // Real sleeps never return early, so with the fix each gap is ≥ spacing (small tolerance for
        // arrival jitter in the three resolves' async prefix). Old ordering: two gaps ≈ 0 → fails.
        for i in 1..<stamps.count {
            let gap = stamps[i] - stamps[i - 1]
            #expect(gap >= spacing - .milliseconds(40), "POST \(i) gap \(gap) < spacing \(spacing)")
        }
    }

    // MARK: - e3) an active persisted cooldown short-circuits resolve with zero transport calls (I2)

    @Test func activeCooldownShortCircuitsResolveThenProceedsOnceElapsed() async throws {
        let clock = ManualClock()
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport, clock: clock)
        await session.recordBotCheck()  // 1st trip -> 1 h cooldown

        do {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
            Issue.record("expected .cooldown to be thrown")
        } catch let error as ExtractionError {
            guard case .cooldown = error else { Issue.record("expected .cooldown, got \(error)"); return }
        }
        #expect(transport.callCount == 0)  // gated before any network

        // Once the 1 h backoff elapses, resolve proceeds and hits the wire.
        clock.advanceWall(by: .seconds(3600 + 1))
        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = resolved.stream else { Issue.record("expected .hls, got \(resolved.stream)"); return }
        #expect(transport.callCount == 1)
    }

    // MARK: - f) invalid videoId throws before any transport call

    @Test func invalidVideoIdThrowsBeforeTransport() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport)

        await expectThrows(.invalidVideoId) {
            _ = try await resolver.resolve("short", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 0)
    }

    // MARK: - g) 410 from the availability gate throws .unavailable

    @Test func availabilityGateUnavailableThrowsUnavailable() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport, gate: StubGate(available: false))

        await expectThrows(.unavailable(videoId: Self.videoId)) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 0)
    }
}
