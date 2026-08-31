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
    /// The consumer MUST call `remoteConfig.refresh()` (or the app never fetches an updated config);
    /// until it does, `current()` returns the bundled default forever — by design, but load-bearing.
    public let remoteConfig: RemoteConfigStore

    /// ONE instance for the app's lifetime. `SystemClock` measures elapsed time from a baseline
    /// captured at its own init, so a second one built app-side would hand `ExtractionRateLimiter`
    /// a fresh zero and reset every interval it enforces -- public so consumers reuse THIS clock
    /// instead of making that mistake.
    public let clock: SystemClock

    public init(
        keyValueStore: KeyValueStore,
        availabilityGate: AvailabilityGate,
        locale: InnerTubeLocale,
        remoteConfigURL: URL
    ) {
        let clock = SystemClock()
        self.clock = clock

        remoteConfig = RemoteConfigStore(
            transport: URLSessionTransport(), keyValueStore: keyValueStore, url: remoteConfigURL)

        let sessionStore = SessionStore(monotonicClock: clock, wallClock: clock, keyValueStore: keyValueStore)
        let manifestCache = ManifestCache(remoteConfig: remoteConfig)

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
