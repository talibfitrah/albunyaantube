import Foundation
import InnerTubeKit
import Observation

/// The five tabs in strip order (`ChannelDetailModels.kt:169-175`): never hidden, each with its
/// own empty copy.
nonisolated enum ChannelTabKind: CaseIterable, Sendable, Hashable {
    case videos, live, shorts, playlists, about

    enum SkeletonKind { case list, shortsGrid }

    var titleKey: String {
        switch self {
        case .videos: "channel_tab_videos"
        case .live: "channel_tab_live"
        case .shorts: "channel_tab_shorts"
        case .playlists: "channel_tab_playlists"
        case .about: "channel_tab_about"
        }
    }

    var emptyKey: String {
        switch self {
        case .videos: "channel_videos_empty"
        case .live: "channel_live_empty"
        case .shorts: "channel_shorts_empty"
        case .playlists: "channel_playlists_empty"
        case .about: "channel_about_no_description"
        }
    }

    /// RULING 11: the Shorts tab loads behind a 9:16 grid skeleton, never a blank rectangle.
    var skeletonKind: SkeletonKind { self == .shorts ? .shortsGrid : .list }
}

/// Android's `ChannelDetailViewModel.kt` over `BrowseSource`: header + Videos in parallel on open,
/// the other tabs lazily on first selection, in-header search per tab, the Subscribe toggle.
@MainActor @Observable final class ChannelDetailViewModel {
    nonisolated struct AboutRow: Equatable, Sendable {
        let key: String
        let text: String
    }

    let channelId: String
    /// Route fast path first (`Route.channel(id:name:avatarURL:)`), replaced by the fetched header.
    private(set) var header: ChannelHeader
    private(set) var videos: TabState<VideoItem> = .idle
    private(set) var live: TabState<VideoItem> = .idle
    private(set) var shorts: TabState<VideoItem> = .idle
    private(set) var playlists: TabState<PlaylistTile> = .idle
    var selectedTab: ChannelTabKind = .videos
    var query = ""
    private(set) var isSubscribed = false
    /// Task 30 (fork F14): an IMPORTED channel an admin has not reviewed yet.
    ///
    /// `isSubscribed` is unfiltered by design (`SubscriptionsStore`), so an AWAITING row reads as
    /// subscribed here — while the Me tab hides it from the chip rail, the Atom feed never fetches
    /// it, and it sits under Pending instead. "Subscribed", alone, is therefore a claim the rest of
    /// the app visibly does not honour. This is the second half of the state, rendered beside the
    /// button rather than instead of it: unsubscribing an import you did not mean to keep is a
    /// legitimate thing to do, and it stays one tap away.
    /// Part B gate (stage 5 M5): COMPUTED off the store rather than snapshotted, so a background
    /// pull that graduates the row (the store reloads on every sync write) flips the badge here
    /// and on the Pending tab in the same observation.
    var isAwaiting: Bool { subscriptions.awaitingItems.contains { $0.channelId == channelId } }
    /// The browse latch holds: Videos are the Atom feed, the screen shows `browse_degraded_notice`.
    private(set) var isDegraded = false
    /// RULING 14/15: the gate answered 410 -- terminal, no Retry.
    private(set) var isUnavailable = false

    private let browse: any BrowseSource
    private let subscriptions: any SubscriptionsStore
    private var generations: [ChannelTabKind: Int] = [:]

    init(channelId: String, name: String?, avatarURL: URL?, browse: any BrowseSource, subscriptions: any SubscriptionsStore) {
        self.channelId = channelId
        self.header = ChannelHeader(id: channelId, name: name ?? channelId, avatarURL: avatarURL)
        self.browse = browse
        self.subscriptions = subscriptions
        self.isSubscribed = subscriptions.isSubscribed(channelId)
    }

    var tabs: [ChannelTabKind] { ChannelTabKind.allCases }

    // MARK: - Derived

    /// Reconciliation note 4: `subscriberText` is YouTube's own localized prose ("1.2M subscribers"),
    /// rendered verbatim -- never through `channel_subscribers_format`, which would double the unit.
    /// ponytail: `BrowseClient.parseHeader` detects the row by the English word "subscriber", so
    /// under `hl=ar` this is nil and the line reads "–" on every channel (RULING 8's placeholder).
    /// Upgrade path: a localized-count parser in InnerTubeKit, not `hl=en` for the header call.
    func subscriberLine(for text: String?) -> String {
        text ?? String(localized: "channel_subscribers_unknown")
    }

    /// RULING 7: only rows that can hold data. `ChannelHeader` carries subscriber text alone --
    /// location / joined / total views / verified never arrive upstream.
    var aboutRows: [AboutRow] {
        [AboutRow(key: "subscribers", text: subscriberLine(for: header.subscriberText))]
    }

    /// The search-filtered view of a video tab (About has nothing to filter; Playlists is
    /// `visiblePlaylists`). Filtered states carry no continuation, so no trigger fires mid-search.
    func visible(_ tab: ChannelTabKind) -> TabState<VideoItem> {
        self[tab].filtered(query: query)
    }

    var visiblePlaylists: TabState<PlaylistTile> { playlists.filtered(query: query) }

    private subscript(tab: ChannelTabKind) -> TabState<VideoItem> {
        get {
            switch tab {
            case .videos: videos
            case .live: live
            case .shorts: shorts
            case .playlists, .about: .idle
            }
        }
        set {
            switch tab {
            case .videos: videos = newValue
            case .live: live = newValue
            case .shorts: shorts = newValue
            case .playlists, .about: break
            }
        }
    }

    // MARK: - Loading

    /// Header and Videos in parallel (`ChannelDetailViewModel.kt:109-124`: 300-600 ms off cold open).
    func load() async {
        isUnavailable = false
        // Synchronously, before the first suspension: `ChannelTabsView`'s `onChange(initial: true)`
        // runs `ensureTabLoaded(.videos)` on the same tick and must see a non-idle tab, not fetch twice.
        videos = .loadingInitial
        async let videosDone: Void = loadInitial(.videos)
        do {
            let fetched = try await browse.channelHeader(channelId)
            header = fetched
        } catch BrowseSourceError.unavailable {
            isUnavailable = true
        } catch {
            // Keep the route's fast-path header; the tabs decide their own fate.
        }
        await videosDone
        if isUnavailable { videos = .errorInitial(messageKey: "content_unavailable_message") }
        isDegraded = await browse.isDegraded()
    }

    /// `:374-386`: a tab loads once, on first selection; never again unless `reload`.
    func ensureTabLoaded(_ tab: ChannelTabKind) async {
        switch tab {
        case .about: return
        case .playlists: guard playlists == .idle else { return }
        default: guard self[tab] == .idle else { return }
        }
        await loadInitial(tab)
        // Cubic #14: the degraded latch can trip DURING this lazy load; refreshed only in
        // `load()`/`reload()`, the notice never surfaced mid-session.
        isDegraded = await browse.isDegraded()
    }

    /// Retry from the tab's error state.
    func reload(_ tab: ChannelTabKind) async {
        await loadInitial(tab)
        isDegraded = await browse.isDegraded()
    }

    private func loadInitial(_ tab: ChannelTabKind) async {
        let g = bump(tab)
        switch tab {
        case .playlists:
            playlists = .loadingInitial
            do {
                let page = try await browse.channelPlaylists(channelId, continuation: nil)
                guard g == generations[tab] else { return }
                playlists = Self.initial(page, emptyKey: tab.emptyKey)
            } catch {
                guard g == generations[tab] else { return }
                playlists = .errorInitial(messageKey: Self.errorKey(error))
            }
        case .about:
            return
        default:
            self[tab] = .loadingInitial
            do {
                let page = try await videoPage(tab, continuation: nil)
                guard g == generations[tab] else { return }
                self[tab] = Self.initial(page, emptyKey: tab.emptyKey)
            } catch {
                guard g == generations[tab] else { return }
                self[tab] = .errorInitial(messageKey: Self.errorKey(error))
            }
        }
    }

    /// Next page from the surviving cursor; false when nothing started (`ChannelTabAutofill`'s commit contract).
    func loadMore(_ tab: ChannelTabKind) async -> Bool {
        if tab == .playlists {
            guard let c = visiblePlaylists.continuation, !playlists.isAppending else { return false }
            let current = playlists.items
            playlists = .loaded(items: current, continuation: c, isAppending: true, showsLoadMore: false)
            let g = bump(tab)
            do {
                let page = try await browse.channelPlaylists(channelId, continuation: c)
                guard g == generations[tab] else { return true }
                playlists = .loaded(items: current + page.items, continuation: page.nextContinuation, isAppending: false, showsLoadMore: false)
            } catch {
                guard g == generations[tab] else { return true }
                playlists = .errorAppend(messageKey: "load_more_error", items: current, continuation: c, showsLoadMore: false)
            }
            isDegraded = await browse.isDegraded()   // Cubic #14: the latch can trip on a page fetch too
            return true
        }
        guard let c = visible(tab).continuation, !self[tab].isAppending else { return false }
        let current = self[tab].items
        self[tab] = .loaded(items: current, continuation: c, isAppending: true, showsLoadMore: false)
        let g = bump(tab)
        do {
            let page = try await videoPage(tab, continuation: c)
            guard g == generations[tab] else { return true }
            self[tab] = .loaded(items: current + page.items, continuation: page.nextContinuation, isAppending: false, showsLoadMore: false)
        } catch {
            guard g == generations[tab] else { return true }
            self[tab] = .errorAppend(messageKey: "load_more_error", items: current, continuation: c, showsLoadMore: false)
        }
        isDegraded = await browse.isDegraded()   // Cubic #14
        return true
    }

    private func videoPage(_ tab: ChannelTabKind, continuation: String?) async throws -> BrowsePage<VideoItem> {
        switch tab {
        case .videos: try await browse.channelVideos(channelId, continuation: continuation)   // the Videos tab, ruling 3
        case .live: try await browse.channelTab(channelId, tab: .live, continuation: continuation)
        case .shorts: try await browse.channelTab(channelId, tab: .shorts, continuation: continuation)
        case .playlists, .about: BrowsePage(items: [], nextContinuation: nil)
        }
    }

    private func bump(_ tab: ChannelTabKind) -> Int {
        let next = (generations[tab] ?? 0) + 1
        generations[tab] = next
        return next
    }

    /// CF-C-7: never `.loaded([])`.
    private static func initial<T>(_ page: BrowsePage<T>, emptyKey: String) -> TabState<T> {
        page.items.isEmpty ? .empty(messageKey: emptyKey)
            : .loaded(items: page.items, continuation: page.nextContinuation, isAppending: false, showsLoadMore: false)
    }

    /// Never `localizedDescription` -- catalog keys only.
    private static func errorKey(_ error: any Error) -> String {
        if case BrowseSourceError.unavailable = error { return "content_unavailable_message" }
        return "channel_tab_error_generic"
    }

    // MARK: - Taps

    /// RULING 39 + 66: the header's real name and this channel's id ride on every tap; a Short adds
    /// the avatar (B4's overlay), a live stream the report subtype (`ChannelLiveTabFragment.kt:62-69`).
    func playerArgs(for item: VideoItem, tab: ChannelTabKind) -> PlayerArgs {
        var args = PlayerArgs(videoId: item.id, title: item.title, channelName: header.name, thumbnailURL: item.thumbnailURL,
                              durationSeconds: item.durationSeconds, channelId: channelId)
        args.isLive = tab == .live
        if tab == .shorts { args.channelAvatarURL = header.avatarURL }
        return args
    }

    // MARK: - Subscribe

    /// The store is synchronous, so the flag is simply re-read after the toggle. Returns the
    /// message key to show on failure, nil on success.
    func toggleSubscribed() -> String? {
        // Unsubscribing an awaiting import tombstones the row, which drops it out of
        // `awaitingItems` too — so the badge (computed) cannot outlive the subscription it explains.
        defer { isSubscribed = subscriptions.isSubscribed(channelId) }
        do {
            try subscriptions.toggle(id: channelId, name: header.name, avatarURL: header.avatarURL)
            return nil
        } catch SubscriptionsError.capReached {
            return "me_subscription_cap_reached"
        } catch {
            return "error_state_generic_headline"
        }
    }
}
