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
    #expect(Set(q.upcoming.map(\.id)) == Set(["a", "b", "c", "e"]))
    #expect(q.hasMorePages == false)          // paging disabled when shuffled
    #expect(q.needsPage == false)
}

@Test func advanceWalksForwardAndStopsAtTheEnd() {
    var q = PlayerQueue.start(items: items(["a", "b"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.advance()?.id == "b")
    #expect(q.hasNext == false)
    #expect(q.advance() == nil)               // `PlayerViewModel.kt:1920-1923`: playback stops
}

@Test func selectPlaysAnArbitraryQueuedItemAndRepositionsTheQueue() {
    // `PlayerViewModel.kt:355-387`: the Up Next tap is id-matched against the queue.
    var q = PlayerQueue.start(items: items(["a", "b", "c"]), targetVideoId: nil, startIndex: 0,
                              shuffled: false, cursor: nil)
    #expect(q.select(id: "c")?.id == "c")
    #expect(q.upcoming.isEmpty)
    #expect(q.select(id: "nope") == nil)      // desync is a no-op, never a crash
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
