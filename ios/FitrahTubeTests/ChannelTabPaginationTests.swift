import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Plan C Task 2: the channel tabs' pagination machine (`ChannelTabAutofill`), the per-tab state
/// (`TabState`) and the in-header search filter. All pure, no `await`, no wall clock.
@Suite(.perTest)
struct ChannelTabPaginationTests {
    private static func videos(_ count: Int) -> [VideoItem] {
        (0..<count).map { VideoItem(id: "v\($0)", title: "Lecture \($0)", channelName: "Channel") }
    }

    // MARK: - ChannelTabAutofill

    @Test func compactAutofillsExactlyOnePageThenOffersTheButton() {
        // ruling 10 / BaseChannelListTabFragment.kt:231-234,399-404 -- the channel tabs' cap is 1 on a
        // phone and 2 at >=600pt, and what follows the cap is a BUTTON, not silence. This is the whole
        // reason PaginationGuard (which refuses to autofill on compact at all) is not reused here.
        var a = ChannelTabAutofill()
        let r1 = a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true)
        #expect(r1)
        a.recordAppend(accepted: true, at: .now)
        let r2 = a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true)
        #expect(r2 == false)
        #expect(a.showsLoadMore)
    }

    @Test func regularAutofillsTwice() {
        var a = ChannelTabAutofill()
        let r3 = a.shouldAutoLoad(widthClass: .regular, hasMore: true, isAppending: false, contentFits: true)
        #expect(r3)
        a.recordAppend(accepted: true, at: .now)
        #expect(a.showsLoadMore == false)
        let r4 = a.shouldAutoLoad(widthClass: .regular, hasMore: true, isAppending: false, contentFits: true)
        #expect(r4)
        a.recordAppend(accepted: true, at: .now)
        let r5 = a.shouldAutoLoad(widthClass: .regular, hasMore: true, isAppending: false, contentFits: true)
        #expect(r5 == false)
        #expect(a.showsLoadMore)
    }

    @Test func noMoreOrInFlightOrNotFittingNeverAutofills() {
        var a = ChannelTabAutofill()
        let r6 = a.shouldAutoLoad(widthClass: .regular, hasMore: false, isAppending: false, contentFits: true)
        #expect(r6 == false)
        #expect(a.showsLoadMore == false) // an exhausted list has no button to offer
        let r7 = a.shouldAutoLoad(widthClass: .regular, hasMore: true, isAppending: true, contentFits: true)
        #expect(r7 == false)
        let r8 = a.shouldAutoLoad(widthClass: .regular, hasMore: true, isAppending: false, contentFits: false)
        #expect(r8 == false)
    }

    @Test func appendsAreRateLimitedToOnePerSecond() {
        // ChannelDetailViewModel.kt:283-289 + MIN_APPEND_INTERVAL_MS (:1137).
        var a = ChannelTabAutofill()
        let t0 = Date(timeIntervalSince1970: 0)
        #expect(a.accepts(at: t0))
        a.recordAppend(accepted: true, at: t0)
        #expect(a.accepts(at: t0.addingTimeInterval(0.9)) == false)
        #expect(a.accepts(at: t0.addingTimeInterval(1.0)))
    }

    @Test func aRejectedAppendSchedulesExactlyOneRecheck() {
        // BaseChannelListTabFragment.kt:76-112,410-415: a rejected (too-soon) append schedules ONE
        // re-check 1 100 ms later; a second rejection while it is pending schedules nothing.
        var a = ChannelTabAutofill()
        let t0 = Date(timeIntervalSince1970: 0)
        a.recordAppend(accepted: true, at: t0)
        let r9 = a.recordAppend(accepted: false, at: t0.addingTimeInterval(0.5))
        #expect(r9 == 1.1)
        let r10 = a.recordAppend(accepted: false, at: t0.addingTimeInterval(0.6))
        #expect(r10 == nil)
        a.recordAppend(accepted: true, at: t0.addingTimeInterval(1.2))
        let r11 = a.recordAppend(accepted: false, at: t0.addingTimeInterval(1.3))
        #expect(r11 == 1.1)
    }

    @Test func aFiredRecheckReleasesTheSlotForTheNextRejection() {
        // Review I1: the recheck fires 1.1 s later and re-evaluates; if that evaluation is rejected too
        // (a Load-more append landed 0.4 s earlier) the slot must be free again or the list stalls
        // forever -- Android nulls the job in `finally` (BaseChannelListTabFragment.kt:76-112).
        var a = ChannelTabAutofill()
        let t0 = Date(timeIntervalSince1970: 0)
        a.recordAppend(accepted: true, at: t0)
        #expect(a.recordAppend(accepted: false, at: t0.addingTimeInterval(0.5)) == 1.1)
        var stalled = a
        #expect(stalled.recordAppend(accepted: false, at: t0.addingTimeInterval(1.6)) == nil) // without recheckFired()
        a.recheckFired()
        let r13 = a.recordAppend(accepted: false, at: t0.addingTimeInterval(1.6))
        #expect(r13 == 1.1)
    }

    @Test func loadMoreTapResetsTheAutofillBudget() {
        // BaseChannelListTabFragment.kt:216-225,240-243 -- an explicit tap renews the counter, so a
        // second screenful can autofill again. Without this the button appears once and then the list
        // is manual forever.
        var a = ChannelTabAutofill()
        _ = a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true)
        a.recordAppend(accepted: true, at: .now)
        _ = a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true)
        #expect(a.showsLoadMore)
        a.loadMoreTapped()
        #expect(a.showsLoadMore == false)
        let r12 = a.shouldAutoLoad(widthClass: .compact, hasMore: true, isAppending: false, contentFits: true)
        #expect(r12)
    }

    @Test func resetBumpsTheGenerationSoAStaleCopyIsRefused() {
        // Same discipline as PaginationGuard (gate wave-4 V1/V6): a copy taken before a search-change
        // reset must not be committed after it.
        var a = ChannelTabAutofill()
        let copy = a
        a.reset()
        #expect(copy.generation != a.generation)
        #expect(a.showsLoadMore == false)
    }

    // MARK: - TabState

    @Test func searchDisablesPaginationByDroppingTheCursor() {
        // BaseChannelListTabFragment.kt:269-294 / PlaylistDetailFragment.kt:336 -- the filtered Loaded
        // state carries nextPage = nil, so the near-end trigger cannot fire mid-search.
        let items = Self.videos(3)
        let filtered = TabState.loaded(items: items, continuation: "TOKEN", isAppending: false, showsLoadMore: true)
            .filtered(query: "lecture")
        #expect(filtered.continuation == nil)
        #expect(filtered.items.count == 3)
        #expect(filtered == .loaded(items: items, continuation: nil, isAppending: false, showsLoadMore: false))
    }

    @Test func anEmptyQueryLeavesTheStateUntouched() {
        let state = TabState.loaded(items: Self.videos(2), continuation: "TOKEN", isAppending: false, showsLoadMore: true)
        #expect(state.filtered(query: "   ") == state)
    }

    @Test func zeroMatchesRendersTheSearchNoResultsCopyNotTheTabsEmptyCopy() {
        // RULING 5, fixing Android defect 6 (brief 5.4): Android shows "This channel has no videos yet"
        // when a search matches nothing, which is a lie about the channel.
        let loaded = TabState.loaded(items: Self.videos(3), continuation: "TOKEN", isAppending: false, showsLoadMore: false)
        #expect(loaded.filtered(query: "zzzz").emptyMessageKey == "search_no_results")
        #expect(TabState<VideoItem>.empty(messageKey: "channel_videos_empty").emptyMessageKey == "channel_videos_empty")
        #expect(loaded.emptyMessageKey == nil)
    }

    @Test func appendFailureKeepsTheItemsAndTheCursor() {
        // ChannelDetailViewModel.kt:951-979 -- an append error must never blank a populated list.
        let items = Self.videos(5)
        let state = TabState.loaded(items: items, continuation: "TOKEN", isAppending: true, showsLoadMore: false)
            .appendFailed(messageKey: "load_more_error")
        #expect(state == .errorAppend(messageKey: "load_more_error", items: items, continuation: "TOKEN", showsLoadMore: false))
        #expect(state.items == items)
        #expect(state.continuation == "TOKEN")
        // ...and a search over the failed state still filters the items it kept.
        #expect(state.filtered(query: "Lecture 4").items.map(\.id) == ["v4"])
    }

    // MARK: - SearchFilter

    @Test func searchIsTrimmedCaseAndDiacriticInsensitiveOverTitleOrChannel() {
        let items = [
            VideoItem(id: "a", title: "Tafsīr of Sūrah al-Fātiḥah", channelName: "Masjid"),
            VideoItem(id: "b", title: "Friday khutbah", channelName: "Madīnah Lectures"),
            VideoItem(id: "c", title: "Seerah part 3", channelName: nil),
        ]
        #expect(SearchFilter.apply(items, query: "  TAFSIR ").map(\.id) == ["a"])
        #expect(SearchFilter.apply(items, query: "madinah").map(\.id) == ["b"])
        #expect(SearchFilter.apply(items, query: "").map(\.id) == ["a", "b", "c"])
        let tiles = [PlaylistTile(id: "p", title: "Ramadān series", channelName: "Masjid")]
        #expect(SearchFilter.apply(tiles, query: "ramadan").map(\.id) == ["p"])
        #expect(SearchFilter.apply(tiles, query: "nothing").isEmpty)
        // Arabic harakat are combining marks: a bare query must match a vowelled title.
        let arabic = [VideoItem(id: "q", title: "تفسير القُرْآن", channelName: nil)]
        #expect(SearchFilter.apply(arabic, query: "القرآن").map(\.id) == ["q"])
    }
}
