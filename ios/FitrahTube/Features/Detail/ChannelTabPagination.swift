import Foundation
import InnerTubeKit

/// Per-tab state for the channel screen's five tabs and the playlist screen (Plan C Task 2). Ports
/// Android's machine (`ChannelDetailViewModel.kt:1091-1108`) with the error payloads carried, so an
/// append failure never blanks a populated list. `Item` is `VideoItem` for four tabs and
/// `PlaylistTile` for the fifth.
nonisolated enum TabState<Item: Sendable & Equatable>: Sendable, Equatable {
    case idle
    case loadingInitial
    /// Contract (CF-C-7): never construct with empty `items` -- an empty tab is `.empty(messageKey:)`
    /// with its own copy. The only zero-item state this file produces is `filtered(query:)`'s
    /// `.empty(messageKey: "search_no_results")`; a `.loaded([])` renders nothing at all.
    case loaded(items: [Item], continuation: String?, isAppending: Bool, showsLoadMore: Bool)
    /// `messageKey` is the tab's own empty copy (`channel_videos_empty`, `channel_live_empty`, …),
    /// distinct from a zero-match search, which `emptyMessageKey` answers with `search_no_results`
    /// (RULING 5 -- Android's "This channel has no videos yet" on a miss lies about the channel).
    case empty(messageKey: String)
    case errorInitial(messageKey: String)
    case errorAppend(messageKey: String, items: [Item], continuation: String?, showsLoadMore: Bool)

    var items: [Item] {
        switch self {
        case .loaded(let items, _, _, _), .errorAppend(_, let items, _, _): items
        default: []
        }
    }

    var continuation: String? {
        switch self {
        case .loaded(_, let continuation, _, _), .errorAppend(_, _, let continuation, _): continuation
        default: nil
        }
    }

    var isAppending: Bool {
        if case .loaded(_, _, true, _) = self { return true }
        return false
    }

    var showsLoadMore: Bool {
        switch self {
        case .loaded(_, let c, _, let shows), .errorAppend(_, _, let c, let shows): shows && c != nil
        default: false
        }
    }

    /// The `.empty` key (a tab's own copy, or `search_no_results` from `filtered`), nil otherwise.
    var emptyMessageKey: String? {
        if case .empty(let key) = self { return key }
        return nil
    }

    /// `BaseChannelListTabFragment.kt:269-294` / `PlaylistDetailFragment.kt:336`: the filtered state
    /// carries no continuation, so the near-end trigger cannot fire mid-search, and no Load-more
    /// button either. An empty query is the identity; zero matches is `.empty("search_no_results")`
    /// (RULING 5), so `.loaded` never carries an empty list.
    func filtered(query: String, matches: (Item, String) -> Bool) -> TabState {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return self }
        switch self {
        case .loaded(let items, _, _, _), .errorAppend(_, let items, _, _):
            let hits = items.filter { matches($0, trimmed) }
            guard !hits.isEmpty else { return .empty(messageKey: "search_no_results") }
            return .loaded(items: hits, continuation: nil, isAppending: false, showsLoadMore: false)
        default:
            return self
        }
    }

    /// `ChannelDetailViewModel.kt:951-979`: keep the items and the cursor, surface the message.
    func appendFailed(messageKey: String) -> TabState {
        switch self {
        case .loaded(let items, let c, _, let shows), .errorAppend(_, let items, let c, let shows):
            .errorAppend(messageKey: messageKey, items: items, continuation: c, showsLoadMore: shows)
        default:
            .errorInitial(messageKey: messageKey)
        }
    }
}

extension TabState where Item == VideoItem {
    func filtered(query: String) -> TabState { filtered(query: query, matches: SearchFilter.matches) }
}

extension TabState where Item == PlaylistTile {
    func filtered(query: String) -> TabState { filtered(query: query, matches: SearchFilter.matches) }
}

/// The channel tabs' pagination machine (ruling 10; reconciliation note 2) -- deliberately NOT
/// `PaginationGuard`, whose guard 1 refuses to autofill on compact at all. Here a phone autofills
/// once and a regular width twice (`BaseChannelListTabFragment.kt:231-234,399-404`); past the cap a
/// "Load more" button takes over, and an explicit tap renews the budget (`:216-225,240-243`).
/// Appends are rate-limited to one per second with a single 1 100 ms re-check after a rejection
/// (`ChannelDetailViewModel.kt:283-289`, `BaseChannelListTabFragment.kt:76-112,410-415`).
nonisolated struct ChannelTabAutofill: Sendable, Equatable {
    static let minAppendInterval: TimeInterval = 1.0
    static let recheckDelay: TimeInterval = 1.1

    private(set) var autofilledPages = 0
    private(set) var lastAcceptedAt: Date?
    private(set) var recheckPending = false
    private(set) var showsLoadMore = false
    /// Bumped by `reset()` alone (PaginationGuard's discipline, gate wave-4 V1/V6): a copy taken
    /// before a search-change reset is refused at commit time.
    private(set) var generation = 0

    static func cap(_ widthClass: WidthClass) -> Int { widthClass == .compact ? 1 : 2 }

    mutating func shouldAutoLoad(widthClass: WidthClass, hasMore: Bool, isAppending: Bool, contentFits: Bool) -> Bool {
        guard hasMore else { showsLoadMore = false; return false }
        guard !isAppending, contentFits else { return false }
        guard autofilledPages < Self.cap(widthClass) else { showsLoadMore = true; return false }
        return true
    }

    /// The 1 s minimum between accepted appends.
    func accepts(at now: Date) -> Bool {
        lastAcceptedAt.map { now.timeIntervalSince($0) >= Self.minAppendInterval } ?? true
    }

    /// An accepted append spends one autofill page and clears any pending re-check. A rejected one
    /// returns the delay for the single re-check it schedules, or nil if one is already pending.
    /// Caller contract: the timer calls `recheckFired()` BEFORE re-evaluating, so a rejection of the
    /// re-check itself can schedule another (Android nulls the job in `finally`).
    @discardableResult
    mutating func recordAppend(accepted: Bool, at now: Date) -> TimeInterval? {
        if accepted {
            autofilledPages += 1
            lastAcceptedAt = now
            recheckPending = false
            return nil
        }
        guard !recheckPending else { return nil }
        recheckPending = true
        return Self.recheckDelay
    }

    /// The scheduled re-check has fired: release the slot whatever the re-evaluation decides.
    mutating func recheckFired() {
        recheckPending = false
    }

    /// Renews the autofill budget without invalidating in-flight copies (generation kept).
    mutating func loadMoreTapped() {
        autofilledPages = 0
        showsLoadMore = false
    }

    /// External reset -- search change, tab reload, pull-to-refresh.
    mutating func reset() {
        let next = generation + 1
        self = ChannelTabAutofill()
        generation = next
    }
}

/// In-header search over the loaded items (spec §9): trimmed, case- and diacritic-insensitive
/// substring over title OR channel name. Never a network call.
nonisolated enum SearchFilter {
    static func apply(_ items: [VideoItem], query: String) -> [VideoItem] { filter(items, query: query, matches: matches) }
    static func apply(_ items: [PlaylistTile], query: String) -> [PlaylistTile] { filter(items, query: query, matches: matches) }

    static func matches(_ item: VideoItem, _ query: String) -> Bool { matches([item.title, item.channelName], query) }
    static func matches(_ item: PlaylistTile, _ query: String) -> Bool { matches([item.title, item.channelName], query) }

    private static func filter<T>(_ items: [T], query: String, matches: (T, String) -> Bool) -> [T] {
        let trimmed = query.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return items }
        return items.filter { matches($0, trimmed) }
    }

    private static func matches(_ fields: [String?], _ query: String) -> Bool {
        let needle = fold(query)
        return fields.contains { $0.map { fold($0).contains(needle) } ?? false }
    }

    /// Lowercase + drop every combining mark: `.diacriticInsensitive` handles Latin accents but
    /// leaves Arabic harakat (U+064B..U+0652) in place, so a bare query would miss a vowelled title.
    private static func fold(_ s: String) -> String {
        let scalars = s.lowercased().decomposedStringWithCanonicalMapping.unicodeScalars
            .filter { $0.properties.generalCategory != .nonspacingMark }
        return String(String.UnicodeScalarView(scalars))
    }
}
