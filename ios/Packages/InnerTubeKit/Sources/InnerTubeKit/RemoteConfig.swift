import Foundation

/// Per-client-family InnerTube request context (spec §6.13). Fields absent for
/// a given family (e.g. `web` has no `userAgent`/device fields) decode as `nil`.
public struct ClientContext: Sendable, Codable, Equatable {
    public var clientName: String
    public var clientVersion: String
    public var clientNameId: Int
    public var deviceMake: String?
    public var deviceModel: String?
    public var osName: String?
    public var osVersion: String?
    public var androidSdkVersion: Int?
    public var userAgent: String?

    public init(
        clientName: String,
        clientVersion: String,
        clientNameId: Int,
        deviceMake: String? = nil,
        deviceModel: String? = nil,
        osName: String? = nil,
        osVersion: String? = nil,
        androidSdkVersion: Int? = nil,
        userAgent: String? = nil
    ) {
        self.clientName = clientName
        self.clientVersion = clientVersion
        self.clientNameId = clientNameId
        self.deviceMake = deviceMake
        self.deviceModel = deviceModel
        self.osName = osName
        self.osVersion = osVersion
        self.androidSdkVersion = androidSdkVersion
        self.userAgent = userAgent
    }
}

/// Data-only remote config (spec §6.13): parameter tweaks (versions, UA, order
/// among shipped strategies) land here without an App Store submission. Never
/// contains code — only strings and orderings consumed by bundled logic.
public struct RemoteConfig: Sendable, Codable, Equatable {
    public var schemaVersion: Int
    public var minAppVersion: String
    public var resolverOrder: [String]
    public var manifestCacheSeconds: Int
    public var clients: [String: ClientContext]
    /// RULING 63: the Featured pseudo-category's Firestore id, published rather than compiled in.
    /// Optional on purpose -- a persisted last-known-good config from before this field must still
    /// decode (`bundledDefault` fatalErrors on a decode failure); nil means the app's bundled id.
    public var featuredCategoryId: String?

    public init(
        schemaVersion: Int,
        minAppVersion: String,
        resolverOrder: [String],
        manifestCacheSeconds: Int,
        clients: [String: ClientContext],
        featuredCategoryId: String? = nil
    ) {
        self.schemaVersion = schemaVersion
        self.minAppVersion = minAppVersion
        self.resolverOrder = resolverOrder
        self.manifestCacheSeconds = manifestCacheSeconds
        self.clients = clients
        self.featuredCategoryId = featuredCategoryId
    }
}

extension RemoteConfig {
    /// Strategy names the bundled resolver ladder actually implements (spec
    /// §6.13: "unknown strategy names are dropped").
    ///
    /// `openInYouTube` is deliberately ABSENT (owner directive 2026-08-27: the app never redirects
    /// or hands off to YouTube). Leaving it out of this set is what makes `sanitized(_:)` DROP the
    /// rung from any published config that still lists it -- the ban survives a remote config the
    /// app did not author.
    public static let knownResolverStrategies: Set<String> = [
        "visionosHLS", "androidItag18", "embed",
    ]

    /// Loaded once from the bundled `remote-config-default.json` (Task 1's frozen client table).
    public static let bundledDefault: RemoteConfig = {
        guard
            let url = Bundle.module.url(forResource: "remote-config-default", withExtension: "json"),
            let data = try? Data(contentsOf: url),
            let config = try? JSONDecoder().decode(RemoteConfig.self, from: data)
        else {
            fatalError("remote-config-default.json is missing or malformed in the InnerTubeKit bundle")
        }
        return config
    }()

    /// True if `appVersion` is older than `minAppVersion` (numeric segment
    /// compare; spec §6.13's "update required" gate).
    public func requiresUpdate(appVersion: String) -> Bool {
        SemVer.compare(appVersion, minAppVersion) == .orderedAscending
    }
}

/// Numeric-segment version comparator: "1.0.10" > "1.0.9", unlike a lexicographic compare.
public enum SemVer {
    public static func compare(_ lhs: String, _ rhs: String) -> ComparisonResult {
        let lhsParts = segments(lhs)
        let rhsParts = segments(rhs)
        for i in 0..<max(lhsParts.count, rhsParts.count) {
            let l = i < lhsParts.count ? lhsParts[i] : 0
            let r = i < rhsParts.count ? rhsParts[i] : 0
            if l != r { return l < r ? .orderedAscending : .orderedDescending }
        }
        return .orderedSame
    }

    private static func segments(_ version: String) -> [Int] {
        version.split(separator: ".").map { Int($0) ?? 0 }
    }
}

/// Injected key-value persistence for the remote config's last-known-good
/// copy. `UserDefaults`-backed in the app; in-memory in tests.
public protocol KeyValueStore: Sendable {
    func get(_ key: String) -> Data?
    func set(_ key: String, _ value: Data)
}

/// Serves the freshest available `RemoteConfig`: a successful fetch this
/// session, else the last-known-good persisted copy, else the bundled default
/// (spec §6.13).
public actor RemoteConfigStore {
    static let lastGoodKey = "InnerTubeKit.RemoteConfig.lastGood"
    static let maxBodyBytes = 64 * 1024

    private let transport: HTTPTransport
    private let keyValueStore: KeyValueStore
    private let url: URL
    private var lastGood: RemoteConfig?
    private var fetched: RemoteConfig?

    public init(transport: HTTPTransport, keyValueStore: KeyValueStore, url: URL) {
        self.transport = transport
        self.keyValueStore = keyValueStore
        self.url = url
        if let data = keyValueStore.get(Self.lastGoodKey),
            let decoded = try? JSONDecoder().decode(RemoteConfig.self, from: data)
        {
            lastGood = Self.sanitized(decoded)
        }
    }

    public func current() -> RemoteConfig {
        fetched ?? lastGood ?? .bundledDefault
    }

    public func refresh() async {
        guard
            let response = try? await transport.send(HTTPRequest(method: "GET", url: url, headers: [:], body: nil)),
            response.status == 200,
            response.body.count <= Self.maxBodyBytes,
            let decoded = try? JSONDecoder().decode(RemoteConfig.self, from: response.body)
        else {
            return
        }
        let sanitized = Self.sanitized(decoded)
        fetched = sanitized
        lastGood = sanitized
        if let data = try? JSONEncoder().encode(sanitized) {
            keyValueStore.set(Self.lastGoodKey, data)
        }
    }

    /// The `clientName` each known `clients[key]` must carry (mirrors `ClientFamily.expectedClientName`).
    private static let expectedClientNames: [String: String] = [
        "visionos": "VISIONOS", "android": "ANDROID", "web": "WEB",
    ]

    private static func sanitized(_ config: RemoteConfig) -> RemoteConfig {
        var config = config
        // Cubic #10: dedupe (first occurrence wins -- a duplicated rung is a whole extra ladder walk
        // per resolve) and cap the ladder at 8 rungs (unobservable today with 3 known strategies;
        // belt for a future, larger strategy table).
        var seen = Set<String>()
        config.resolverOrder = Array(
            config.resolverOrder
                .filter { RemoteConfig.knownResolverStrategies.contains($0) && seen.insert($0).inserted }
                .prefix(8))
        // Floor at 0: "0 = no cache" is a legitimate published choice; a negative value back-dates
        // every entry's expiry in `ManifestCache.put`, silently disabling the cache.
        config.manifestCacheSeconds = max(0, config.manifestCacheSeconds)
        // Bad remote data must drop-and-continue (same pattern as `resolverOrder` above), not crash
        // `PlayerRequestBuilder.build`'s family/context match — a renamed clientName just loses that
        // rung (`StreamResolver`'s `guard let context = config.clients[…]` already yields `.advance`).
        config.clients = config.clients.filter { key, context in
            guard let expected = expectedClientNames[key] else { return true }
            return context.clientName == expected
        }
        return config
    }
}
