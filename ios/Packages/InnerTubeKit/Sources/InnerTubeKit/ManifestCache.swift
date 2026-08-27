import Foundation

/// Memory-only cache of resolved streams, keyed by videoId (spec §9,
/// `extraction.md` §3.2). Live results are never stored. Capacity 50 with
/// LRU eviction, where recency is tracked by `get` only (a `put` on an
/// existing key updates the value without bumping its position).
public actor ManifestCache {
    private static let capacity = 50
    private static let maxTTLSeconds: TimeInterval = 3600

    private struct Entry {
        var resolved: Resolved
        var expiresAt: Date
    }

    private let remoteConfig: RemoteConfigStore

    /// Oldest → newest by last `get`; new keys are appended on `put`.
    private var order: [String] = []
    private var entries: [String: Entry] = [:]

    public init(remoteConfig: RemoteConfigStore) {
        self.remoteConfig = remoteConfig
    }

    public func get(_ videoId: String, now: Date) -> Resolved? {
        guard let entry = entries[videoId] else { return nil }
        guard now < entry.expiresAt else {
            entries.removeValue(forKey: videoId)
            order.removeAll { $0 == videoId }
            return nil
        }
        order.removeAll { $0 == videoId }
        order.append(videoId)
        return entry.resolved
    }

    public func put(_ resolved: Resolved, videoId: String, now: Date) async {
        guard !isLive(resolved) else { return }

        // Clamp to the stream's real URL expiry: never serve past `resolved.expiresAt`, even if the
        // TTL is longer (a short googlevideo `expiresInSeconds` would otherwise cache a dead URL).
        let expiresAt = min(now.addingTimeInterval(await ttlSeconds()), resolved.expiresAt ?? .distantFuture)
        let isNewKey = entries[videoId] == nil
        entries[videoId] = Entry(resolved: resolved, expiresAt: expiresAt)

        guard isNewKey else { return }
        order.append(videoId)
        if order.count > Self.capacity {
            let evicted = order.removeFirst()
            entries.removeValue(forKey: evicted)
        }
    }

    public func invalidate(_ videoId: String) {
        entries.removeValue(forKey: videoId)
        order.removeAll { $0 == videoId }
    }

    public func flushAll() {
        entries.removeAll()
        order.removeAll()
    }

    private func isLive(_ resolved: Resolved) -> Bool {
        if case .hls(_, let isLive, _, _) = resolved.stream {
            return isLive
        }
        return false
    }

    /// CF-B1-11: read live, per-`put`, so a published config change takes effect without a
    /// relaunch (the TTL used to be pinned to the bundled default at construction).
    private func ttlSeconds() async -> TimeInterval {
        // ponytail: `Resolved` carries no video duration yet, so the
        // expiresAt-minus-duration term from spec §9 is never computable
        // here; wire it in once PlayerResponseParser threads duration through.
        min(TimeInterval(await remoteConfig.current().manifestCacheSeconds), Self.maxTTLSeconds)
    }
}
