import Foundation
import Testing
@testable import InnerTubeKit

/// Proves `InnerTube`'s wiring holds together: construction succeeds and every exposed
/// component is reachable and actor-isolated calls against it work. Network-free — `resolver`,
/// `browse`, and `atom` all resolve to real `URLSessionTransport`s inside `InnerTube` (there is
/// no injection seam in its public init, by design: it's the app's composition root, not a test
/// double), so only the two collaborators that never touch the network (`remoteConfig.current()`,
/// `rateLimiter.check`) are exercised for behaviour; the rest are checked for reachability only.
@Suite struct InnerTubeTests {
    private struct StubGate: AvailabilityGate {
        func verify(videoId: String, sourceChannelId: String?) async throws -> Bool { true }
    }

    @Test func composesAndExposesComponents() async throws {
        let innerTube = InnerTube(
            keyValueStore: InMemoryKeyValueStore(),
            availabilityGate: StubGate(),
            locale: InnerTubeLocale(hl: "en", gl: "US"),
            remoteConfigURL: URL(string: "https://example.com/remote-config.json")!
        )

        let resolver: StreamResolver = innerTube.resolver
        let browse: BrowseClient = innerTube.browse
        let atom: AtomFeedFetcher = innerTube.atom
        _ = resolver
        _ = browse
        _ = atom

        // No refresh() was called, so this proves the transport/keyValueStore/url wiring holds
        // without a network call: `current()` falls through to the bundled default.
        let config = await innerTube.remoteConfig.current()
        #expect(config.resolverOrder == RemoteConfig.bundledDefault.resolverOrder)

        let decision = await innerTube.rateLimiter.check("dQw4w9WgXcQ", kind: .player, now: .zero)
        #expect(decision == .allowed)
    }
}
