import Foundation
import InnerTubeKit
import Testing
@testable import FitrahTube

/// Plan C Task 5: the channel screen's view model over a fake `BrowseSource`.
@Suite(.perTest)
struct ChannelDetailViewModelTests {

    // MARK: - Doubles

    private final class FakeSource: BrowseSource, @unchecked Sendable {
        var degraded = false
        var pages = 2
        var perPage = 5
        var headerGate: Gate?
        private(set) var calls: [String] = []

        final class Gate: @unchecked Sendable {
            private var resume: CheckedContinuation<Void, Never>?
            func wait() async { await withCheckedContinuation { resume = $0 } }
            func release() { resume?.resume(); resume = nil }
        }

        func isDegraded() async -> Bool { degraded }

        func channelHeader(_ id: String) async throws -> ChannelHeader {
            calls.append("header")
            if let headerGate { self.headerGate = nil; await headerGate.wait() }
            return ChannelHeader(id: id, name: "Alafasy", subscriberText: degraded ? nil : "1.2M subscribers",
                                 avatarURL: URL(string: "https://x/avatar.jpg"))
        }

        func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
            calls.append("videos")
            if degraded {
                // AtomFeedFetcher.latest's shape: 15 items, id/title/published/thumbnail only.
                guard continuation == nil else { return BrowsePage(items: [], nextContinuation: nil) }
                return BrowsePage(items: (0..<15).map { VideoItem(id: "atom-\($0)", title: "Atom \($0)", publishedText: "1 day ago") },
                                  nextContinuation: nil)
            }
            return page(prefix: "video", continuation: continuation)
        }

        func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> {
            calls.append("\(tab)")
            if degraded { throw BrowseError.botCheck }
            return page(prefix: "\(tab)", continuation: continuation, duration: tab == .shorts ? nil : 600)
        }

        func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> {
            calls.append("playlists")
            if degraded { throw BrowseError.botCheck }
            return BrowsePage(items: (0..<3).map { PlaylistTile(id: "PL\($0)", title: "Playlist \($0)", itemCountText: "12 videos") },
                              nextContinuation: nil)
        }

        func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> {
            BrowsePage(items: [], nextContinuation: nil)
        }

        private func page(prefix: String, continuation: String?, duration: Int? = 600) -> BrowsePage<VideoItem> {
            let n = continuation.flatMap { Int($0) } ?? 0
            let items = (0..<perPage).map { i in
                VideoItem(id: "\(prefix)-\(n)-\(i)", title: "\(prefix.capitalized) \(n * perPage + i)",
                          durationSeconds: duration, viewCountText: "1K views")
            }
            return BrowsePage(items: items, nextContinuation: n + 1 < pages ? "\(n + 1)" : nil)
        }
    }

    @Observable final class FakeSubscriptions: SubscriptionsStore {
        var ids: Set<String> = []
        /// Task 9: `UserScoped` is now a protocol requirement; this fake is never re-scoped.
        var currentUserId: String = ""
        var items: [SubscribedChannel] { [] }
        func isSubscribed(_ channelId: String) -> Bool { ids.contains(channelId) }
        func toggle(id: String, name: String?, avatarURL: URL?) throws {
            try SwiftDataSubscriptionsStore.validate(id)
            if ids.contains(id) { ids.remove(id); return }
            guard ids.count < SwiftDataSubscriptionsStore.cap else { throw SubscriptionsError.capReached }
            ids.insert(id)
        }
        /// Task 28: this fake keeps no tombstones, so "any state" is the same set.
        func containsAny(_ channelId: String) -> Bool { ids.contains(channelId) }
        /// Task 28 / CF-A-11: the cap is bypassed here too, which is the whole point of the seam.
        func importChannel(id: String, title: String, avatarUrl: String?,
                           approvalStatus: String, at: Date) throws {
            try SwiftDataSubscriptionsStore.validate(id)
            ids.insert(id)
        }
    }

    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"

    private func makeVM(source: FakeSource = FakeSource(), subscriptions: FakeSubscriptions = FakeSubscriptions(),
                        name: String? = "Route Name") -> ChannelDetailViewModel {
        ChannelDetailViewModel(channelId: Self.channelId, name: name, avatarURL: nil, browse: source, subscriptions: subscriptions)
    }

    // MARK: - Loading

    @Test func theHeaderAndTheFirstTabLoadInParallel() async {
        // ChannelDetailViewModel.kt:109-124 -- deliberate, "to cut 300-600 ms off cold open".
        let source = FakeSource()
        let gate = FakeSource.Gate()
        source.headerGate = gate
        let vm = makeVM(source: source)
        let load = Task { await vm.load() }
        for _ in 0..<100 where vm.videos.items.isEmpty { await Task.yield() }
        #expect(vm.videos.items.count == 5)          // videos landed while the header is still held
        #expect(vm.header.name == "Route Name")      // the route's fast path until the header arrives
        gate.release()
        await load.value
        #expect(vm.header.name == "Alafasy")
        #expect(vm.header.subscriberText == "1.2M subscribers")
    }

    @Test func tabsLoadLazilyOnFirstSelectionAndNeverReload() async {
        // :374-386 -- ensureTabLoaded no-ops unless the tab is .idle.
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.live == .idle)
        #expect(vm.shorts == .idle)
        #expect(vm.playlists == .idle)
        await vm.ensureTabLoaded(.live)
        await vm.ensureTabLoaded(.live)
        await vm.ensureTabLoaded(.about)
        #expect(source.calls.filter { $0 == "live" }.count == 1)
        #expect(vm.live.items.count == 5)
        await vm.ensureTabLoaded(.videos)
        #expect(source.calls.filter { $0 == "videos" }.count == 1)
    }

    @Test func allFiveTabsExistEvenWhenEmpty() async {
        // ChannelDetailModels.kt:169-175 -- tabs are never hidden; each shows its own empty state.
        let source = FakeSource()
        source.perPage = 0
        source.pages = 1
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.tabs.count == 5)
        #expect(ChannelTabKind.allCases.map(\.titleKey) == ["channel_tab_videos", "channel_tab_live", "channel_tab_shorts", "channel_tab_playlists", "channel_tab_about"])
        #expect(vm.videos == .empty(messageKey: "channel_videos_empty"))
        await vm.ensureTabLoaded(.live)
        await vm.ensureTabLoaded(.shorts)
        #expect(vm.live == .empty(messageKey: "channel_live_empty"))
        #expect(vm.shorts == .empty(messageKey: "channel_shorts_empty"))
    }

    @Test func anUnknownSubscriberCountRendersTheDashNotAFormattedZero() {
        // RULING 8 + reconciliation note 4: BrowseClient gives us a STRING, and its detection heuristic
        // is English-only (BrowseClient.swift:253), so nil is the expected Arabic result.
        let vm = makeVM()
        #expect(vm.subscriberLine(for: nil) == String(localized: "channel_subscribers_unknown"))
        #expect(vm.subscriberLine(for: "1.2M subscribers") == "1.2M subscribers")  // verbatim, NOT re-formatted
    }

    // MARK: - Degraded mode

    @Test func aBotCheckedVideosTabDegradesToTheAtomFeed() async {
        // CF-C3 + plan 11 ("degraded mode ... is automatic"). Fifteen items, no continuation, and the
        // notice banner -- not an error state, and not a silent short list.
        let source = FakeSource()
        source.degraded = true
        let vm = makeVM(source: source)
        await vm.load()
        #expect(vm.videos.items.count == 15)
        #expect(vm.videos.continuation == nil)
        #expect(vm.isDegraded)
        #expect(vm.header.subscriberText == nil)
    }

    @Test func aBotCheckedLiveShortsAndPlaylistsTabsShowErrorsBecauseThereIsNoSubstitute() async {
        // Task 2's degraded table: only the header and Videos have a degraded source. Rendering any of
        // the three empty would claim the channel has no streams / no Shorts / no playlists.
        let source = FakeSource()
        source.degraded = true
        let vm = makeVM(source: source)
        await vm.load()
        await vm.ensureTabLoaded(.live)
        await vm.ensureTabLoaded(.shorts)
        await vm.ensureTabLoaded(.playlists)
        #expect(vm.live == .errorInitial(messageKey: "channel_tab_error_generic"))
        #expect(vm.shorts == .errorInitial(messageKey: "channel_tab_error_generic"))
        #expect(vm.playlists == .errorInitial(messageKey: "channel_tab_error_generic"))
        // Retry re-probes (the source decides whether it is still latched).
        source.degraded = false
        await vm.reload(.live)
        #expect(vm.live.items.count == 5)
    }

    @Test func aDegradedLatchDuringALazyTabLoadSurfacesTheNotice() async {
        // Cubic #14: `isDegraded` was refreshed only in `load()`/`reload()` -- a latch that fired
        // during `ensureTabLoaded` (first tap on another tab) never surfaced the degraded banner.
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        #expect(!vm.isDegraded)
        source.degraded = true                    // the browse source latches mid-session
        await vm.ensureTabLoaded(.live)
        #expect(vm.isDegraded)
    }

    @Test func aDegradedLatchDuringLoadMoreSurfacesTheNotice() async {
        // The other mid-session path Cubic #14 names: paging an already-open tab.
        let source = FakeSource()
        let vm = makeVM(source: source)
        await vm.load()
        #expect(!vm.isDegraded)
        source.degraded = true
        _ = await vm.loadMore(.videos)
        #expect(vm.isDegraded)
    }

    @Test func aGate410OnTheHeaderIsTerminal() async {
        final class Blocked: BrowseSource, @unchecked Sendable {
            func isDegraded() async -> Bool { false }
            func channelHeader(_ id: String) async throws -> ChannelHeader { throw BrowseSourceError.unavailable }
            func channelVideos(_ id: String, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
            func channelTab(_ id: String, tab: ChannelTab, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
            func channelPlaylists(_ id: String, continuation: String?) async throws -> BrowsePage<PlaylistTile> { throw BrowseSourceError.unavailable }
            func playlistItems(_ playlistId: String, continuation: String?) async throws -> BrowsePage<VideoItem> { throw BrowseSourceError.unavailable }
        }
        let vm = ChannelDetailViewModel(channelId: Self.channelId, name: nil, avatarURL: nil, browse: Blocked(), subscriptions: FakeSubscriptions())
        await vm.load()
        #expect(vm.isUnavailable)
        #expect(vm.videos == .errorInitial(messageKey: "content_unavailable_message"))
        #expect(vm.header.name == Self.channelId)   // nothing better to show than the id
    }

    // MARK: - Taps

    @Test func aVideoTapCarriesTheRealChannelNameAndTheChannelId() async {
        // RULING 39 (channelName <- category was the Android bug) + RULING 66 (the report context).
        let vm = makeVM()
        await vm.load()
        let item = vm.videos.items[1]
        let args = vm.playerArgs(for: item, tab: .videos)
        #expect(args.channelName == "Alafasy")
        #expect(args.channelId == "UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(args.videoId == "video-0-1")
        #expect(args.title == "Video 1")
        #expect(args.playlistId == nil)
        #expect(args.reportContext.parentType == .channel)
        #expect(args.reportContext.parentId == "UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(args.reportContext.contentSubType == nil)
    }

    @Test func aLiveTapCarriesTheLivestreamSubtype() async {
        // ChannelLiveTabFragment.kt:62-69
        let vm = makeVM()
        await vm.load()
        await vm.ensureTabLoaded(.live)
        let args = vm.playerArgs(for: vm.live.items[0], tab: .live)
        #expect(args.isLive)
        #expect(args.reportContext.contentSubType == .livestream)
        #expect(vm.playerArgs(for: vm.videos.items[0], tab: .videos).isLive == false)
    }

    @Test func aShortsTapCarriesTheMetadataFastPathIncludingTheAvatar() async {
        // B4 Task 1's Route.shorts(PlayerArgs) integration: id, title, thumbnail, channelId/Name, avatar.
        let vm = makeVM()
        await vm.load()
        await vm.ensureTabLoaded(.shorts)
        let args = vm.playerArgs(for: vm.shorts.items[0], tab: .shorts)
        #expect(args.videoId == "shorts-0-0")
        #expect(args.channelId == Self.channelId)
        #expect(args.channelName == "Alafasy")
        #expect(args.channelAvatarURL == URL(string: "https://x/avatar.jpg"))
    }

    // MARK: - Search

    @Test func searchFiltersEachTabIndependentlyAndLeavesAboutAlone() async {
        // brief 5.4
        let vm = makeVM()
        await vm.load()
        await vm.ensureTabLoaded(.live)
        await vm.ensureTabLoaded(.playlists)
        vm.query = "Live 3"
        #expect(vm.visible(.live).items.map(\.id) == ["live-0-3"])
        #expect(vm.visible(.videos).emptyMessageKey == "search_no_results")
        #expect(vm.visiblePlaylists.emptyMessageKey == "search_no_results")
        #expect(vm.visible(.live).continuation == nil)
        #expect(await vm.loadMore(.live) == false)
        #expect(vm.aboutRows.count == 1)   // About never filters
        vm.query = "playlist 1"
        #expect(vm.visiblePlaylists.items.map(\.id) == ["PL1"])
        vm.query = ""
        #expect(vm.visible(.videos).continuation == "1")
    }

    // MARK: - Pagination

    @Test func loadMoreAppendsAndAnAppendFailureKeepsTheItems() async {
        let vm = makeVM()
        await vm.load()
        #expect(await vm.loadMore(.videos))
        #expect(vm.videos.items.count == 10)
        #expect(vm.videos.continuation == nil)
        #expect(await vm.loadMore(.videos) == false)
    }

    // MARK: - Skeleton / About

    @Test func theShortsTabRendersItsSkeletonWhileLoading() {
        // RULING 11, fixing defect 1: Android's shorts skeleton RecyclerView never gets an adapter
        // (fragment_channel_shorts_tab.xml:28-39), so its loading state is a blank rectangle.
        #expect(ChannelTabKind.shorts.skeletonKind == .shortsGrid)
        #expect(ChannelTabKind.videos.skeletonKind == .list)
    }

    @Test func theAboutTabOmitsTheRowsThatCanNeverHoldData() async {
        // RULING 7 -- location / joinedDate / totalViews are always nil upstream
        // (NewPipeChannelDetailRepository.kt:740-746). `ChannelHeader` carries no verified flag either.
        let vm = makeVM()
        await vm.load()
        #expect(vm.aboutRows.map(\.key) == ["subscribers"])
        #expect(vm.aboutRows.map(\.text) == ["1.2M subscribers"])
    }

    // MARK: - Subscribe

    @Test func subscribeIsOptimisticAndTheCapSurfacesItsOwnMessage() async {
        let subs = FakeSubscriptions()
        let vm = makeVM(subscriptions: subs)
        await vm.load()
        #expect(vm.isSubscribed == false)
        #expect(vm.toggleSubscribed() == nil)
        #expect(vm.isSubscribed)
        #expect(subs.ids == [Self.channelId])
        #expect(vm.toggleSubscribed() == nil)
        #expect(vm.isSubscribed == false)
        subs.ids = Set((0..<30).map { "UCchannel\($0)" })
        #expect(vm.toggleSubscribed() == "me_subscription_cap_reached")
        #expect(vm.isSubscribed == false)
    }
}
