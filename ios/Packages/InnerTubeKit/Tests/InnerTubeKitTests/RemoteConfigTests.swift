import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct RemoteConfigTests {
    @Test func bundledDefaultDecodesWithConfirmedClientTable() {
        let config = RemoteConfig.bundledDefault
        #expect(config.resolverOrder == ["visionosHLS", "androidItag18", "embed", "openInYouTube"])
        #expect(config.schemaVersion == 1)
        #expect(config.minAppVersion == "1.0.0")
        #expect(config.manifestCacheSeconds == 3600)
        #expect(config.clients["visionos"]?.clientNameId == 101)
        #expect(config.clients["android"]?.userAgent == "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
        #expect(config.clients["web"]?.userAgent == nil)
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
        let oversizedBody = Data(repeating: 0x41, count: 64 * 1024 + 1)
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
        #expect(SemVer.compare("1.2.0", "1.10.0") != .orderedDescending)
    }
}

private func keyValueStoreWithLastGood(_ data: Data) -> InMemoryKeyValueStore {
    let store = InMemoryKeyValueStore()
    store.set(RemoteConfigStore.lastGoodKey, data)
    return store
}
