import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct StreamResolverTests {
    private static let videoId = "dQw4w9WgXcQ"

    // MARK: - test doubles

    /// Hangs the FIRST call until it is cancelled; later calls answer immediately. Lets a test
    /// hold a resolve open at the transport while another caller supersedes it.
    private final class GatedTransport: HTTPTransport, @unchecked Sendable {
        // Sendable: all mutable state is guarded by `lock`.
        private let lock = NSLock()
        private var count = 0
        private let response: HTTPResponse

        init(_ response: HTTPResponse) { self.response = response }

        var callCount: Int { lock.withLock { count } }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            let ordinal = lock.withLock { count += 1; return count }
            if ordinal == 1 { try await Task.sleep(for: .seconds(30)) }
            return response
        }

        /// Bounded wait so a wiring regression fails the assertions instead of hanging the suite.
        func waitForFirstCall() async {
            var attempts = 0
            while callCount == 0, attempts < 1000 {
                try? await Task.sleep(for: .milliseconds(1))
                attempts += 1
            }
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
        cache: ManifestCache = ManifestCache(configTTLSeconds: 3600),
        locale: InnerTubeLocale = InnerTubeLocale(hl: "en", gl: "US")
    ) -> (StreamResolver, SessionStore) {
        let session = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: InMemoryKeyValueStore())
        let configStore = RemoteConfigStore(
            transport: NoopTransport(),
            keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.com/config.json")!
        )
        let resolver = StreamResolver(
            transport: transport,
            remoteConfigStore: configStore,
            sessionStore: session,
            cache: cache,
            gate: gate,
            monotonicClock: clock,
            wallClock: clock,
            locale: locale,
            minPostSpacing: .zero
        )
        return (resolver, session)
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

    // MARK: - c) ageGate skips straight to openInYouTube, never rotates

    @Test func ageGateJumpsToOpenInYouTubeWithoutRotating() async throws {
        let transport = RecordingTransport([try fixtureResponse("player-age-gated")])
        let (resolver, session) = makeResolver(transport: transport)
        await session.setVisitorData("original-visitor", for: .visionos)

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .openInYouTube = resolved.stream else {
            Issue.record("expected .openInYouTube, got \(resolved.stream)"); return
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
        // Both player rungs answer UNPLAYABLE, so the ladder bottoms out on `embed`.
        let clock = ManualClock()
        let cache = ManifestCache(configTTLSeconds: 3600)
        let transport = RecordingTransport([try fixtureResponse("player-unplayable-kids")])
        let (resolver, session) = makeResolver(transport: transport, clock: clock, cache: cache)
        await session.recordBotCheck()
        clock.advanceWall(by: .seconds(8 * 24 * 3600))  // past the 7-day clean-streak reset window

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .embed = resolved.stream else { Issue.record("expected .embed, got \(resolved.stream)"); return }

        #expect(await cache.get(Self.videoId, now: clock.wallNow) == nil)
        #expect(await session.loadCooldown().tripCount == 1)
    }

    @Test func nativeStreamIsCachedAndRecordsSuccess() async throws {
        let clock = ManualClock()
        let cache = ManifestCache(configTTLSeconds: 3600)
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
        await transport.waitForFirstCall()  // the first job is registered and out on the wire

        let winner = try await resolver.resolve(
            Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: true)
        guard case .hls = winner.stream else { Issue.record("expected .hls, got \(winner.stream)"); return }

        // The cancelled job's awaiter gets the winner's stream, not a spurious CancellationError.
        let adopted = try await superseded
        guard case .hls = adopted.stream else { Issue.record("expected adopted .hls, got \(adopted.stream)"); return }
        #expect(transport.callCount == 2)
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
