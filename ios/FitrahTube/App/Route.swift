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

/// One destination pushed onto a tab's `NavigationStack` (spec §6). Phase 1 only implements the
/// tab roots and this routing layer -- every destination renders `PhaseTwoPlaceholderView` until
/// its real screen lands in a later task.
nonisolated enum Route: Hashable, Sendable {
    case player(PlayerArgs)
    case shorts(id: String)
    case channel(id: String, name: String?, avatarURL: URL?)
    case playlist(id: String, title: String?, category: String?, count: Int?)
    case search
    case categories
    case subcategories(parentId: String, parentName: String)
    case featured(categoryId: String?, categoryName: String?)
    case favorites
    case settings
    case about
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
