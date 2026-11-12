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
    /// with its own copy; a `.loaded([])` renders nothing at all.
    case loaded(items: [Item], continuation: String?, isAppending: Bool, showsLoadMore: Bool)
    /// `messageKey` is the tab's own empty copy (`channel_videos_empty`, `channel_live_empty`, …).
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

    /// Renews the autofill budget (an in-flight append still commits).
    mutating func loadMoreTapped() {
        autofilledPages = 0
        showsLoadMore = false
    }
}
