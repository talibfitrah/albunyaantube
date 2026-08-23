import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct StreamResolverTests {
    private static let videoId = "dQw4w9WgXcQ"

    // MARK: - test doubles

    /// Returns recorded responses in call order (repeating the last once exhausted)
    /// and counts how many times it was hit — the call-counting the single-flight
    /// and cache-hit assertions need.
    private final class ScriptedTransport: HTTPTransport, @unchecked Sendable {
        // Sendable: all mutable state is guarded by `lock`.
        private let lock = NSLock()
        private let responses: [HTTPResponse]
        private var index = 0
        private var count = 0

        init(_ responses: [HTTPResponse]) { self.responses = responses }

        var callCount: Int {
            lock.withLock { count }
        }

        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            lock.withLock {
                count += 1
                let response = responses[min(index, responses.count - 1)]
                index += 1
                return response
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
        cache: ManifestCache = ManifestCache(configTTLSeconds: 3600)
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
            locale: InnerTubeLocale(hl: "en", gl: "US"),
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
        let transport = ScriptedTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport)

        let first = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = first.stream else { Issue.record("expected .hls, got \(first.stream)"); return }

        let second = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = second.stream else { Issue.record("expected cached .hls, got \(second.stream)"); return }

        #expect(transport.callCount == 1)
    }

    // MARK: - b) VISIONOS unplayableKids -> ANDROID itag18 -> .progressive("360p")

    @Test func unplayableKidsFallsToAndroidItag18() async throws {
        let transport = ScriptedTransport([try fixtureResponse("player-unplayable-kids"), androidItag18Response])
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
        let transport = ScriptedTransport([try fixtureResponse("player-age-gated")])
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
        let transport = ScriptedTransport([try fixtureResponse("player-botcheck"), try fixtureResponse("player-ok-hls")])
        let (resolver, session) = makeResolver(transport: transport)
        await session.setVisitorData("v1", for: .visionos)

        let resolved = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        guard case .hls = resolved.stream else { Issue.record("expected .hls after retry, got \(resolved.stream)"); return }

        #expect(transport.callCount == 2)
        // rotate() cleared the visionos visitorData; it was not restored (no responseContext capture yet).
        #expect(await session.visitorData(for: .visionos) == nil)
    }

    // MARK: - e) two concurrent resolves for the same id issue ONE player POST

    @Test func concurrentResolvesShareOnePost() async throws {
        let transport = ScriptedTransport([try fixtureResponse("player-ok-hls")])
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
        let transport = ScriptedTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport)

        await expectThrows(.invalidVideoId) {
            _ = try await resolver.resolve("short", purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 0)
    }

    // MARK: - g) 410 from the availability gate throws .unavailable

    @Test func availabilityGateUnavailableThrowsUnavailable() async throws {
        let transport = ScriptedTransport([try fixtureResponse("player-ok-hls")])
        let (resolver, _) = makeResolver(transport: transport, gate: StubGate(available: false))

        await expectThrows(.unavailable(videoId: Self.videoId)) {
            _ = try await resolver.resolve(Self.videoId, purpose: .player, sourceChannelId: nil, forceRefresh: false)
        }
        #expect(transport.callCount == 0)
    }
}
