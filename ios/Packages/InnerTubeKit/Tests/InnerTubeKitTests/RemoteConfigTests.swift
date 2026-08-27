import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct RemoteConfigTests {
    @Test func bundledDefaultDecodesWithConfirmedClientTable() {
        let config = RemoteConfig.bundledDefault
        #expect(config.resolverOrder == ["visionosHLS", "androidItag18", "embed"])
        #expect(config.schemaVersion == 1)
        #expect(config.minAppVersion == "1.0.0")
        #expect(config.manifestCacheSeconds == 3600)
        #expect(config.clients["visionos"]?.clientNameId == 101)
        #expect(config.clients["android"]?.userAgent == "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
        #expect(config.clients["web"]?.userAgent == nil)
    }

    /// OWNER DIRECTIVE 2026-08-27: the app never hands off to YouTube. `openInYouTube` is no longer
    /// a known strategy, so a published config that still lists it -- an older config, or one
    /// authored before the directive -- must have that rung DROPPED rather than honoured. This is
    /// the same drop-and-continue filter unknown strategies already went through.
    @Test func refreshDropsAPublishedOpenInYouTubeRung() async {
        let body = Data(
            """
            {"schemaVersion":1,"minAppVersion":"1.0.0","resolverOrder":["visionosHLS","openInYouTube","embed"],"manifestCacheSeconds":3600,"clients":{}}
            """.utf8)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: body))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        #expect(await store.current().resolverOrder == ["visionosHLS", "embed"])
    }

    @Test func refreshDropsUnknownResolverStrategy() async {
        let body = Data(
            """
            {"schemaVersion":1,"minAppVersion":"1.0.0","resolverOrder":["visionosHLS","magic","embed"],"manifestCacheSeconds":3600,"clients":{}}
            """.utf8)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: body))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        let config = await store.current()
        #expect(config.resolverOrder == ["visionosHLS", "embed"])
    }

    @Test func oversizedFetchIsRejectedKeepingLastGood() async {
        let goodBody = Data(
            """
            {"schemaVersion":2,"minAppVersion":"1.0.0","resolverOrder":["embed"],"manifestCacheSeconds":60,"clients":{}}
            """.utf8)
        // Well-formed (fully decodable) RemoteConfig padded past 64 KiB via one client's
        // oversized userAgent — NOT non-JSON garbage. Garbage would fail to decode regardless of
        // check ordering, so it can't catch a "decode before size check" regression; this can.
        let oversizedConfig = RemoteConfig(
            schemaVersion: 3, minAppVersion: "1.0.0", resolverOrder: ["embed"], manifestCacheSeconds: 60,
            clients: [
                "padding": ClientContext(
                    clientName: "PADDING", clientVersion: "1", clientNameId: 1,
                    userAgent: String(repeating: "a", count: 64 * 1024 + 1024))
            ])
        let oversizedBody = try! JSONEncoder().encode(oversizedConfig)
        #expect(oversizedBody.count > RemoteConfigStore.maxBodyBytes)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: goodBody))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        #expect(await store.current().schemaVersion == 2)

        let oversizedTransport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: oversizedBody))
        ])
        let store2 = RemoteConfigStore(
            transport: oversizedTransport, keyValueStore: keyValueStoreWithLastGood(goodBody),
            url: URL(string: "https://example.com/remote-config.json")!)
        await store2.refresh()
        #expect(await store2.current().schemaVersion == 2)
    }

    @Test func refreshDropsClientWithMismatchedClientName() async {
        // "visionos" renamed to something other than the expected "VISIONOS" — bad remote data,
        // must drop-and-continue (same pattern as the unknown-resolver-strategy filter above)
        // rather than crash `PlayerRequestBuilder.build`'s family/context precondition.
        let config = RemoteConfig(
            schemaVersion: 1, minAppVersion: "1.0.0", resolverOrder: ["visionosHLS"], manifestCacheSeconds: 3600,
            clients: [
                "visionos": ClientContext(clientName: "RENAMED_CLIENT", clientVersion: "1", clientNameId: 101),
                "android": ClientContext(clientName: "ANDROID", clientVersion: "1", clientNameId: 3),
            ])
        let body = try! JSONEncoder().encode(config)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: body))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        let sanitized = await store.current()
        #expect(sanitized.clients["visionos"] == nil)
        #expect(sanitized.clients["android"] != nil)
    }

    @Test func malformedFetchLeavesCurrentAtBundledDefault() async {
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: Data("not json".utf8)))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        let config = await store.current()
        #expect(config == RemoteConfig.bundledDefault)
    }

    @Test func semVerComparesNumericSegmentsNotLexicographically() {
        #expect(SemVer.compare("1.0.0", "1.0.10") == .orderedAscending)
        #expect(SemVer.compare("1.2.0", "1.10.0") == .orderedAscending)
    }

    @Test func semVerTreatsMissingTrailingSegmentsAsZero() {
        #expect(SemVer.compare("1.0", "1.0.0") == .orderedSame)
    }

    @Test func requiresUpdateComparesAppVersionAgainstMinAppVersion() {
        var config = RemoteConfig.bundledDefault
        config.minAppVersion = "1.5.0"

        #expect(config.requiresUpdate(appVersion: "1.4.9") == true)
        #expect(config.requiresUpdate(appVersion: "1.5.0") == false)
        #expect(config.requiresUpdate(appVersion: "1.5.1") == false)
    }
}

private func keyValueStoreWithLastGood(_ data: Data) -> InMemoryKeyValueStore {
    let store = InMemoryKeyValueStore()
    store.set(RemoteConfigStore.lastGoodKey, data)
    return store
}
