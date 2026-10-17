import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Spec §9.1, §9.3: the iOS 1.0.4 live refresh and live-channel mode. No network: a scripted resolver, a scripted
/// lives lookup, and an injected sleep.
@MainActor @Suite(.perTest)
struct LiveChannelPlaybackTests {

    actor RecordingResolver: StreamResolving {
        private var outcomes: [Result<Resolved, Error>]
        private(set) var videoIds: [String] = []
        private(set) var sourceChannelIds: [String?] = []

        init(_ outcomes: [Result<Resolved, Error>]) { self.outcomes = outcomes }

        func resolve(_ videoId: String, purpose: Purpose, kind: RequestKind, sourceChannelId: String?, forceRefresh: Bool,
                     requiresMuxed: Bool, requiresHLS: Bool) async throws -> Resolved {
            videoIds.append(videoId)
            sourceChannelIds.append(sourceChannelId)
            guard !outcomes.isEmpty else { throw ExtractionError.unavailable(videoId: videoId) }
            return try outcomes.removeFirst().get()
        }

        func demote(_ videoId: String, failed: ClientFamily) async {}
    }

    /// Records each sleep; the first `immediate` return at once, later ones park until cancelled.
    final class Sleeper: @unchecked Sendable {
        private let lock = NSLock()
        private var calls: [Duration] = []
        private let immediate: Int
        init(immediate: Int) { self.immediate = immediate }
        var recorded: [Duration] { lock.withLock { calls } }
        func sleep(_ duration: Duration) async throws {
            let count = lock.withLock { calls.append(duration); return calls.count }
            if count > immediate { try await Task.sleep(for: .seconds(3600)) }
        }
    }

    static func live(_ id: String, expiresIn: TimeInterval = 21_540, now: Bool = true) -> Resolved {
        Resolved(stream: .hls(url: URL(string: "https://manifest.test/\(id).m3u8")!, isLive: true, audioOnlyURL: nil, captionTracks: []),
                 client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(expiresIn), isLiveNow: now)
    }

    func makeViewModel(_ resolver: RecordingResolver, args: PlayerArgs, sleeper: Sleeper,
                       lives: [String: LiveChannelLives] = [:]) -> PlayerViewModel {
        let settings = UserDefaultsSettingsStore(defaults: UserDefaults(suiteName: "LiveChannelPlaybackTests.\(UUID().uuidString)")!)
        return PlayerViewModel(resolver: resolver, settings: settings, args: args,
                               liveLookup: { id in
                                   guard let found = lives[id] else { throw URLError(.notConnectedToInternet) }
                                   return found
                               },
                               sleep: { try await sleeper.sleep($0) })
    }

    func waitUntil(_ condition: () async -> Bool) async throws {
        for _ in 0..<200 {
            if await condition() { return }
            try await Task.sleep(for: .milliseconds(10))
        }
        Issue.record("condition never held")
    }

    static func file(_ vm: PlayerViewModel) -> String? {
        vm.state.resolved.map { PlayerHostView.streamURL($0.stream).lastPathComponent }
    }

    // MARK: - §9.3(a) the refresh (R13)

    @Test func theRefreshIsTenMinutesBeforeExpiry() {
        let now = Date()
        #expect(PlayerViewModel.liveRefreshDelay(expiresAt: now.addingTimeInterval(21_540), now: now) == 20_940)
        #expect(PlayerViewModel.liveRefreshDelay(expiresAt: now.addingTimeInterval(300), now: now) == 0)
    }

    @Test func aLiveStreamIsReResolvedBeforeItsURLsExpire() async throws {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 601)), .success(Self.live("a1fresh"))])
        let sleeper = Sleeper(immediate: 1)
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: sleeper)

        await vm.open()
        try await waitUntil { await resolver.videoIds.count == 2 }
        try await waitUntil { Self.file(vm) == "a1fresh.m3u8" }

        #expect((sleeper.recorded.first?.components.seconds ?? 99) <= 1)       // 601 s to expiry → ~1 s
    }

    /// A refresh only replaces a live stream with one that is still live now (never a recording, F28).
    @Test func aRefreshThatIsNoLongerLiveKeepsThePlayingStream() async throws {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 601)), .success(Self.live("ended", now: false))])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "Rs7St51oDDc"), sleeper: Sleeper(immediate: 1))

        await vm.open()
        try await waitUntil { await resolver.videoIds.count == 2 }
        try await Task.sleep(for: .milliseconds(50))

        #expect(Self.file(vm) == "a1.m3u8")
    }

    @Test func aVodStreamSchedulesNoRefresh() async throws {
        let vod = Resolved(stream: .hls(url: URL(string: "https://manifest.test/v.m3u8")!, isLive: false, audioOnlyURL: nil, captionTracks: []),
                           client: .visionos, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(601))
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(RecordingResolver([.success(vod)]), args: PlayerArgs(videoId: "x"), sleeper: sleeper)

        await vm.open()

        #expect(sleeper.recorded.isEmpty)
    }

    /// F28: an ended broadcast still resolves `.hls(isLive: true)`. It is a recording, so it gets no live timer, and its
    /// foreground refresh still lands (the live-now check guards only a stream that is live now).
    @Test func anEndedBroadcastIsNotTreatedAsLive() async throws {
        let resolver = RecordingResolver([.success(Self.live("rec", expiresIn: 30, now: false)),
                                          .success(Self.live("recfresh", now: false))])
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "x"), sleeper: sleeper)

        await vm.open()
        await vm.reResolveIfExpiring()

        #expect(sleeper.recorded.isEmpty)
        #expect(Self.file(vm) == "recfresh.m3u8")
    }

    /// HD (spec R43) is VOD only: a 360p stream arms no live timer, and the live refresh leaves it alone.
    @Test func theHDPathNeverTouchesTheLiveRefresh() async {
        let sd = Resolved(stream: .progressive(url: URL(string: "https://media.test/sd.mp4")!, label: "360p"),
                          client: .android, userAgent: "ua", resolvedAt: Date(), expiresAt: Date().addingTimeInterval(601))
        let resolver = RecordingResolver([.success(sd)])
        let sleeper = Sleeper(immediate: 0)
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "x"), sleeper: sleeper)

        await vm.open()
        await vm.refreshLiveStream()

        #expect(sleeper.recorded.isEmpty)
        #expect(await resolver.videoIds.count == 1)
    }

    /// One refresh per expiry: the timer and the foreground check meeting (an app resumed past `expiresAt − 60 s`, where
    /// the timer's sleep also ends) join one walk. Two would trip the limiter's 30 s interval, and the second's
    /// generation bump would discard the first's good answer.
    @Test func theTimerAndTheForegroundCheckShareOneRefresh() async {
        let resolver = RecordingResolver([.success(Self.live("a1", expiresIn: 30)), .success(Self.live("fresh")),
                                          .success(Self.live("second"))])
        let vm = makeViewModel(resolver, args: PlayerArgs(videoId: "x"), sleeper: Sleeper(immediate: 0))

        await vm.open()
        async let foreground: Void = vm.reResolveIfExpiring()
        async let timer: Void = vm.refreshLiveStream()
        _ = await (foreground, timer)

        #expect(await resolver.videoIds.count == 2)
        #expect(Self.file(vm) == "fresh.m3u8")
    }

    /// Regression pin (R13; passes before this task too): a timer does not fire while the app is suspended and
    /// paused, so the foreground check must stay. It fails only if a later change deletes or loosens it.
    @Test func theForegroundCheckStays() {
        #expect(PlayerViewModel.shouldPreemptivelyReResolve(.ready(Self.live("a", expiresIn: 30)), now: Date()))
    }
}
