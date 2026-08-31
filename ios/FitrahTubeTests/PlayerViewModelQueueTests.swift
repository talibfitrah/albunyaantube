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
        await vm.play(at: 1)
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
        #expect(resolver.calls.filter { $0.videoId == "b" && $0.kind == .prefetch }.count == 2)   // open + explicit
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")                                    // and it did not block
        #expect(vm.state.isPlayable)
    }

    @Test func pagingFetchesTheNextPageAtFiveRemaining() async {
        // Seven on page 1: six upcoming at open (above the threshold, so `open()` does NOT page --
        // see `openPagesImmediatelyWhenTheLaunchLandsNearAPageEnd` for the other side).
        let source = FakeQueueSource(pages: [(ids: ["a", "b", "c", "d", "e", "f", "g"], next: "P2"),
                                             (ids: ["h", "i"], next: nil)])
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), source: source)
        await vm.open()
        #expect(vm.queue.upcoming.count == 6)
        #expect(source.pageCalls == 1)
        await vm.playToEnd()                     // now 5 remaining -> needsPage fires
        #expect(vm.queue.items.count == 9)
        #expect(source.pageCalls == 2)
    }

    /// B5 Task 4 finding: a launch that lands within `pageThreshold` of a page end (a Plan C row tap
    /// near the bottom of page 1, or a `targetVideoId` there) used to show a truncated Up Next --
    /// `open()` loaded page 1 and never asked `pageIfNeeded`, so the section listed 1 row (or none:
    /// ruling 33 hides it) while a whole second page existed. The first ADVANCE paged, so playback
    /// was never wrong; the list was.
    @Test func openPagesImmediatelyWhenTheLaunchLandsNearAPageEnd() async {
        let source = FakeQueueSource(pages: [(ids: ["a", "b", "c", "d", "e", "f"], next: "P2"),
                                             (ids: ["g", "h"], next: nil)])
        let (vm, _) = makeModel(args: .init(videoId: "e", playlistId: "PL", targetVideoId: "e"), source: source)
        await vm.open()
        #expect(vm.queue.items.count == 8)
        #expect(vm.queue.upcoming.map(\.id) == ["f", "g", "h"])
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

    /// Cubic P3: `pageIfNeeded`'s single-flight guard returned EARLY while another task was still
    /// suspended inside `queueSource.page(...)`. An end-of-item advance in that window saw an
    /// empty `upcoming`, took nil from `queue.advance()` and showed `.queueEnded` -- with a whole
    /// page still loading. A concurrent caller must await the in-flight fetch and advance into it.
    @Test func anAdvanceDuringAnInFlightPageFetchWaitsInsteadOfEndingTheQueue() async {
        let source = FakeQueueSource(pages: [(ids: ["a", "b"], next: "P2"), (ids: ["c", "d"], next: nil)],
                                     gateFrom: 1)
        let (vm, _) = makeModel(args: .init(videoId: "b", playlistId: "PL", targetVideoId: "b"),
                                source: source)
        let opening = Task { await vm.open() }   // loadQueue (call 0), then pageIfNeeded holds call 1
        await source.waitUntilPageCalled(count: 2)
        let advancing = Task { await vm.playToEnd() }
        for _ in 0..<2000 {                      // the advance must be underway before the release
            if vm.advanceCalls >= 1 { break }
            try? await Task.sleep(for: .milliseconds(1))
        }
        source.release()
        await opening.value
        await advancing.value
        #expect(vm.state != .queueEnded)
        #expect(vm.args.videoId == "c")
        #expect(vm.state.isPlayable)
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

    @Test func advanceResetsTheHoistedPosition() async {
        // CF-B1-8: currentTime is session-only (ruling 32), hoisted so a host rebuild can restore it.
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        vm.currentTime = 42
        await vm.playToEnd()
        #expect(vm.currentTime == 0)              // new video: starts at the beginning
    }

    @Test func advanceResetsThePerStreamOrientationAndZoomFlags() async {
        // B5 Task 3 fix round: `videoIsPortrait` is set from the OLD item's presentationSize; left
        // standing, a 9:16 -> 16:9 advance would keep a portrait-fullscreen layout on a landscape
        // video until the host's next observer tick. Unknown must read as landscape (doc comment).
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        vm.videoIsPortrait = true
        vm.videoZoomed = true
        await vm.playToEnd()
        #expect(vm.videoIsPortrait == false)
        #expect(vm.videoZoomed == false)
    }

    @Test func aPrefetchedVideoIsNotResolvedAgainOnTheNextAdvance() async {
        // I2 (B5 T2 review): a second `.prefetch` resolve of an already-warmed id is a cache hit
        // that still spends the per-video retry budget and the global prefetch lane. b->c->d:
        // `d` enters the window at b (targets c,d) and stays in it at c (targets d,e) -- once.
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                       queue: ["a", "b", "c", "d", "e", "f"])
        await vm.open()
        await vm.playToEnd()
        await vm.playToEnd()
        #expect(vm.args.videoId == "c")
        let pre = resolver.calls.filter { $0.kind == .prefetch }.map(\.videoId)
        #expect(pre == ["b", "c", "d", "e"])
    }

    /// B5 final review, IMPORTANT-1: `advance()` swaps `args` BEFORE its resolve lands. A SwiftUI
    /// pass in that window sees `args.videoId` = next while `state` is still the OLD `.ready`; a
    /// host keyed on `args` read that as "different video" and rebuilt the OLD url at 0 -- the
    /// outgoing video restarted, and the real resolved pass then resumed the next video off-zero.
    /// The host's key must be the video `state` describes, which only moves when a resolve lands.
    @Test(.timeLimit(.minutes(1))) func theHostKeyStaysOnTheOldVideoUntilTheAdvanceResolves() async {
        let resolver = RecordingResolver(.hls, holdsUntilReleased: true)
        let vm = PlayerViewModel(resolver: resolver, settings: makeSettings(safeMode: false),
                                 args: .init(videoId: "a", playlistId: "PL"),
                                 queueSource: FakeQueueSource(pages: [(ids: ["a", "b"], next: nil)]))
        resolver.release(); resolver.release()          // open(): a on .player, b on .prefetch
        await vm.open()
        #expect(vm.hostVideoId == "a")

        let advance = Task { await vm.playToEnd() }
        await resolver.waitUntilCalled(count: 3)        // b on .player, held
        #expect(vm.args.videoId == "b")
        #expect(vm.state.isPlayable)                    // still the OLD stream
        #expect(vm.hostVideoId == "a")                  // so the host must still key on it

        resolver.release()
        await advance.value
        #expect(vm.hostVideoId == "b")
    }

    @Test func aFailedQueueLoadNeverKillsThePlayingVideo() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                source: FakeQueueSource(pages: [], failFrom: 0))
        await vm.open()
        #expect(vm.state.isPlayable)
        #expect(vm.queue.items.isEmpty)
    }
}
