import Foundation
import InnerTubeKit
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

    /// `Self.hls` carries `expiresAt: nil`, which `shouldPreemptivelyReResolve` reads as "never
    /// expires" -- the silent-refresh test below needs a stream that actually does expire.
    private static func expiringHLS(now: Date = Date()) -> Resolved {
        Resolved(stream: .hls(url: URL(string: "https://example.com/a.m3u8")!, isLive: false,
                              audioOnlyURL: nil, captionTracks: []),
                 client: .visionos, userAgent: "ua", resolvedAt: now, expiresAt: now.addingTimeInterval(30))
    }

    private func makeArgs() -> PlayerArgs { PlayerArgs(videoId: "abcdefghijk", channelId: "ch1") }

    private func makeSettings() -> UserDefaultsSettingsStore {
        UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "PlayerViewModelTests.\(UUID().uuidString)")!)
    }

    private func makeViewModel(resolver: FakeResolver, args: PlayerArgs? = nil,
                               safeMode: Bool = true) -> PlayerViewModel {
        let settings = makeSettings()
        settings.safeMode = safeMode     // the store's own default is already true (Android parity)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args ?? makeArgs())
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

        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind,
                     sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool) async throws -> Resolved {
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

    @Test func embedMapsToTheEmbedState() async {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.embed)]))
        await vm.open()
        #expect(vm.state == .embed(Self.embed))
    }

    /// OWNER DIRECTIVE 2026-08-27: there is no hand-off rung, in EITHER Safe Mode setting. The
    /// ladder's floor is the embed, and the embed is what Safe Mode must keep -- it is the rung that
    /// holds a child inside the app. (The resolver can no longer even emit a hand-off: the enum case
    /// is gone, which is what makes this test's `for` loop exhaustive rather than illustrative.)
    @Test func theLadderNeverProducesAHandOffInEitherSafeModeState() async {
        for safeMode in [false, true] {
            let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(Self.embed)]), safeMode: safeMode)
            await vm.open()
            #expect(vm.state == .embed(Self.embed), "safeMode: \(safeMode)")
        }
    }

    @Test func aSilentRefreshNeverSwapsAPlayingStreamIntoTheEmbed() async {
        // CF-B2-3 + plan §6.6: `reResolveIfExpiring` shows the user nothing, so it must not be able to
        // change the playback SURFACE. The near-expiry rung-1 stream keeps playing; reactive recovery
        // (which never passes `silent:`) owns the demotion, loudly, once the stream actually fails.
        let expiring = Self.expiringHLS()
        let vm = makeViewModel(resolver: FakeResolver(outcomes: [.success(expiring), .success(Self.embed)]),
                               safeMode: false)
        await vm.open()
        #expect(vm.state == .ready(expiring))
        await vm.reResolveIfExpiring(now: .distantFuture)   // well past `expiresAt - margin`, so it fires
        #expect(vm.state == .ready(expiring))               // embed result dropped; rung 1 still playing
    }

    @Test func embedActionsMapOntoTerminalStates() {
        let vm = makeViewModel(resolver: FakeResolver(outcomes: []), safeMode: false)
        vm.applyEmbedAction(.fail(messageKey: "player_embed_removed"))
        #expect(vm.state == .error(messageKey: "player_embed_removed"))
        vm.applyEmbedAction(.fail(messageKey: "player_stream_unavailable"))
        #expect(vm.state == .error(messageKey: "player_stream_unavailable"))
        // CF-B3-10: terminal embed errors land on `.unplayable` (no Retry), never on `.error`.
        vm.applyEmbedAction(.unplayable(messageKey: "player_embed_removed"))
        #expect(vm.state == .unplayable(messageKey: "player_embed_removed"))
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

    // MARK: - Ruling 34: the Settings "Audio only" value seeds a session-only toggle

    @Test func audioOnlyIsSeededFromTheSettingAndIsSessionOnly() {
        let settings = makeSettings()
        settings.audioOnly = true
        let model = PlayerViewModel(resolver: FakeResolver(outcomes: []), settings: settings, args: makeArgs())
        #expect(model.audioOnly == true)

        model.audioOnly = false

        #expect(settings.audioOnly == true)   // ruling 34 seeds; it never writes back (player.md §5)
    }

    @Test func backgroundPlayIsReadLiveFromTheSetting() {
        let settings = makeSettings()
        let model = PlayerViewModel(resolver: FakeResolver(outcomes: []), settings: settings, args: makeArgs())
        #expect(model.backgroundPlay == true)   // store default

        settings.backgroundPlay = false

        #expect(model.backgroundPlay == false)  // read live, not captured at init
    }

    @Test func audioOnlyIsOfferedOnlyWhenTheResolvedStreamHasAnAudioTrack() {
        let video = URL(string: "https://manifest.googlevideo.com/x.m3u8")!
        let withAudio = Self.resolved(.hls(url: video, isLive: false,
                                           audioOnlyURL: URL(string: "https://r1.example.com/a140")!, captionTracks: []))
        #expect(PlayerViewModel.audioOnlyAvailable(for: .ready(withAudio)) == true)
        #expect(PlayerViewModel.audioOnlyAvailable(for: .ready(Self.hls)) == false)   // rung 1, no itag 140
        #expect(PlayerViewModel.audioOnlyAvailable(for: .rung2Progressive(Self.progressive)) == false)
        #expect(PlayerViewModel.audioOnlyAvailable(for: .loading) == false)
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

    // MARK: - CF-B1-2: the rate-limiter gate (Task 6)

    private func makeViewModel(resolver: any StreamResolving) -> PlayerViewModel {
        PlayerViewModel(resolver: resolver, settings: makeSettings(), args: makeArgs())
    }

    @Test func aBlockedForceRefreshBecomesACooldownState() async {
        let clock = FixedMonotonicClock(now: .seconds(0))
        let resolver = RateLimitedResolver(wrapping: RecordingResolver(.hls),
                                           rateLimiter: ExtractionRateLimiter(), clock: clock)
        // Burn the per-video .player budget: 3 attempts in the 5-minute window, spaced past the 30s
        // minimum interval so the earlier ones are `.allowed`, not `.delayed`.
        for offset in [0, 40, 80] {
            clock.now = .seconds(offset)
            _ = try? await resolver.resolve("abc", purpose: .player, kind: .player,
                                            sourceChannelId: nil, forceRefresh: true)
        }
        clock.now = .seconds(120)
        do {
            _ = try await resolver.resolve("abc", purpose: .player, kind: .player,
                                           sourceChannelId: nil, forceRefresh: true)
            #expect(Bool(false), "expected a cooldown")
        } catch let error as ExtractionError {
            guard case .cooldown(let until) = error else { return #expect(Bool(false), "expected .cooldown") }
            // M2: `until > Date()` passes for a one-second cooldown too. The limiter's per-video
            // retryAfter is `oldest attempt + 5 min - now` = 0 + 300 - 120 = 180 s, so pin that.
            #expect(abs(until.timeIntervalSinceNow - 180) <= 5)
        } catch { #expect(Bool(false), "wrong error type") }
    }

    @Test func anUngatedOpenIsNeverRateLimited() async throws {
        // A non-forced resolve may be a pure ManifestCache hit -- gating it would refuse a replay of a
        // video the user watched 10 seconds ago (Android gates only its three force-refresh sites).
        let inner = RecordingResolver(.hls)
        let resolver = RateLimitedResolver(wrapping: inner, rateLimiter: ExtractionRateLimiter(),
                                           clock: FixedMonotonicClock(now: .seconds(0)))
        for _ in 0..<5 {
            _ = try await resolver.resolve("abc", purpose: .player, kind: .player,
                                           sourceChannelId: nil, forceRefresh: false)
        }
        #expect(inner.calls.count == 5)   // all five reached the resolver; none was refused
    }

    @Test func theLimiterGatesThePrefetchLaneEvenWithoutForceRefresh() async throws {
        // Reconciliation note 7: a prefetch is the non-forced resolve most likely to hit the network,
        // and it is the one lane the limiter exists to keep behind the interactive one.
        let clock = FixedMonotonicClock(now: .seconds(0))
        let limited = RateLimitedResolver(wrapping: RecordingResolver(.hls),
                                          rateLimiter: ExtractionRateLimiter(), clock: clock)
        _ = try await limited.resolve("v", purpose: .prefetch, kind: .prefetch,
                                      sourceChannelId: nil, forceRefresh: false)
        await #expect(throws: ExtractionError.self) {
            _ = try await limited.resolve("v", purpose: .prefetch, kind: .prefetch,
                                          sourceChannelId: nil, forceRefresh: false)
        }
    }

    /// The limiter's own cooldown lands in `StreamState.cooldown`, which is exactly the state
    /// `retry()` already refuses to re-enter while `until` is in the future -- so the gate cannot
    /// lock the retry button against itself, and needs no unlock path of its own.
    @Test func aRateLimiterCooldownDoesNotSelfLockRetry() async {
        let clock = FixedMonotonicClock(now: .seconds(0))
        let inner = RecordingResolver(.hls)
        let vm = makeViewModel(resolver: RateLimitedResolver(wrapping: inner,
                                                             rateLimiter: ExtractionRateLimiter(), clock: clock))
        await vm.open()                                     // ungated: reaches the resolver, records nothing
        for offset in [0, 40, 80] {                         // three forced attempts: the whole per-video budget
            clock.now = .seconds(offset)
            await vm.retry()
        }
        #expect(inner.calls.count == 4)

        clock.now = .seconds(120)
        await vm.retry()                                    // refused by the limiter
        guard case .cooldown = vm.state else { return #expect(Bool(false), "expected .cooldown") }
        #expect(inner.calls.count == 4)

        await vm.retry()                                    // and the VM's own guard stops the next one dead
        #expect(inner.calls.count == 4)
    }

    @Test func recoveryReResolvesUseTheAutoRecoveryLane() async {
        let resolver = RecordingResolver(.hls)   // records every (kind, forceRefresh) pair
        let model = makeViewModel(resolver: resolver)
        await model.open()
        await model.handleRecoveryEvent(.playbackError)
        #expect(resolver.calls.first?.kind == .player)
        #expect(resolver.calls.last?.kind == .autoRecovery)
        #expect(resolver.calls.last?.forceRefresh == true)
    }

    // MARK: - CF-B1-3: the pre-emptive foreground re-resolve (Task 6)

    @Test func foregroundReResolvesOnlyWhenTheStreamIsAboutToExpire() async {
        let now = Date(timeIntervalSince1970: 1_000_000)
        let fresh = Resolved(stream: .hls(url: URL(string: "https://x/y.m3u8")!, isLive: false,
                                          audioOnlyURL: nil, captionTracks: []),
                             client: .visionos, userAgent: "UA", resolvedAt: now,
                             expiresAt: now.addingTimeInterval(600))
        let stale = Resolved(stream: fresh.stream, client: .visionos, userAgent: "UA",
                             resolvedAt: now, expiresAt: now.addingTimeInterval(30))
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(fresh), now: now) == false)
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(stale), now: now) == true)
        // M4: `.rung2Progressive` shares the playable branch, so it must answer identically -- a
        // `playable`/`resolved` helper that only matched `.ready` would leave rung 2 never refreshed.
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.rung2Progressive(fresh), now: now) == false)
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.rung2Progressive(stale), now: now) == true)
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.loading, now: now) == false)
    }

    @Test func aPreemptiveReResolveNeverLeavesThePlayableBranch() async {
        // LOAD-BEARING: `.ready` and `.rung2Progressive` share one SwiftUI branch; a `.loading` hop
        // would dismantle PlayerHostView and drop the AVPlayer carrying the position. `RecordingResolver`
        // holds its answer until released, so the state can be observed WHILE the re-resolve is in
        // flight -- the exact window a `.loading` hop would show in.
        let resolver = RecordingResolver(.hls, holdsUntilReleased: true)
        let model = makeViewModel(resolver: resolver)
        resolver.release()
        await model.open()
        let task = Task { await model.reResolveIfExpiring(now: Date.distantFuture) }
        await resolver.waitUntilCalled(count: 2)
        #expect(model.state.isPlayable)          // mid-flight: still the playable branch
        resolver.release()
        await task.value
        #expect(model.state.isPlayable)          // and after
        #expect(resolver.calls.last?.kind == .proactiveTTLRefresh)
    }

    @Test func aFailedPreemptiveReResolveLeavesTheHealthyStreamPlaying() async {
        // THE FAILURE PATH, and the reason `silent:` exists. A proactive refresh that throws (a
        // RateLimitedResolver cooldown, or a network blip on foreground) must NOT demote a player
        // that is still happily playing an unexpired stream: `state` is left exactly as it was, so
        // PlayerScreen's playable branch keeps its view identity and PlayerHostView is not rebuilt.
        for failure in [ExtractionError.cooldown(until: Date().addingTimeInterval(600)),
                        ExtractionError.transport("blip"),
                        ExtractionError.unavailable(videoId: "abc")] {
            let resolver = RecordingResolver(.hls)
            let model = makeViewModel(resolver: resolver)
            await model.open()
            guard case .ready(let opened) = model.state else { return #expect(Bool(false), "expected .ready") }

            resolver.outcome = .failure(failure)
            await model.reResolveIfExpiring(now: Date.distantFuture)

            // Same state, same associated `Resolved` -- not merely "still playable".
            guard case .ready(let after) = model.state else { return #expect(Bool(false), "state was demoted") }
            #expect(after.resolvedAt == opened.resolvedAt)
            #expect(resolver.calls.last?.kind == .proactiveTTLRefresh)
        }
    }

    /// Fix round 1, C1. A silent TTL refresh that starts while a recovery resolve is in flight
    /// bumps `generation`, so the recovery's completion is discarded -- and the refresh's OWN
    /// failure is then swallowed by the silent rule. The player is left holding a `.failed`
    /// `AVPlayerItem` inside `.ready`: frozen, no error surface, no retry. The recovery is
    /// invisible to `shouldPreemptivelyReResolve` (it deliberately runs with `showLoading: false`,
    /// so the state is still the old `.ready`), which is why the guard is `isRecovering`.
    @Test func aTTLRefreshNeverPreemptsAnInFlightRecovery() async {
        let resolver = RecordingResolver(.hls, holdsUntilReleased: true)
        let model = makeViewModel(resolver: resolver)
        resolver.release()
        await model.open()

        resolver.outcome = .progressive                 // the recovery's answer
        let recovery = Task { await model.handleRecoveryEvent(.playbackError) }
        await resolver.waitUntilCalled(count: 2)        // recovery resolve entered, now held
        resolver.outcome = .hls                         // what a pre-empting refresh would land

        let proactive = Task { await model.reResolveIfExpiring(now: .distantFuture) }
        // Released on a timer rather than inline: a guarded refresh never touches the resolver, an
        // unguarded one is sitting in the hold right now, and this frees BOTH -- so a regression
        // fails on the assertions below instead of deadlocking the suite.
        let releaser = Task {
            try? await Task.sleep(for: .milliseconds(50))
            resolver.release()
            resolver.release()
        }
        await proactive.value
        await recovery.value
        await releaser.value

        #expect(resolver.calls.map(\.kind) == [.player, .autoRecovery])
        guard case .rung2Progressive = model.state else {
            return #expect(Bool(false), "recovery result discarded, state is \(model.state)")
        }
    }

    @Test func reactiveRecoveryStillSurfacesFailures() async {
        // The other half of the rule: `silent:` must NOT leak into recovery. When the stream has
        // genuinely stopped working, a failed re-resolve is still allowed to land a failure state.
        let resolver = RecordingResolver(.hls)
        let model = makeViewModel(resolver: resolver)
        await model.open()
        resolver.outcome = .failure(.unavailable(videoId: "abc"))
        await model.handleRecoveryEvent(.playbackError)
        #expect(model.state == .contentUnavailable)
    }
}
