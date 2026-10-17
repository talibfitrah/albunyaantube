import AVFoundation
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

    /// 1.0.4 round 5 (re-review N1, test b): the advance's flag outlived the swap through the
    /// prefetch, so a SHORT next lecture that ended meanwhile hit the one-advance guard and the
    /// playlist stopped. The flag clears once the advance lands; the next end advances again.
    @Test(.timeLimit(.minutes(1))) func aShortNextLectureThatEndsDuringThePrefetchStillAdvances() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b", "c"],
                                       cooldown: ["c"])   // open() leaves c unprefetched
        await vm.open()
        resolver.prefetchRefusals = []
        resolver.hold("c")                                 // the advance to b now waits in c's prefetch
        let first = Task { await vm.playToEnd() }
        await resolver.waitUntilCalled(count: 5)           // a, b, c(refused) at open; b(.player), c(.prefetch, held)
        #expect(vm.args.videoId == "b")

        let second = Task { await vm.playToEnd() }         // b, a 16 s clip, ends while c's prefetch is out
        await resolver.waitUntilCalled(count: 6)           // c on .player, held
        resolver.release(id: "c")
        resolver.release(id: "c")
        await first.value
        await second.value
        #expect(vm.advanceCalls == 2, "b's end was swallowed")
        #expect(vm.args.videoId == "c")
    }

    /// Round 3 m1's other half (re-review: pin it): one end, one advance. A second end for the same
    /// ended item while its advance is still resolving (a `.resume` that plays it) must not skip.
    @Test(.timeLimit(.minutes(1))) func aSecondEndWhileTheAdvanceResolvesIsIgnored() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b", "c"])
        await vm.open()
        resolver.hold("b")
        let first = Task { await vm.playToEnd() }
        await resolver.waitUntilCalled(count: 4)           // a, b, c at open; b on .player, held
        await vm.playToEnd()                               // the same end again
        resolver.release(id: "b")
        await first.value
        #expect(vm.advanceCalls == 1, "one end advanced twice")
        #expect(vm.args.videoId == "b")
    }

    @Test func endOfItemAdvancesToTheNextQueuedVideo() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")
        #expect(vm.state.isPlayable)
        #expect(vm.args.playlistId == "PL")   // the queue context survives the advance
    }

    /// Captions bug: Up Next keeps the chosen caption LANGUAGE, like Android's
    /// `setPreferredTextLanguage` on its one player (`PlayerFragment.kt:549-553`). The next video's
    /// track is the same language at a different URL, so matching by URL dropped the captions AND
    /// the menu's checkmark on every hop.
    @Test func upNextKeepsTheChosenCaptionLanguage() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        let arabicOnA = CaptionTrack(url: URL(string: "https://example.com/a/ar.vtt")!, languageCode: "ar",
                                     languageName: "Arabic", isAutoGenerated: true)
        let arabicOnB = CaptionTrack(url: URL(string: "https://example.com/b/ar.vtt")!, languageCode: "ar",
                                     languageName: "Arabic", isAutoGenerated: true)
        resolver.captionTracks = ["a": [arabicOnA], "b": [arabicOnB]]
        await vm.open()
        CaptionsMenu.pick(CaptionChoice(languageCode: "ar", isAutoGenerated: true), vm)   // the pick on A
        #expect(CaptionsMenu.overlayTrack(vm) == arabicOnA)

        await vm.play(at: 1)

        #expect(vm.args.videoId == "b")
        #expect(CaptionsMenu.overlayTrack(vm) == arabicOnB)
    }

    /// Carry-over crosses kinds: an UPLOADER Arabic pick on A lands on B's auto-generated Arabic
    /// when that is the only Arabic B has (same kind first, then the other kind, else Off).
    @Test func upNextCarriesAnUploaderPickToTheNextVideosAutoGeneratedTrack() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        let arabicOnB = CaptionTrack(url: URL(string: "https://example.com/b/ar.vtt")!, languageCode: "ar",
                                     languageName: "Arabic", isAutoGenerated: true)
        resolver.captionTracks = ["b": [arabicOnB]]
        await vm.open()
        vm.captionChoice = CaptionChoice(languageCode: "ar", isAutoGenerated: false)   // the pick on A

        await vm.play(at: 1)

        #expect(vm.args.videoId == "b")
        #expect(CaptionsMenu.overlayTrack(vm) == arabicOnB)
    }

    /// Cubic R2-4: `swapArgs` resets `airPlayFellBack` so the AirPlay mirroring fallback is per
    /// stream, but the `allowsExternalPlayback = false` it wrote lives on the `AVPlayer` —
    /// which `PlayerHostView.player(for:replacing:)` REUSES across advances and only ever sets
    /// true on a freshly built player (deliberately, so an update pass cannot undo the fallback
    /// while it is still wanted). So after one 403 fallback every later queue item silently
    /// mirrored instead of playing on the AirPlay device, and a later failure re-ran the fallback
    /// against an already-disabled route. The reset has to reach the same player the fallback did.
    @Test func anAdvanceUndoesTheAirPlayMirroringFallbackOnTheReusedPlayer() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        let player = AVPlayer()
        vm.currentPlayer = player
        player.allowsExternalPlayback = false      // what the fallback wrote for THIS stream
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")
        #expect(player.allowsExternalPlayback,
                "the next queue item mirrors instead of playing on the AirPlay device")
    }

    /// R4-2: `swapArgs` reset `currentTime` and the per-stream flags but not `pausedForCast`, so
    /// after an Up Next tap mid-cast the phone played B while the cast bookkeeping still described
    /// A — and the session's end then seeked B to A's receiver position and played it. The pause
    /// belongs to the video it was taken for.
    @Test func anAdvanceSpendsThePauseThatBelongedToTheVideoThatIsGone() async {
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        vm.currentPlayer = AVPlayer()
        vm.pauseForCast()
        await vm.playToEnd()
        #expect(vm.args.videoId == "b")
        #expect(vm.pausedForCast == false, "the pause belonged to A's cast, not to B")
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

    @Test func aUserTapDuringAGatedAdvancePageFetchWins() async {
        // Cubic #4: `advance()` suspends in `await pageIfNeeded()` and then unconditionally moved
        // the queue -- a `play(at:)` landing during that suspension was overridden by the resumed
        // advance (its resolve superseded the user's pick). Same gated window as
        // `anAdvanceDuringAnInFlightPageFetchWaitsInsteadOfEndingTheQueue`: a near-page-end launch
        // holds `open()`'s page fetch, the end-of-item advance joins that in-flight fetch, and the
        // user taps "g" while both are held open.
        let source = FakeQueueSource(pages: [(ids: ["a", "b", "c", "d", "e", "f", "g"], next: "P2"),
                                             (ids: ["h"], next: nil)],
                                     gateFrom: 1)
        let (vm, _) = makeModel(args: .init(videoId: "e", playlistId: "PL", targetVideoId: "e"),
                                source: source)
        let opening = Task { await vm.open() }   // loadQueue (call 0), then pageIfNeeded holds call 1
        await source.waitUntilPageCalled(count: 2)
        let advancing = Task { await vm.playToEnd() }      // advance joins the held fetch, pre-queue.advance()
        for _ in 0..<2000 {
            if vm.advanceCalls >= 1 { break }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(vm.advanceCalls >= 1)
        let playing = Task { await vm.play(at: 6) }        // the user taps "g" mid-fetch
        for _ in 0..<2000 {                                // the tap's own resolve must land first
            if vm.args.videoId == "g" { break }
            try? await Task.sleep(for: .milliseconds(1))
        }
        #expect(vm.args.videoId == "g")
        source.release()
        await opening.value
        await advancing.value
        await playing.value
        #expect(vm.args.videoId == "g")                    // the user's pick survives the resumed advance
        #expect(vm.state.isPlayable)
    }

    /// Review F1: `advance()` re-checks supersession after `pageIfNeeded()` but not after its OWN
    /// resolve. Traced: advance's resolve for "b" in flight -> the user taps "d" (`play(at:)`
    /// cancels it, sets `.loading`, bumps generation) -> advance resumes, sees `.loading` (not
    /// playable), enters the skip recursion, `queue.advance()` moves off the user's pick and
    /// its own resolve supersedes the tap.
    @Test(.timeLimit(.minutes(1))) func aUserTapDuringTheAdvancesOwnResolveWins() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"),
                                       queue: ["a", "b", "c", "d", "e"])
        await vm.open()
        resolver.hold("b")                                 // the advance's own resolve
        resolver.hold("d")                                 // the tap's resolve: held so the resumed
                                                           // advance sees `.loading` -- the traced window
        let advancing = Task { await vm.playToEnd() }      // -> b, resolve held
        await resolver.waitUntilCalled(count: 4)           // open: a(.player), b/c(.prefetch); then b(.player)
        let playing = Task { await vm.play(at: 3) }        // the user taps "d" mid-resolve
        await resolver.waitUntilCalled(count: 5)           // the tap's resolve for d is out (held)
        resolver.release(id: "b")                          // the advance resumes into the race window
        await advancing.value
        #expect(vm.args.videoId == "d")                    // the tap's pick wins; no skip burned onto "e"
        resolver.release(id: "d")
        await playing.value
        #expect(vm.args.videoId == "d")
        #expect(vm.state.isPlayable)
    }

    /// Task 6 review M1: a step-down's `demote` is a real suspension. An Up Next tap that lands in it
    /// owns the screen; the resumed recovery must not force-resolve the tapped video on the
    /// `.autoRecovery` lane and supersede the tap's own resolve.
    @Test(.timeLimit(.minutes(1))) func anUpNextTapDuringTheStepDownsDemoteWins() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        let gate = Gate()
        resolver.demoteGate = gate
        let recovering = Task { await vm.handleRecoveryEvent(.playbackError, httpStatus: 401) }
        await gate.waitUntilBlocked()                      // recovery is inside `demote`
        await vm.play(at: 1)                               // the user taps "b"
        await gate.release()
        await recovering.value
        #expect(resolver.demotions == ["a:visionos"])
        #expect(!resolver.calls.contains { $0.kind == .autoRecovery })
        #expect(vm.args.videoId == "b")
        #expect(vm.state.isPlayable)
    }

    /// R2-1: the step-down guard is the stepped-down VIDEO's, so it survives a Retry but not a change
    /// of video. The next video that flakes to exhaustion still demotes its own rung.
    @Test(.timeLimit(.minutes(1))) func theStepDownGuardDoesNotFollowTheQueueToTheNextVideo() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 401)   // a steps down
        await vm.play(at: 1)                                             // the user taps "b"
        vm.recordPlaybackProgress()
        for _ in 0..<RecoveryBudget.maxRetries {
            await vm.handleRecoveryEvent(.playbackError)                // b flakes, plays again
            vm.recordPlaybackProgress()
        }
        await vm.handleRecoveryEvent(.playbackError)

        #expect(vm.args.videoId == "b")
        #expect(resolver.demotions == ["a:visionos", "b:visionos"])
    }

    /// Fix r4 N-5: one remembered id lost a's guard to b, so a -> b -> a demoted a's fallback at its
    /// next exhaustion and pointed Retry back at the rung that had 401'd. Every stepped-down video
    /// keeps its guard.
    @Test(.timeLimit(.minutes(1))) func theStepDownGuardSurvivesAToBToA() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 401)   // a steps down
        await vm.play(at: 1)
        await vm.handleRecoveryEvent(.playbackError, httpStatus: 401)   // b steps down
        await vm.play(at: 0)                                             // back to a
        vm.recordPlaybackProgress()
        for _ in 0..<RecoveryBudget.maxRetries {
            await vm.handleRecoveryEvent(.playbackError)                // a's fallback flakes, plays again
            vm.recordPlaybackProgress()
        }
        await vm.handleRecoveryEvent(.playbackError)

        #expect(vm.args.videoId == "a")
        #expect(!vm.state.isPlayable, "a never reached the Retry card")
        #expect(resolver.demotions == ["a:visionos", "b:visionos"])
    }

    /// Stage 5 F4: a recovery an Up Next tap superseded can stay suspended on the network for a
    /// long time (a `Task.value` does not end when its awaiter is cancelled). Its claim on the
    /// recovery slot ends with the tap, so the next video's one-shot failure is still recovered.
    @Test(.timeLimit(.minutes(1))) func aSupersededRecoveryDoesNotSwallowTheNextVideosFailure() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        let gate = Gate()
        resolver.demoteGate = gate
        let recovering = Task { await vm.handleRecoveryEvent(.playbackError, httpStatus: 401) }
        await gate.waitUntilBlocked()                      // a's recovery hangs inside `demote`
        await vm.play(at: 1)                               // the user taps "b", which plays

        await vm.handleRecoveryEvent(.playbackError)       // b's item fails once (no status: same rung)

        #expect(resolver.calls.contains { $0.videoId == "b" && $0.kind == .autoRecovery },
                "b's failure was dropped behind a's superseded recovery")
        await gate.release()
        await recovering.value
    }

    /// Final review M1(b): the outgoing item dies while an auto-advance's resolve is out. That
    /// failure belongs to the video `state` still shows, not to the one `args` now names, so
    /// recovery must not force-resolve the NEXT video on the `.autoRecovery` lane and supersede the
    /// advance (which would skip its prefetch, paging and auto-skip).
    @Test(.timeLimit(.minutes(1))) func aFailureDuringAnAdvancesResolveDoesNotRecoverTheNextVideo() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL"), queue: ["a", "b"])
        await vm.open()
        resolver.hold("b")
        let advancing = Task { await vm.playToEnd() }      // -> b, its resolve held
        await resolver.waitUntilCalled(count: 3)           // open: a(.player), b(.prefetch); then b(.player)
        let recovering = Task { await vm.handleRecoveryEvent(.playbackError) }   // a's item dies now
        try? await Task.sleep(for: .milliseconds(50))      // let a wrong recovery reach the resolver
        resolver.release(id: "b")
        resolver.release(id: "b")                          // a wrong recovery's own resolve of b, if any
        await recovering.value
        await advancing.value
        #expect(!resolver.calls.contains { $0.kind == .autoRecovery }, "recovered the next video")
        #expect(vm.args.videoId == "b")
        #expect(vm.state.isPlayable)
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
        #expect(vm.advanceCalls >= 1)            // a timed-out poll must fail loudly, not test nothing
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

    // MARK: - Owner issue 2: the playlist screen's rows seed the queue

    private func seed(_ ids: [String]) -> [ContentItem] {
        ids.map { ContentItem(video: VideoItem(id: $0, title: "T-\($0)", channelName: "Ch",
                                               durationSeconds: 120, thumbnailURL: nil)) }
    }

    @Test(.timeLimit(.minutes(1))) func aSeededQueueIsInPlaceBeforeTheResolveAndFetchesNoPage() async {
        let source = FakeQueueSource(pages: [], failFrom: 0)          // any page call fails
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL", targetVideoId: "a",
                                                   queueItems: seed(["a", "b", "c"])), source: source)
        resolver.hold("a")
        let opening = Task { await vm.open() }
        await resolver.waitUntilCalled(count: 1)
        #expect(vm.queue.upcoming.map(\.id) == ["b", "c"])           // while the resolve is held
        resolver.release(id: "a")
        await opening.value
        #expect(source.pageCalls == 0)
        #expect(vm.state.isPlayable)
    }

    /// §4 path 1: a `.task` cancelled mid-open must not leave Up Next empty.
    @Test(.timeLimit(.minutes(1))) func aCancelledOpenKeepsTheSeededQueue() async {
        let (vm, resolver) = makeModel(args: .init(videoId: "a", playlistId: "PL", targetVideoId: "a",
                                                   queueItems: seed(["a", "b", "c"])),
                                       source: FakeQueueSource(pages: [], failFrom: 0))
        resolver.hold("a")
        let opening = Task { await vm.open() }
        await resolver.waitUntilCalled(count: 1)
        opening.cancel()
        resolver.release(id: "a")
        await opening.value
        #expect(vm.queue.upcoming.map(\.id) == ["b", "c"])
    }

    @Test func aSeededQueuePagesOnFromThePassedToken() async {
        let source = FakeQueueSource(pages: [(ids: ["g", "h"], next: nil)])
        let (vm, _) = makeModel(args: .init(videoId: "e", playlistId: "PL", targetVideoId: "e",
                                            queueItems: seed(["a", "b", "c", "d", "e", "f"]),
                                            queueContinuation: "P2"), source: source)
        await vm.open()
        #expect(source.continuations == ["P2"])
        #expect(vm.queue.upcoming.map(\.id) == ["f", "g", "h"])
    }

    @Test func aSeededShufflePinsTheStartAndNeverPages() async {
        let source = FakeQueueSource(pages: [], failFrom: 0)
        let (vm, _) = makeModel(args: .init(videoId: "a", playlistId: "PL", shuffled: true,
                                            queueItems: seed(["a", "b", "c", "d", "e", "f"]),
                                            queueContinuation: "P2"), source: source)
        await vm.open()
        #expect(vm.queue.current?.id == "a")
        #expect(vm.queue.hasMorePages == false)
        #expect(source.pageCalls == 0)
    }
}
