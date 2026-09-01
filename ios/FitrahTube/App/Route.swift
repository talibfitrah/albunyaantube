import Foundation

/// Shell tabs, in menu order (Android `bottom_nav_menu.xml:3-22`, `shell-home.md:A2`):
/// Home → Channels → Me → Playlists → Videos.
nonisolated enum Tab: Int, CaseIterable, Sendable {
    case home, channels, me, playlists, videos
}

extension Tab {
    /// Shared by `MainShellView`'s `TabView` items and `NavigationRailView`'s rail items so the
    /// bottom bar and the leading rail (task-7b) can never disagree on a tab's title/icon.
    var title: String {
        switch self {
        case .home: String(localized: "nav_home")
        case .channels: String(localized: "nav_channels")
        case .me: String(localized: "nav_me")
        case .playlists: String(localized: "nav_playlists")
        case .videos: String(localized: "nav_videos")
        }
    }

    /// SF Symbols per `strings-assets.md:732-770`.
    var symbolName: String {
        switch self {
        case .home: "house.fill"
        case .channels: "tv"
        case .me: "person.crop.circle"
        case .playlists: "list.bullet.rectangle"
        case .videos: "film.stack"
        }
    }
}

/// One destination pushed onto a tab's `NavigationStack` (spec §6); `MainShellView.destination(for:)`
/// maps each case to its screen exhaustively (the Phase 1 placeholder is gone since Plan C Task 5).
nonisolated enum Route: Hashable, Sendable {
    case player(PlayerArgs)
    case shorts(PlayerArgs)
    case channel(id: String, name: String?, avatarURL: URL?)
    case playlist(id: String, title: String?, category: String?, count: Int?)
    case search
    case categories
    case subcategories(parentId: String, parentName: String)
    case featured(categoryId: String?, categoryName: String?)
    case favorites
    case settings
    case about
    /// Phase 3 Task 6: the Saved (offline library) screen — the ONE Route case Phase 3 adds.
    case offline
}

extension Route {
    /// The catalog-item → destination mapping. One copy (gate wave-2 W9): the same three-case
    /// switch was pasted into `HomeView`, `FeaturedView` (twice), `ContentListView` and
    /// `SearchView`, and RULINGS #17 records a bug born of exactly that duplication -- the
    /// `PlayerArgs(item:)` extraction below fixed only the player leg of it.
    init(item: ContentItem) {
        switch item.type {
        case .video:
            self = .player(PlayerArgs(item: item))
        case .channel:
            self = .channel(id: item.id, name: item.title, avatarURL: item.thumbnailURL)
        case .playlist:
            self = .playlist(id: item.id, title: item.title, category: item.category, count: item.itemCount)
        }
    }
}

/// Android's `PlayerFragment` arguments (`PlayerFragment.kt:400-438`) -- the metadata fast path
/// means playback can start from what the caller already has on hand, no backend fetch first.
nonisolated struct PlayerArgs: Hashable, Sendable {
    let videoId: String
    var playlistId: String? = nil
    var title: String? = nil
    var channelName: String? = nil
    var thumbnailURL: URL? = nil
    var description: String? = nil
    var durationSeconds: Int? = nil
    var viewCount: Int64? = nil
    var channelId: String? = nil
    /// Shorts only (B4): the channel avatar the Shorts bottom overlay shows next to the @handle
    /// (`ShortsPageViewHolder.kt:44-60`). The main player has no avatar affordance, so this is nil on
    /// every `.player` route -- and nil is also the honest value for a Short opened from a deep link,
    /// where the sender supplies nothing but an id.
    var channelAvatarURL: URL? = nil
    /// Index hint from the caller's list (`PlayerFragment.kt:402-417`). NOT authoritative --
    /// `targetVideoId` wins; this is the fallback when the deep scan is exhausted.
    var startIndex: Int = 0
    /// Randomize the queue, pinning the launched video first; paging is disabled while shuffled
    /// ("can't prefetch shuffle since we don't know the order", `PlaylistDetailFragment.kt:300-303`).
    var shuffled: Bool = false
    /// The authoritative start video when the caller knows it (`PlaylistDetailFragment.kt:747`).
    var targetVideoId: String? = nil
    /// Opened from a channel's Live tab (`ChannelLiveTabFragment.kt:62-69`): the report carries
    /// `contentSubType = LIVESTREAM`. Nothing else reads it.
    var isLive: Bool = false
}

extension PlayerArgs {
    /// The catalog-item mapping every screen that can open the player needs. One copy (gate s1-2):
    /// it was pasted identically into `HomeViewModel`, `ContentListView`, `FeaturedView` and
    /// `SearchView`, which made RULINGS #17 -- `channelName` prefers the video's real
    /// `channelTitle`, falling back to `category` only when nil (Android always used `category`, a
    /// mapping bug) -- something that had to stay independently correct in four places.
    init(item: ContentItem) {
        self.init(videoId: item.id, title: item.title, channelName: item.channelTitle ?? item.category,
                  thumbnailURL: item.thumbnailURL, description: item.description,
                  durationSeconds: item.durationSeconds, viewCount: item.viewCount)
    }
}
