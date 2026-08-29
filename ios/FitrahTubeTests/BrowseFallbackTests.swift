import FitrahAPI
import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Plan C Task 2, CF-C3 + reconciliation note 3: the browse-only degraded-mode decision and its
/// persisted 1 h latch. Never touches `SessionStore`'s cooldown -- that ladder is the player's.
@Suite(.perTest)
struct BrowseFallbackTests {
    private static let now = Date(timeIntervalSince1970: 1_000)

    /// In-memory `KeyValueStore`; InnerTubeKit's own double lives in its test target, out of reach.
    private nonisolated final class InMemoryKeyValueStore: KeyValueStore, @unchecked Sendable {
        private var storage: [String: Data] = [:]
        func get(_ key: String) -> Data? { storage[key] }
        func set(_ key: String, _ value: Data) { storage[key] = value }
    }

    /// Counts requests; never answers (a latched source must not reach it at all).
    private nonisolated final class CountingTransport: HTTPTransport, @unchecked Sendable {
        private let lock = NSLock()
        private var count = 0
        var callCount: Int { lock.withLock { count } }
        func send(_ request: HTTPRequest) async throws -> HTTPResponse {
            lock.withLock { count += 1 }
            throw URLError(.badServerResponse)
        }
    }

    @Test func aLatchedSourceNeverReachesTheTransport() async {
        // Plan :407: "the second open never reaches the transport". Real BrowseClient over a counting
        // transport, latch pre-set in the future -> the no-substitute tab rethrows botCheck with zero
        // requests sent.
        let transport = CountingTransport()
        let store = InMemoryKeyValueStore()
        DegradedLatch(store: store).until = Date().addingTimeInterval(3600)
        let base = URL(string: "https://app.fitrahtube.com/")!
        let source = LiveBrowseSource(
            client: BrowseClient(transport: transport,
                                 remoteConfigStore: RemoteConfigStore(transport: transport, keyValueStore: store, url: base),
                                 sessionStore: SessionStore(monotonicClock: SystemClock(), wallClock: SystemClock(), keyValueStore: store),
                                 locale: InnerTubeLocale(hl: "en", gl: "US")),
            atom: AtomFeedFetcher(transport: transport, keyValueStore: store),
            latch: DegradedLatch(store: store),
            index: IndexClient(transport: transport, baseURL: base, deviceId: DeviceId(value: "t")),
            gate: BackendAvailabilityGate(transport: transport, baseURL: base),
            degradedHeader: nil)
        await #expect(throws: BrowseError.botCheck) { try await source.channelTab("UC1", tab: .live, continuation: nil) }
        await #expect(throws: BrowseError.botCheck) { try await source.channelPlaylists("UC1", continuation: nil) }
        #expect(transport.callCount == 0)
    }

    @Test func aBotCheckDegradesAndLatchesForOneHour() {
        // CF-C3 + reconciliation note 3: flat 1 h, not the resolver's 1h->24h ladder.
        guard case .degrade(let until) = BrowseFallback.decide(BrowseError.botCheck, latchedUntil: nil, now: Self.now)
        else { Issue.record("expected .degrade"); return }
        #expect(until == Self.now.addingTimeInterval(3600))
        #expect(BrowseFallback.latchDuration == 3600)
    }

    @Test func aLiveLatchSkipsTheProbeEntirely() {
        #expect(BrowseFallback.decide(BrowseError.botCheck, latchedUntil: Self.now + 60, now: Self.now) == .alreadyDegraded)
        #expect(BrowseFallback.isLatched(until: Self.now + 60, now: Self.now))
    }

    @Test func anExpiredLatchProbesAgain() {
        #expect(BrowseFallback.isLatched(until: Self.now - 1, now: Self.now) == false)
        #expect(BrowseFallback.isLatched(until: nil, now: Self.now) == false)
        guard case .degrade = BrowseFallback.decide(BrowseError.botCheck, latchedUntil: Self.now - 1, now: Self.now)
        else { Issue.record("expected .degrade after expiry"); return }
    }

    @Test func theLatchSurvivesAcrossSourceInstances() {
        // The persistence half (DegradedLatch). A latch held only in a view model is reset by
        // every back-navigation, which is exactly the re-probe-on-every-open it exists to stop.
        let store = InMemoryKeyValueStore()
        #expect(DegradedLatch(store: store).until == nil)
        DegradedLatch(store: store).until = Self.now.addingTimeInterval(3600)
        #expect(DegradedLatch(store: store).until == Self.now.addingTimeInterval(3600))
        #expect(store.get(DegradedLatch.key) != nil)
        #expect(DegradedLatch.key.hasPrefix("FitrahTube."))
    }

    @Test func aTransportErrorIsSurfacedNotDegraded() {
        // Degraded mode answers a BLOCK, not a flaky network. An offline user gets the error state and
        // a Retry, which is honest; silently showing 15 Atom items would read as "this channel has 15
        // videos" forever.
        #expect(BrowseFallback.decide(URLError(.timedOut), latchedUntil: nil, now: Self.now) == .surfaceError)
        #expect(BrowseFallback.decide(BrowseError.malformed, latchedUntil: nil, now: Self.now) == .surfaceError)
    }
}
