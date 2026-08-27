import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct ManifestCacheTests {
    /// A cache whose TTL comes from a *published* remote config carrying `manifestCacheSeconds`
    /// -- the live source CF-B1-11 asks for, not the bundled default pinned at construction.
    private func makeCache(configTTLSeconds: Int) async -> ManifestCache {
        let body = Data(
            """
            {"schemaVersion":1,"minAppVersion":"1.0.0","resolverOrder":["visionosHLS"],"manifestCacheSeconds":\(configTTLSeconds),"clients":{}}
            """.utf8)
        let store = RemoteConfigStore(
            transport: FixtureTransport(routes: [
                .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: body))
            ]),
            keyValueStore: InMemoryKeyValueStore(),
            url: URL(string: "https://example.com/config.json")!)
        await store.refresh()
        return ManifestCache(remoteConfig: store)
    }

    private func hlsResolved(isLive: Bool = false) -> Resolved {
        Resolved(
            stream: .hls(url: URL(string: "https://example.com/manifest.m3u8")!, isLive: isLive, audioOnlyURL: nil, captionTracks: []),
            client: .visionos,
            userAgent: "ua",
            resolvedAt: Date(timeIntervalSince1970: 0),
            expiresAt: nil
        )
    }

    @Test func putThenGetWithinTTLIsHitThenMissAfterTTL() async {
        let cache = await makeCache(configTTLSeconds: 100)
        let now = Date(timeIntervalSince1970: 1000)

        await cache.put(hlsResolved(), videoId: "abc123def45", now: now)
        #expect(await cache.get("abc123def45", now: now.addingTimeInterval(50)) != nil)

        #expect(await cache.get("abc123def45", now: now.addingTimeInterval(101)) == nil)
    }

    @Test func liveHLSIsNeverStored() async {
        let cache = await makeCache(configTTLSeconds: 100)
        let now = Date(timeIntervalSince1970: 1000)

        await cache.put(hlsResolved(isLive: true), videoId: "live12345678", now: now)
        #expect(await cache.get("live12345678", now: now) == nil)
    }

    @Test func lruEvictsLeastRecentlyGotten() async {
        let cache = await makeCache(configTTLSeconds: 10_000)
        let now = Date(timeIntervalSince1970: 1000)

        for i in 0..<50 {
            await cache.put(hlsResolved(), videoId: String(format: "vid%08d", i), now: now)
        }

        // Touch vid00000000 so it's most-recently-used; vid00000001 stays least-recently-got.
        #expect(await cache.get("vid00000000", now: now) != nil)

        // 51st put evicts the least-recently-got entry.
        await cache.put(hlsResolved(), videoId: "vid00000050", now: now)

        #expect(await cache.get("vid00000001", now: now) == nil)
        #expect(await cache.get("vid00000000", now: now) != nil)
        #expect(await cache.get("vid00000050", now: now) != nil)
    }

    @Test func ttlClampsConfigAbove3600ButPassesLowerConfigThrough() async {
        let now = Date(timeIntervalSince1970: 1000)

        let clamped = await makeCache(configTTLSeconds: 7200)
        await clamped.put(hlsResolved(), videoId: "clamp0000001", now: now)
        #expect(await clamped.get("clamp0000001", now: now.addingTimeInterval(3599)) != nil)
        #expect(await clamped.get("clamp0000001", now: now.addingTimeInterval(3601)) == nil)

        let unclamped = await makeCache(configTTLSeconds: 1800)
        await unclamped.put(hlsResolved(), videoId: "short0000001", now: now)
        #expect(await unclamped.get("short0000001", now: now.addingTimeInterval(1799)) != nil)
        #expect(await unclamped.get("short0000001", now: now.addingTimeInterval(1801)) == nil)
    }

    @Test func expiredGetEvictsStaleEntrySoItDoesNotOccupyALRUSlot() async {
        let cache = await makeCache(configTTLSeconds: 100)
        let now = Date(timeIntervalSince1970: 1000)

        for i in 0..<49 {
            await cache.put(hlsResolved(), videoId: String(format: "vid%08d", i), now: now)
        }
        await cache.put(hlsResolved(), videoId: "stale0000001", now: now)
        // An expired `get` must remove the entry from BOTH `entries` and `order` — not just return
        // nil — else a phantom `order` slot lingers (cache now holds 50 `order` entries: 49 fresh +
        // the "removed" stale one) and the next two `put`s below trigger ONE EXTRA eviction beyond
        // the one LRU eviction they're entitled to.
        #expect(await cache.get("stale0000001", now: now.addingTimeInterval(101)) == nil)

        await cache.put(hlsResolved(), videoId: "vid00000049", now: now)
        await cache.put(hlsResolved(), videoId: "vid00000050", now: now)

        #expect(await cache.get("vid00000000", now: now) == nil)  // oldest, evicted by design
        #expect(await cache.get("vid00000001", now: now) != nil)  // fails iff the phantom slot lingered
    }

    @Test func cacheExpiryClampsToStreamExpiresAtWhenSoonerThanTTL() async {
        let cache = await makeCache(configTTLSeconds: 3600)  // 1 h TTL
        let now = Date(timeIntervalSince1970: 1000)
        let resolved = Resolved(
            stream: .hls(url: URL(string: "https://example.com/m.m3u8")!, isLive: false, audioOnlyURL: nil, captionTracks: []),
            client: .visionos, userAgent: "ua", resolvedAt: now,
            expiresAt: now.addingTimeInterval(120))  // real URL expiry 2 min << TTL

        await cache.put(resolved, videoId: "clampexpiry1", now: now)
        #expect(await cache.get("clampexpiry1", now: now.addingTimeInterval(119)) != nil)
        // Evicted at the stream's real expiry, NOT at now+3600.
        #expect(await cache.get("clampexpiry1", now: now.addingTimeInterval(121)) == nil)
    }

    @Test func flushAllEmptiesCache() async {
        let cache = await makeCache(configTTLSeconds: 100)
        let now = Date(timeIntervalSince1970: 1000)
        await cache.put(hlsResolved(), videoId: "abc123def45", now: now)

        await cache.flushAll()

        #expect(await cache.get("abc123def45", now: now) == nil)
    }

    @Test func invalidateRemovesSingleEntry() async {
        let cache = await makeCache(configTTLSeconds: 100)
        let now = Date(timeIntervalSince1970: 1000)
        await cache.put(hlsResolved(), videoId: "abc123def45", now: now)

        await cache.invalidate("abc123def45")

        #expect(await cache.get("abc123def45", now: now) == nil)
    }

    // CF-B1-11: the TTL must follow whatever the *published* config says, not the value that
    // happened to be bundled when `ManifestCache` was constructed.
    @Test func ttlFollowsThePublishedRemoteConfigNotTheBundledDefault() async {
        #expect(RemoteConfig.bundledDefault.manifestCacheSeconds == 3600)  // the value it must NOT use
        let cache = await makeCache(configTTLSeconds: 5)
        let now = Date(timeIntervalSince1970: 1_000_000)

        await cache.put(hlsResolved(), videoId: "livettl00001", now: now)

        #expect(await cache.get("livettl00001", now: now.addingTimeInterval(4)) != nil)
        #expect(await cache.get("livettl00001", now: now.addingTimeInterval(6)) == nil)
    }
}
