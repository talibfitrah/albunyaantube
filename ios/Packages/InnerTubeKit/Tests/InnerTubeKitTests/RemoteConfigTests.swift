import Foundation
import Testing
@testable import InnerTubeKit

@Suite struct RemoteConfigTests {
    @Test func bundledDefaultDecodesWithConfirmedClientTable() {
        let config = RemoteConfig.bundledDefault
        // B3 embed ruling (2026-08-27): the embed rung ships DARK. The bundled default is exactly the
        // two native rungs; `embed` stays a known strategy so a PUBLISHED config can turn it on.
        #expect(config.resolverOrder == ["visionosHLS", "androidItag18"])
        #expect(config.schemaVersion == 1)
        #expect(config.minAppVersion == "1.0.0")
        #expect(config.manifestCacheSeconds == 3600)
        #expect(config.clients["visionos"]?.clientNameId == 101)
        #expect(config.clients["android"]?.userAgent == "com.google.android.youtube/21.26.364 (Linux; U; Android 11) gzip")
        #expect(config.clients["web"]?.userAgent == nil)
        #expect(config.featuredCategoryId == "itirf9pGpAvoBT5VSkEc")
    }

    /// RULING 63: `featuredCategoryId` is optional so a config persisted before the field existed
    /// (or a published one that omits it) still decodes, leaving the bundled default untouched.
    @Test func aDocumentWithoutFeaturedCategoryIdDecodesWithNil() throws {
        let body = Data(
            """
            {"schemaVersion":1,"minAppVersion":"1.0.0","resolverOrder":["visionosHLS"],"manifestCacheSeconds":3600,"clients":{}}
            """.utf8)
        let decoded = try JSONDecoder().decode(RemoteConfig.self, from: body)
        #expect(decoded.featuredCategoryId == nil)
        #expect(RemoteConfig.bundledDefault.featuredCategoryId == "itirf9pGpAvoBT5VSkEc")
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

    /// The other half of "ship dark": `embed` is not in the bundled default, but `sanitized(_:)`
    /// must still KEEP it, or the emergency lever (enable the rung from a published config) is gone.
    @Test func refreshKeepsAPublishedEmbedRung() async {
        let body = Data(
            """
            {"schemaVersion":1,"minAppVersion":"1.0.0","resolverOrder":["visionosHLS","androidItag18","embed"],"manifestCacheSeconds":3600,"clients":{}}
            """.utf8)
        let transport = FixtureTransport(routes: [
            .init(match: { _ in true }, response: .init(status: 200, headers: [:], body: body))
        ])
        let store = RemoteConfigStore(
            transport: transport, keyValueStore: InMemoryKeyValueStore(), url: URL(string: "https://example.com/remote-config.json")!)
        await store.refresh()
        #expect(await store.current().resolverOrder == ["visionosHLS", "androidItag18", "embed"])
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

    /// Plan C Task 6: the published repo-root `ios-remote-config.json` through the REAL decoder +
    /// sanitizer. The file is inert until it reaches `main`, but a typo in it is silent at runtime --
    /// `refresh()` swallows a decode failure and `current()` falls back to the bundled default, so
    /// the app looks fine and the runbook's "remote config first" rows quietly do nothing. Seeding
    /// lastGood is what runs the private `sanitized` (`RemoteConfig.swift:110-117`) without exposing
    /// it. Gated on `IOS_REMOTE_CONFIG_PATH` (set by `ios/scripts/test.sh`) AND the file existing, so
    /// a checkout without the file skips rather than fails.
    @Test(.enabled(if: publishedConfigPath != nil))
    func thePublishedRepoRootConfigSurvivesSanitizing() async throws {
        let path = try #require(publishedConfigPath)
        let store = RemoteConfigStore(
            transport: FixtureTransport(routes: []), keyValueStore: keyValueStoreWithLastGood(try Data(contentsOf: URL(filePath: path))),
            url: URL(string: "https://example.invalid/none")!)
        let config = await store.current()
        #expect(config.resolverOrder.count == 2)      // nothing dropped as an unknown strategy (embed ships dark)
        #expect(config.clients.count == 3)            // no clientName mismatch dropped a family
        #expect(config.featuredCategoryId != nil)     // ruling 63's key is present
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

/// `IOS_REMOTE_CONFIG_PATH` when it names an existing file, else nil (the test above skips).
private let publishedConfigPath: String? = ProcessInfo.processInfo.environment["IOS_REMOTE_CONFIG_PATH"]
    .flatMap { FileManager.default.fileExists(atPath: $0) ? $0 : nil }

private func keyValueStoreWithLastGood(_ data: Data) -> InMemoryKeyValueStore {
    let store = InMemoryKeyValueStore()
    store.set(RemoteConfigStore.lastGoodKey, data)
    return store
}
