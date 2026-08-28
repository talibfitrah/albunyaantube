import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Plan B5 Task 2: the queue inside `PlayerViewModel` -- auto-advance, auto-skip, prefetch and
/// paging, all driven through `playToEnd()` (the end-of-item hook) with no AVFoundation involved.
@Suite(.perTest)
struct PlayerViewModelQueueTests {
    private func makeSettings(safeMode: Bool) -> UserDefaultsSettingsStore {
        let settings = UserDefaultsSettingsStore(
            defaults: UserDefaults(suiteName: "PlayerViewModelQueueTests.\(UUID().uuidString)")!)
        settings.safeMode = safeMode
        return settings
    }

    /// `queue:` becomes a single-page `FakeQueueSource`; `source:` overrides it. `unplayable:` ids
    /// fail on every lane; `cooldown:` ids fail on the `.prefetch` lane only.
    private func makeModel(args: PlayerArgs, queue: [String] = [], unplayable: [String] = [],
                           cooldown: [String] = [], source: FakeQueueSource? = nil,
                           settings: UserDefaultsSettingsStore? = nil, safeMode: Bool = false)
        -> (vm: PlayerViewModel, resolver: RecordingResolver) {
        let resolver = RecordingResolver(.hls)
        resolver.outcomes = Dictionary(uniqueKeysWithValues: unplayable.map {
            ($0, RecordingResolver.Outcome.failure(.unavailable(videoId: $0)))
        })
        resolver.prefetchRefusals = Set(cooldown)
        let vm = PlayerViewModel(resolver: resolver, settings: settings ?? makeSettings(safeMode: safeMode),
                                 args: args,
                                 queueSource: source ?? FakeQueueSource(pages: [(ids: queue, next: nil)]))
        return (vm, resolver)
    }

    @Test func endOfItemAdvancesToTheNextQueuedVideo() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")
        #expect(vm.state.isPlayable)
        #expect(vm.args.playlistId == "PL")   // the queue context survives the advance
    }

    @Test func safeModeDisablesAutoAdvanceButNotTheQueue() async {
        // Ruling 58 + spec §10 "auto-advance on end UNLESS Safe Mode" + plan §6.10. The queue is
        // still populated and a TAP still plays -- only the automatic hop is gone.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"], safeMode: true)
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "a")                    // did not advance
        #expect(vm.queue.upcoming.map(\.id) == ["b"])      // queue intact -- ruling 33 shows it
        await vm.play(id: "b")
        #expect(vm.args.videoId == "b")                    // manual tap is unaffected
    }

    @Test func safeModeReadsTheViewModelPropertyNotTheStoreDirectly() async {
        // CF-B3-2: one Safe Mode reader in the player. Flipping the store mid-session must take
        // effect on the NEXT end-of-item, because `safeMode` is a live computed read.
        let settings = makeSettings(safeMode: true)
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"], settings: settings)
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "a")
        settings.safeMode = false
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")
    }

    @Test func endOfItemWithAnExhaustedQueueStopsInsteadOfLooping() async {
        // `PlayerViewModel.kt:1920-1923`: no next item, no more pages -> playback stops. On iOS that
        // must NOT be `.idle` (reconciliation note 9: `.idle` renders as an eternal "Loading..."
        // spinner with no Retry -- `PlayerStateView.swift:28-32`), so the terminus is its own state.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a"])
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "a")
        #expect(vm.state == .queueEnded)
        #expect(vm.state != .idle)
    }

    @Test func endOfItemInSingleVideoModeLeavesThePlayerAlone() async {
        // No playlist, no queue: the end of the video is the end of the video. AVKit sits on the
        // last frame with its own replay; a "playlist ended" card would be a lie.
        let (vm, _) = makeModel(args: .init(videoId: "a"))
        await vm.open()
        await vm.playToEnd()
        #expect(vm.state.isPlayable)
        #expect(vm.queue.items.isEmpty)
    }

    @Test func queueEndedIsEquatableToItself() {
        // `StreamState.==` has a `default: return false` arm -- without an explicit tuple case the
        // assertion above would silently fail forever (reconciliation note 9's enumeration table).
        #expect(StreamState.queueEnded == StreamState.queueEnded)
        #expect(StreamState.queueEnded != StreamState.idle)
    }

    @Test func autoSkipWalksPastUnplayableItemsAndStopsAfterThree() async {
        // `PlayerViewModel.kt:1349-1366`. Four dead items in a row: skip 1, 2, 3, then STOP on the
        // 4th with the real terminal state so the user sees something instead of a silent walk.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                queue: ["a", "x1", "x2", "x3", "x4", "z"],
                                unplayable: ["x1", "x2", "x3", "x4"])
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "x4")
        #expect(vm.state == .contentUnavailable)   // ruling 14's one terminal surface
    }

    @Test func aSuccessfulAdvanceResetsTheSkipCounter() async {
        // `PlayerViewModel.kt:1916`: consecutive, not cumulative. Three dead items after `b` are
        // three skips -- one short of the cap -- so the walk lands on `c`, playing.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                queue: ["a", "x1", "b", "x2", "x3", "x4", "c"],
                                unplayable: ["x1", "x2", "x3", "x4"])
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")            // one skip, counter back to 0
        await vm.playToEnd()
        #expect(vm.args.videoId == "c")            // three more skips, then plays
        #expect(vm.state.isPlayable)
    }

    @Test func autoAdvanceNeverForcesARefresh() async {
        // CF-B2-2, rule (b), verbatim: "auto-advance must NEVER pass forceRefresh: true -- the next
        // video's manifest cache entry is the whole point of prefetching it."
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        await vm.prefetchUpcoming()
        await vm.playToEnd()
        #expect(resolver.calls.filter { $0.forceRefresh }.isEmpty)
    }

    @Test func prefetchResolvesTheNextTwoOnThePrefetchLane() async {
        // Ruling 16 + CF-B2-2 + reconciliation note 8.
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                       queue: ["a", "b", "c", "d", "e"])
        await vm.open()
        let pre = resolver.calls.filter { $0.kind == .prefetch }
        #expect(pre.map(\.videoId) == ["b", "c"])
        #expect(pre.allSatisfy { $0.purpose == .prefetch && $0.forceRefresh == false })
    }

    @Test func aPrefetchRefusalIsSkippedSilently() async {
        // CF-B2-2, rule (a): never surfaced as .cooldown, never retried into, never blocks.
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                       queue: ["a", "b", "c"], cooldown: ["b"])
        await vm.open()
        let before = vm.state
        await vm.prefetchUpcoming()
        #expect(vm.state == before)                                        // state untouched
        #expect(resolver.calls.filter { $0.videoId == "b" }.count == 2)    // open + explicit; never retried
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")                                    // and it did not block
        #expect(vm.state.isPlayable)
    }

    @Test func pagingFetchesTheNextPageAtFiveRemainingAndLatchesOnFailure() async {
        let source = FakeQueueSource(pages: [(ids: ["a", "b", "c", "d", "e", "f"], next: "P2"),
                                             (ids: ["g", "h"], next: nil)])
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), source: source)
        await vm.open()
        #expect(vm.queue.upcoming.count == 5)
        await vm.playToEnd()                     // now 4 remaining -> needsPage fired at 5
        #expect(vm.queue.items.count == 8)
        #expect(source.pageCalls == 2)
    }

    @Test func aFailedPageStopsPagingAndPlaybackEndsCleanly() async {
        let source = FakeQueueSource(pages: [(ids: ["a", "b"], next: "P2")], failFrom: 1)
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), source: source)
        await vm.open()
        await vm.playToEnd()                     // -> b
        #expect(vm.args.videoId == "b")
        await vm.playToEnd()                     // queue empty, page failed -> stop
        #expect(vm.state == .queueEnded)
        #expect(vm.queue.hasMorePages == false)
        #expect(source.pageCalls == 2)           // the latch: no third attempt
    }

    @Test func theDeepStartScanIsBoundedAndFallsBackToTheIndexHint() async {
        // `PlayerViewModel.kt:904-1031`, bounds `:1782-1785` (250 items / 3 s).
        let source = FakeQueueSource(pages: (0..<40).map { page in
            (ids: (0..<10).map { i in "v\(page * 10 + i)" }, next: page == 39 ? nil : "P\(page)")
        })
        let (vm, _) = makeModel(args: .init(videoId: "v0", playlistId: "PL", startIndex: 3,
                                            targetVideoId: "v390"),   // past the 250-item bound
                                source: source)
        await vm.open()
        #expect(vm.queue.items.count <= 250)
        #expect(vm.queue.current?.id == "v3")     // the startIndex hint
    }

    @Test func advanceResetsTheHoistedPositionAndKeepsItAcrossASameVideoRetry() async {
        // CF-B1-8: currentTime is session-only (ruling 32), hoisted so a host rebuild can restore it.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        vm.currentTime = 42
        await vm.retry()
        #expect(vm.currentTime == 42)             // same video: position survives
        await vm.playToEnd()
        #expect(vm.currentTime == 0)              // new video: starts at the beginning
    }

    @Test func aFailedQueueLoadNeverKillsThePlayingVideo() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                source: FakeQueueSource(pages: [], failFrom: 0))
        await vm.open()
        #expect(vm.state.isPlayable)
        #expect(vm.queue.items.isEmpty)
    }
}
