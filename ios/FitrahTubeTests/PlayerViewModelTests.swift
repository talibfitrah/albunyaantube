import Foundation
import InnerTubeKit
import SwiftData
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct PlayerViewModelTests {
    // MARK: - Fixtures

    private static func resolved(_ stream: ResolvedStream, client: ClientFamily = .visionos) -> Resolved {
        Resolved(stream: stream, client: client, userAgent: "ua", resolvedAt: Date(), expiresAt: nil)
    }

    private static let hls = resolved(.hls(url: URL(string: "https://example.com/a.m3u8")!, isLive: false, audioOnlyURL: nil, captionTracks: []))
    private static let progressive = resolved(.progressive(url: URL(string: "https://example.com/a.mp4")!, label: "360p"))
    private static let embed = resolved(.embed(videoId: "abcdefghijk"))
    private static let openInYouTube = resolved(.openInYouTube(url: URL(string: "https://www.youtube.com/watch?v=abcdefghijk")!))

    private func makeArgs() -> PlayerArgs { PlayerArgs(videoId: "abcdefghijk", channelId: "ch1") }

    private func makeFavorites() -> SwiftDataFavoritesStore {
        let container = try! ModelContainer(for: FavoriteVideo.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        return SwiftDataFavoritesStore(modelContainer: container)
    }

    private func makeSettings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "PlayerViewModelTests.\(UUID().uuidString)")!)
    }

    private func makeViewModel(resolver: FakeResolver, args: PlayerArgs? = nil) -> PlayerViewModel {
        PlayerViewModel(resolver: resolver, catalog: FakeCatalogClient(), favorites: makeFavorites(),
                         settings: makeSettings(), args: args ?? makeArgs())
    }

    /// Scripts a queue of `resolve` outcomes and records each call's params. `gatedCallIndex`
    /// (1-based) makes that one call suspend on `gate` until released -- for the supersede test.
    private actor FakeResolver: StreamResolving {
        private let outcomes: [Result<Resolved, Error>]
        private(set) var calls: [(purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool)] = []
        private let gate: Gate?
        private let gatedCallIndex: Int

        init(outcomes: [Result<Resolved, Error>], gate: Gate? = nil, gatedCallIndex: Int = 0) {
            self.outcomes = outcomes
            self.gate = gate
            self.gatedCallIndex = gatedCallIndex
        }

        var callCount: Int { calls.count }

        func resolve(_ videoId: String, purpose: Purpose, sourceChannelId: String?, forceRefresh: Bool) async throws -> Resolved {
            calls.append((purpose, sourceChannelId, forceRefresh))
            // The outcome is picked by *registration* order (this call's index), not removed off a
            // shared queue after the gate -- otherwise a later, ungated call racing past a blocked
            // earlier one would steal the earlier call's scripted outcome.
            let index = calls.count - 1
            if calls.count == gatedCallIndex, let gate { await gate.block() }
            guard outcomes.indices.contains(index) else { fatalError("FakeResolver ran out of scripted outcomes") }
            switch outcomes[index] {
            case .success(let resolved): return resolved
            case .failure(let error): throw error
            }
        }
    }

    // MARK: - Outcome -> state mapping (player.md §2.2)

    @Test func hlsMapsToReady() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.hls)]))
        await vm.open()
        #expect(vm.state == .ready(Self.hls))
    }

    @Test func progressiveMapsToRung2Progressive() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.progressive)]))
        await vm.open()
        #expect(vm.state == .rung2Progressive(Self.progressive))
    }

    @Test func embedMapsToGenericErrorPendingB3() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.embed)]))
        await vm.open()
        #expect(vm.state == .error(messageKey: "player_error_generic"))
    }

    @Test func openInYouTubeMapsToGenericErrorPendingB3() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.openInYouTube)]))
        await vm.open()
        #expect(vm.state == .error(messageKey: "player_error_generic"))
    }

    @Test func unavailableMapsToContentUnavailable() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.failure(ExtractionError.unavailable(videoId: "abcdefghijk"))]))
        await vm.open()
        #expect(vm.state == .contentUnavailable)
    }

    @Test func liveOfflineMapsToContentUnavailable() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.failure(ExtractionError.liveOffline(startsAt: nil))]))
        await vm.open()
        #expect(vm.state == .contentUnavailable)
    }

    @Test(arguments: [ExtractionError.ageRestricted, .geoBlocked, .private, .removed])
    func terminalContentErrorsMapToContentUnavailable(_ error: ExtractionError) async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.failure(error)]))
        await vm.open()
        #expect(vm.state == .contentUnavailable)
    }

    @Test func cooldownMapsToCooldownState() async {
        let until = Date().addingTimeInterval(60)
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.failure(ExtractionError.cooldown(until: until))]))
        await vm.open()
        #expect(vm.state == .cooldown(until: until))
    }

    @Test(arguments: [ExtractionError.invalidVideoId, .botCheck, .allRungsFailed, .cancelled, .transport("boom")])
    func otherErrorsMapToGenericError(_ error: ExtractionError) async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.failure(error)]))
        await vm.open()
        #expect(vm.state == .error(messageKey: "player_error_message"))
    }

    // MARK: - CF-B1: cooldown never retries into itself

    @Test func retryIsANoOpWhileStillInsideTheCooldownWindow() async {
        let until = Date().addingTimeInterval(60)
        let resolver = FakeResolver(outcomes: [.failure(ExtractionError.cooldown(until: until))])
        let vm = makeViewModel(resolver: resolver)
        await vm.open()
        #expect(vm.state == .cooldown(until: until))

        await vm.retry()

        #expect(vm.state == .cooldown(until: until)) // unchanged
        #expect(await resolver.callCount == 1) // no second network call
    }

    @Test func retryProceedsOnceTheCooldownHasElapsed() async {
        let until = Date().addingTimeInterval(-1) // already elapsed
        let resolver = FakeResolver(outcomes: [.failure(ExtractionError.cooldown(until: until)), .success(Self.hls)])
        let vm = makeViewModel(resolver: resolver)
        await vm.open()
        #expect(vm.state == .cooldown(until: until))

        await vm.retry()

        #expect(vm.state == .ready(Self.hls))
        #expect(await resolver.callCount == 2)
    }

    // MARK: - retry() forces a refresh

    @Test func retryPassesForceRefreshTrue() async {
        let resolver = FakeResolver(outcomes: [.success(Self.hls), .success(Self.hls)])
        let vm = makeViewModel(resolver: resolver)
        await vm.open()
        await vm.retry()

        let calls = await resolver.calls
        #expect(calls.map(\.forceRefresh) == [false, true])
        #expect(calls.map(\.purpose) == [.player, .player])
        #expect(calls.allSatisfy { $0.sourceChannelId == "ch1" })
    }

    // MARK: - generation guard: a superseded open() keeps only the latest result

    @Test func supersededOpenKeepsOnlyTheLatestResult() async {
        let gate = Gate()
        let resolver = FakeResolver(outcomes: [.success(Self.progressive), .success(Self.hls)], gate: gate, gatedCallIndex: 1)
        let vm = makeViewModel(resolver: resolver)

        let firstOpen = Task { await vm.open() }
        await gate.waitUntilBlocked() // the first resolve call has genuinely suspended

        await vm.open() // supersedes the first: cancels its job, bumps the generation
        #expect(vm.state == .ready(Self.hls))

        await gate.release()
        await firstOpen.value // let the stale first call drain

        #expect(vm.state == .ready(Self.hls)) // unchanged by the late first-call completion
    }
}
