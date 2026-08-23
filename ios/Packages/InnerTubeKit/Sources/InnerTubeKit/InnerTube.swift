import Foundation

/// The composition root the app uses (`ios-app-plan.md` §6.1): wires every actor's concrete
/// dependencies — a dedicated `URLSessionTransport` per pipeline (resolver, browse, remote
/// config, atom feed) and a shared `SystemClock` — and exposes only the entry points a consumer
/// needs. Dumb wiring only; no logic of its own.
public struct InnerTube: Sendable {
    public let resolver: StreamResolver
    public let browse: BrowseClient
    public let atom: AtomFeedFetcher
    public let rateLimiter: ExtractionRateLimiter
    public let remoteConfig: RemoteConfigStore

    public init(
        keyValueStore: KeyValueStore,
        availabilityGate: AvailabilityGate,
        locale: InnerTubeLocale,
        remoteConfigURL: URL
    ) {
        let clock = SystemClock()

        remoteConfig = RemoteConfigStore(
            transport: URLSessionTransport(), keyValueStore: keyValueStore, url: remoteConfigURL)

        let sessionStore = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: keyValueStore)
        // ponytail: TTL pinned to the bundled default at wiring time — `init` is sync and
        // RemoteConfigStore's live value needs `await`. Re-wire once ManifestCache accepts a
        // live TTL source instead of a fixed Int at construction.
        let manifestCache = ManifestCache(configTTLSeconds: RemoteConfig.bundledDefault.manifestCacheSeconds)

        resolver = StreamResolver(
            transport: URLSessionTransport(),
            remoteConfigStore: remoteConfig,
            sessionStore: sessionStore,
            cache: manifestCache,
            gate: availabilityGate,
            monotonicClock: clock,
            wallClock: clock,
            locale: locale
        )

        browse = BrowseClient(
            transport: URLSessionTransport(),
            remoteConfigStore: remoteConfig,
            sessionStore: sessionStore,
            locale: locale
        )

        atom = AtomFeedFetcher(transport: URLSessionTransport(), keyValueStore: keyValueStore)

        rateLimiter = ExtractionRateLimiter()
    }
}
