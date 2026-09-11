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

/// `MeFragment.kt:47-48` — the two tab positions, in the order `renderTabs` adds them.
nonisolated enum MeTab: Sendable { case content, pending }

/// One row of the Pending tab (`AwaitingImportsAdapter.DisplayRow`): an imported id an admin has
/// not reviewed yet. **Not tappable** — it is not in the registry, so there is nothing to open.
nonisolated struct MeAwaitingItem: Sendable, Equatable, Identifiable {
    var id: String
    var title: String
    var thumbnailURL: URL?
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

    // RULING 28 is enforced by the compiler: a kebab case with no `Route` is a compile error in
    // `MeSignedInView.kebab`'s exhaustive switch, and a `Route` with no screen is one in
    // `MainShellView.destination(for:)`.
}

/// Ruling C5's signed-in Me screen, over LOCAL stores only. No feed (Tasks 14-16), no History
/// rows (F10), no Content/Pending tabs (F14).
@MainActor @Observable final class MeViewModel {
    private let session: AccountSession
    private let favorites: any FavoritesStore
    private let subscriptions: any SubscriptionsStore
    private let savedPlaylists: any SavedPlaylistsStore
    /// Task 29: whether this account has a Google grant the import flow can extend, read at RENDER
    /// time rather than captured. A closure, not `any YouTubeAuthorizer`, because that is the whole
    /// of what this screen needs to know — the Me tab has no other business with the import feature.
    private let canImportFromYouTube: () -> Bool
    /// Task 30: `importOfferShown` only. Phase 1 already persists it (`SettingsStore.swift:24`);
    /// nothing else on this screen reads settings.
    private let settings: any SettingsStore

    /// `MeFavoritesAdapter:19-20,45` — 20 tiles, plus a trailing "See all".
    static let maxFavoriteTiles = 20

    private var rawSelection: String?

    /// `MeFragment.kt:387-406`. Read through `selectedTab`, never raw: when the queue empties the
    /// tab bar goes away (`showsTabs`) and a stored `.pending` would leave the screen rendering a
    /// list that is no longer reachable (`:355-358` falls back to Content for exactly that).
    private var rawTab: MeTab = .content

    init(session: AccountSession, favorites: any FavoritesStore,
         subscriptions: any SubscriptionsStore, savedPlaylists: any SavedPlaylistsStore,
         settings: any SettingsStore, canImportFromYouTube: @escaping () -> Bool) {
        self.session = session
        self.favorites = favorites
        self.subscriptions = subscriptions
        self.savedPlaylists = savedPlaylists
        self.settings = settings
        self.canImportFromYouTube = canImportFromYouTube
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

    // MARK: - Fork F14: awaiting imports

    /// Channels, then playlists, then videos — `AwaitingImportsAdapter.buildRows`' order, which is
    /// the import review screen's grouping (`ImportFromYouTubeScreen`). Read off the three stores'
    /// `awaitingItems`, so it is exactly as live as the chip rail beside it.
    var awaitingItems: [MeAwaitingItem] {
        subscriptions.awaitingItems.map {
            MeAwaitingItem(id: $0.channelId, title: $0.title,
                           thumbnailURL: $0.avatarUrl.flatMap(URL.init(string:)))
        }
        + savedPlaylists.awaitingItems.map {
            MeAwaitingItem(id: $0.playlistId, title: $0.title,
                           thumbnailURL: $0.thumbnailUrl.flatMap(URL.init(string:)))
        }
        + favorites.awaitingItems.map {
            MeAwaitingItem(id: $0.videoId, title: $0.title,
                           thumbnailURL: $0.thumbnailUrl.flatMap(URL.init(string:)))
        }
    }

    /// The sum of the three stores. A LIVE count, not a snapshot: `ImportGraduationService` flips
    /// these server-side when an admin reviews an id, and the next sync brings the change down.
    var awaitingCount: Int { awaitingItems.count }

    /// `MeFragment.kt:352-368`: hidden ENTIRELY at zero. With an empty queue a two-tab bar is
    /// permanent chrome over a screen with nothing to switch to.
    var showsTabs: Bool { awaitingCount > 0 }

    /// `:355-358`. When the last pending item clears while the user is on that tab, they fall back
    /// to Content rather than sitting on a list that no longer exists.
    var selectedTab: MeTab { showsTabs ? rawTab : .content }

    /// The same-value guard is one line that saves an invalidation and nothing more — measured
    /// (Task 30): SwiftUI has no adapter swap, so Android's scroll-reset rationale does not apply.
    func select(tab: MeTab) {
        guard tab != rawTab else { return }
        rawTab = tab
    }

    /// Part B gate (stage 3 M-3): the fallback in `selectedTab` is a READ; the stored value has to
    /// be reset too, or the NEXT awaiting row — a later import, a background pull landing one —
    /// would switch the user onto Pending with no tap. Called by the screen when the tab bar goes
    /// away.
    func queueEmptied() { rawTab = .content }

    // MARK: - Fork F14: the one-time import offer

    /// `MeFragment.kt:428-445`. Signed in, not yet shown, and — the load-bearing half — the flag is
    /// written BEFORE the dialog is presented (`:433`), so a second launch that reaches this line
    /// while the first dialog is still up cannot fire a second one. Returns whether to present.
    ///
    /// It also requires the affordance the offer leads to: the positive button pushes
    /// `Route.importFromYouTube`, which an account with no Google grant cannot use (RULING 28 keeps
    /// that row off the kebab for the same reason) — offering it there would be a dialog whose one
    /// action fails.
    func consumeImportOffer() -> Bool {
        guard session.user != nil, canImportFromYouTube(), !settings.importOfferShown else { return false }
        settings.importOfferShown = true
        return true
    }

    /// `MeFragment.kt:270-271` compares `ignoreCase = true`; `AccountMe.isModerator` carries that.
    var showsModeratorItems: Bool { session.state.me?.isModerator == true }

    /// Task 29's capability gate: Import
    /// needs a signed-in GOOGLE user to extend a scope onto (`GIDGoogleUser.addScopes` lives on the
    /// user, not on `GIDSignIn`), so an Apple or email/password account has nothing to authorize.
    /// RULING 28 again: the row is ABSENT for those accounts, never rendered greyed — a greyed row
    /// is still a visible promise, and this one could never be kept.
    var enabledKebabItems: [MeKebabItem] {
        MeKebabItem.items(isModerator: showsModeratorItems)
            .filter { $0 != .importYouTube || canImportFromYouTube() }
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
