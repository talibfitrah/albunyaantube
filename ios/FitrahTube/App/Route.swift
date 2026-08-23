import Foundation

/// Shell tabs, in menu order (Android `bottom_nav_menu.xml:3-22`, `shell-home.md:A2`):
/// Home → Channels → Me → Playlists → Videos.
nonisolated enum Tab: Int, CaseIterable, Sendable {
    case home, channels, me, playlists, videos
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
