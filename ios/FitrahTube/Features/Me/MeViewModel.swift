import Foundation
import Observation

/// One row of the signed-in Me screen's chip rail: a subscribed channel or a saved playlist,
/// merged into ONE list. `addedAt` is `SubscribedChannel.followedAt` / `SavedPlaylist.addedAt`.
nonisolated struct MeChipItem: Sendable, Equatable, Identifiable {
    enum Kind: Sendable, Equatable { case channel, playlist }
    var id: String
    var title: String
    var avatarURL: URL?
    var addedAt: Date
    var kind: Kind
}

/// Menu order = `res/menu/menu_me_kebab.xml`. There is deliberately NO `.history` and no
/// `.recentlyWatched` case: ruling F10 / RULING 28 — a row that promises "coming soon" is a dead
/// affordance, and `MeViewModelTests.thereIsNoHistoryOrRecentlyWatchedKebabItem` pins the ABSENCE.
nonisolated enum MeKebabItem: Sendable, Equatable, CaseIterable {
    case profile, mySubmissions, suggestContent, importYouTube, signOut

    var titleKey: String {
        switch self {
        case .profile: "me_kebab_profile"
        case .mySubmissions: "my_submissions_title"
        case .suggestContent: "me_kebab_suggest_content"
        case .importYouTube: "me_kebab_import_youtube"
        case .signOut: "me_kebab_sign_out"
        }
    }

    var symbolName: String {
        switch self {
        case .profile: "person.crop.circle"
        case .mySubmissions: "tray"
        case .suggestContent: "plus.circle"
        case .importYouTube: "square.and.arrow.down.on.square"
        case .signOut: "rectangle.portrait.and.arrow.right"
        }
    }

    /// BOTH moderator items move together (ruling C4) — `MeFragment.kt:270-273` flips
    /// `action_my_submissions` and `action_suggest_content` off the same boolean.
    static func items(isModerator: Bool) -> [MeKebabItem] {
        allCases.filter { item in
            switch item {
            case .mySubmissions, .suggestContent: isModerator
            default: true
            }
        }
    }

    /// The kebab rows that have a destination TODAY. RULING 28: a row with nowhere to go is not
    /// rendered greyed, it is not rendered at all — a greyed row is still a visible promise, and
    /// three of the four missing ones are Part B, so they would sit on this screen through all of
    /// Part A and Task 19's screenshot matrix. Each later task that lands a destination adds its
    /// own case here plus its own assertion (Task 17 `.profile`, 25 `.mySubmissions`,
    /// 27 `.suggestContent`, 29 `.importYouTube`), so every re-enable is a named test edit.
    ///
    /// `.mySubmissions` is the first ROLE-GATED row to land: `items(isModerator:)` decides whether
    /// it is offered at all (ruling C4) and this set decides whether it has anywhere to go, so a
    /// plain user's kebab is unchanged by Task 25. Task 27 lands the second half of the same C4
    /// pair, `.suggestContent`; a plain user's kebab is unchanged again.
    static let landed: Set<MeKebabItem> = [.profile, .mySubmissions, .suggestContent, .signOut]
}

/// Ruling C5's signed-in Me screen, over LOCAL stores only. No feed (Tasks 14-16), no History
/// rows (F10), no Content/Pending tabs (F14).
@MainActor @Observable final class MeViewModel {
    private let session: AccountSession
    private let favorites: any FavoritesStore
    private let subscriptions: any SubscriptionsStore
    private let savedPlaylists: any SavedPlaylistsStore

    /// `MeFavoritesAdapter:19-20,45` — 20 tiles, plus a trailing "See all".
    static let maxFavoriteTiles = 20

    private var rawSelection: String?

    init(session: AccountSession, favorites: any FavoritesStore,
         subscriptions: any SubscriptionsStore, savedPlaylists: any SavedPlaylistsStore) {
        self.session = session
        self.favorites = favorites
        self.subscriptions = subscriptions
        self.savedPlaylists = savedPlaylists
    }

    /// Channels + playlists MERGED and sorted by add time, descending — never segregated.
    /// Android's comment (`MeViewModel.kt:406-411`) records why: splitting them pushed a
    /// freshly-saved playlist off-screen in RTL. `id` breaks ties so the order is deterministic
    /// (two rows written in the same millisecond would otherwise shuffle between renders).
    var chips: [MeChipItem] {
        let channels = subscriptions.items.map {
            MeChipItem(id: $0.channelId, title: $0.title, avatarURL: $0.avatarUrl.flatMap(URL.init(string:)),
                       addedAt: $0.followedAt, kind: .channel)
        }
        let playlists = savedPlaylists.items.map {
            MeChipItem(id: $0.playlistId, title: $0.title, avatarURL: $0.thumbnailUrl.flatMap(URL.init(string:)),
                       addedAt: $0.addedAt, kind: .playlist)
        }
        return (channels + playlists).sorted {
            $0.addedAt == $1.addedAt ? $0.id < $1.id : $0.addedAt > $1.addedAt
        }
    }

    /// What `MeFeedRepository.refresh(channelIds:force:)` fans out over: the UNFILTERED subscribed
    /// channels. Saved playlists are deliberately absent — the Atom feed is per channel, and the
    /// chip rail merges both kinds only for filtering.
    var subscribedChannelIds: [String] { subscriptions.items.map(\.channelId) }

    /// The store is already sorted most-recently-added first, so this is just the cap.
    var favoriteTiles: [FavoriteVideo] { Array(favorites.items.prefix(Self.maxFavoriteTiles)) }

    /// `MeFragment.kt:270-271` compares `ignoreCase = true`; `AccountMe.isModerator` carries that.
    var showsModeratorItems: Bool { session.state.me?.isModerator == true }

    var enabledKebabItems: [MeKebabItem] {
        MeKebabItem.items(isModerator: showsModeratorItems).filter(MeKebabItem.landed.contains)
    }

    /// Resolved through `selection(_:in:)` on every read rather than stored raw: a chip
    /// unsubscribed elsewhere (the channel screen's Subscribe toggle) must not leave this tab
    /// filtering by a row that no longer exists.
    var selectedChipId: String? { Self.selection(rawSelection, in: chips) }

    func setFilter(_ chipId: String?) { rawSelection = chipId }

    /// The kebab's ONE live destination in this task. `AccountSession` re-scopes every per-user
    /// store to the anon sentinel, which is what puts `MeTabRoot` back on the guest screen.
    func signOut() { session.signOut() }

    /// Pure, over the merged list.
    nonisolated static func selection(_ chipId: String?, in chips: [MeChipItem]) -> String? {
        chips.contains { $0.id == chipId } ? chipId : nil
    }

    /// Fix round 1 / I4 RULING: only CHANNEL chips filter the FEED. The rail merges channels and
    /// saved playlists, but the Atom feed is per channel — routing a playlist chip into
    /// `MeFeedRepository.setFilter` emptied the whole section with nothing to explain it. The chip
    /// still selects and highlights; the feed simply stays unfiltered until a playlist has items
    /// of its own to show (Task 30).
    nonisolated static func feedFilter(for chipId: String?, in chips: [MeChipItem]) -> String? {
        chips.first { $0.id == chipId }?.kind == .channel ? chipId : nil
    }
}
