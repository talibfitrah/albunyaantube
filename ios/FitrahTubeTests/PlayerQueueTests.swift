import Testing
import InnerTubeKit
@testable import FitrahTube

private func items(_ ids: [String]) -> [ContentItem] {
    ids.map { ContentItem(video: VideoItem(id: $0, title: "T-\($0)", channelName: "Ch",
                                           durationSeconds: 120, thumbnailURL: nil)) }
}

@Test func videoItemMapsOntoContentItemWithoutInventingAViewCount() {
    // Reconciliation note 5 + ruling 39: the real channel title lands in `channelTitle`,
    // `category` stays nil (no `channelName <- category` leak), and `viewCount` stays nil
    // because VideoItem only has PRE-FORMATTED display text, which `Format` cannot consume.
    let mapped = ContentItem(video: VideoItem(id: "xc7keR2piUM", title: "Lecture",
                                              channelName: "Sheikh", channelId: "UC1",
                                              durationSeconds: 610,
                                              viewCountText: "1.2M views", thumbnailURL: nil))
    #expect(mapped.id == "xc7keR2piUM")
    #expect(mapped.type == .video)
    #expect(mapped.channelTitle == "Sheikh")
    #expect(mapped.category == nil)
    #expect(mapped.viewCount == nil)
    #expect(mapped.durationSeconds == 610)
}

@Test func startPositionsOnTargetVideoIdNotStartIndex() {
    // `playlist-detail-shorts.md` §3.4 / `PlaylistDetailFragment.kt:747`: targetVideoId is
    // authoritative, startIndex is a hint. A stale hint must lose.
    let q = PlayerQueue.start(items: items(["a", "b", "c", "d"]), targetVideoId: "c",
                              startIndex: 0, shuffled: false, cursor: nil)
    #expect(q.current?.id == "c")
    #expect(q.upcoming.map(\.id) == ["d"])
}

@Test func startFallsBackToStartIndexWhenTheTargetIsNotPresent() {
    let q = PlayerQueue.start(items: items(["a", "b", "c"]), targetVideoId: "zz",
                              startIndex: 1, shuffled: false, cursor: nil)
    #expect(q.current?.id == "b")
}

@Test func startClampsAnOutOfRangeStartIndex() {
    let q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil,
                              startIndex: 99, shuffled: false, cursor: nil)
    #expect(q.current?.id == "b")
}

@Test func shufflePinsTheTappedVideoFirstAndDisablesPaging() {
    // `PlayerViewModel.kt:1044-1093`: randomize, pin the tapped video first, paging OFF.
    var generator = SeededGenerator(seed: 7)
    let q = PlayerQueue.start(items: items(["a", "b", "c", "d", "e"]), targetVideoId: "d",
                              startIndex: 0, shuffled: true, cursor: "PAGE2",
                              using: &generator)
    #expect(q.current?.id == "d")
    // Exact order under SeededGenerator(seed: 7) -- differs from the input order, so an
    // unshuffled queue fails here (a Set assertion passed without shuffling).
    #expect(q.upcoming.map(\.id) == ["b", "a", "e", "c"])
    #expect(q.hasMorePages == false)          // paging disabled when shuffled
    #expect(q.needsPage == false)
}

@Test func shuffleOfAnEmptyFirstPageStillDisablesPaging() {
    // Shuffle always disables paging: an empty first page must not keep the cursor and later
    // append an unshuffled tail.
    let q = PlayerQueue.start(items: [], targetVideoId: nil, startIndex: 0, shuffled: true,
                              cursor: "c")
    #expect(q.hasMorePages == false)
}

@Test func anEmptyQueueHasNoCurrentAndDoesNotAdvance() {
    var q = PlayerQueue.start(items: [], targetVideoId: nil, startIndex: 3, shuffled: false,
                              cursor: nil)
    #expect(q.current == nil)
    #expect(q.advance() == nil)
    #expect(q.index == 0)
}

@Test func hasNextIsTrueWhenNothingIsQueuedButAPageRemains() {
    let q = PlayerQueue.start(items: items(["a"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: "P2")
    #expect(q.upcoming.isEmpty)
    #expect(q.hasNext)
}

@Test func advanceWalksForwardAndStopsAtTheEnd() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.advance()?.id == "b")
    #expect(q.hasNext == false)
    #expect(q.advance() == nil)               // `PlayerViewModel.kt:1920-1923`: playback stops
}

@Test func selectPlaysAnArbitraryQueuedItemAndRepositionsTheQueue() {
    // `PlayerViewModel.kt:355-387`: the Up Next tap repositions the queue to the tapped index.
    var q = PlayerQueue.start(items: items(["a", "b", "c"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.select(at: 2)?.id == "c")
    #expect(q.upcoming.isEmpty)
    #expect(q.select(at: 99) == nil)          // desync is a no-op, never a crash
    #expect(q.select(at: -1) == nil)
}

@Test func selectingALaterDuplicateOccurrencePlaysThatOccurrence() {
    // Cubic P2, the Up Next twin of PlaylistDetail's `rowsStayDistinctWhenAPlaylistRepeatsAVideo`
    // (23b3c325): a playlist can repeat a video id. A tap on the SECOND "dup" (absolute index 3)
    // must reposition the queue there, never jump back to the first occurrence at index 1.
    var q = PlayerQueue.start(items: items(["a", "dup", "b", "dup"]), targetVideoId: nil,
                              startIndex: 0, shuffled: false, cursor: nil)
    #expect(q.select(at: 3)?.id == "dup")
    #expect(q.index == 3)
    #expect(q.upcoming.isEmpty)
}

@Test func needsPageFiresAtFiveRemainingAndNotAboveIt() {
    // Spec §10 "playlist paging prefetch when the queue <=5"; `PlayerViewModel.kt:1786,1911`.
    var q = PlayerQueue.start(items: items(["0","1","2","3","4","5","6"]), targetVideoId: nil,
                              startIndex: 0, shuffled: false, cursor: "P2")
    #expect(q.upcoming.count == 6)
    #expect(q.needsPage == false)
    _ = q.advance()
    #expect(q.upcoming.count == 5)
    #expect(q.needsPage)
}

@Test func needsPageIsFalseOnceThereAreNoMorePages() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: "P2")
    #expect(q.needsPage)
    q.append([], cursor: nil)                 // exhausted
    #expect(q.needsPage == false)
    #expect(q.hasMorePages == false)
}

@Test func aFailedPageStopsPagingForGood() {
    // `PlayerViewModel.kt:1946-1978`: pagingFailed is a latch, not a retry counter.
    var q = PlayerQueue.start(items: items(["a"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: "P2")
    q.markPagingFailed()
    #expect(q.cursor == "P2")                 // the continuation token is kept; the latch gates
    #expect(q.hasMorePages == false)
    #expect(q.needsPage == false)
    q.append(items(["b"]), cursor: "P3")      // a late success must not un-latch it
    #expect(q.hasMorePages == false)
}

@Test func streamPrefetchTargetsAreTheNextTwoOnly() {
    // Reconciliation note 8 + `PlayerViewModel.kt:198`: two, never five.
    let q = PlayerQueue.start(items: items(["a","b","c","d","e"]), targetVideoId: nil,
                              startIndex: 0, shuffled: false, cursor: nil)
    #expect(q.streamPrefetchTargets.map(\.id) == ["b", "c"])
}

@Test func appendDoesNotDisturbTheCurrentIndex() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 1,
                              shuffled: false, cursor: "P2")
    q.append(items(["c", "d"]), cursor: nil)
    #expect(q.current?.id == "b")
    #expect(q.upcoming.map(\.id) == ["c", "d"])
}

/// Deterministic RNG so the shuffle test is reproducible (SplitMix64).
private struct SeededGenerator: RandomNumberGenerator {
    var state: UInt64
    init(seed: UInt64) { state = seed }
    mutating func next() -> UInt64 {
        state &+= 0x9E37_79B9_7F4A_7C15
        var z = state
        z = (z ^ (z >> 30)) &* 0xBF58_476D_1CE4_E5B9
        z = (z ^ (z >> 27)) &* 0x94D0_49BB_1331_11EB
        return z ^ (z >> 31)
    }
}

@Test func autoSkipStopsAfterThreeConsecutiveFailures() {
    // `PlayerViewModel.kt:1352-1357`, MAX_CONSECUTIVE_SKIPS = 3 (`:1787`). The 4th failure
    // must NOT skip -- it shows the real error state (spec §10 "auto-skip unplayable max 3").
    #expect(AutoSkipPolicy.decide(consecutive: 0, limit: 3))
    #expect(AutoSkipPolicy.decide(consecutive: 2, limit: 3))
    #expect(AutoSkipPolicy.decide(consecutive: 3, limit: 3) == false)
}
