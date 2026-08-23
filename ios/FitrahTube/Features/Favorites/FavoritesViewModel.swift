import Foundation

/// Android's `FavoritesViewModel` (`favorites-settings-about.md:1.2`). A thin wrapper over
/// `FavoritesStore`: `items` is a direct passthrough (the store is already `@Observable`,
/// per-user-scoped, and sorted by `addedAt` descending). `remove`/`clearAll` call straight through
/// to the store; RULINGS #31 has both surface a thrown failure via the caller's transient banner
/// rather than fail silently the way Android's uncollected `uiEvents` channel does.
@MainActor @Observable final class FavoritesViewModel {
    private let store: any FavoritesStore

    var items: [FavoriteVideo] { store.items }

    /// MeGuestView's favorites section: "up to 5 rows" (task-12 brief). Store order is already
    /// most-recently-added first, so this is just the first 5.
    var recentFavorites: [FavoriteVideo] { Array(items.prefix(5)) }

    init(store: any FavoritesStore) {
        self.store = store
    }

    /// `toggle` on a still-live favorite always soft-deletes it (never resurrects) --
    /// `SwiftDataFavoritesStore.toggle`'s live-row branch.
    func remove(_ item: FavoriteVideo) throws {
        try store.toggle(contentItem(for: item))
    }

    /// RULINGS #30: soft-deletes every row (tombstones for the phase-4 sync push), unlike
    /// Android's hard `DELETE` -- already implemented that way in `SwiftDataFavoritesStore.clearAll`.
    func clearAll() throws {
        try store.clearAll()
    }

    /// Android's `FavoritesFragment` bundle (`favorites-settings-about.md:1.4`): exactly these 5
    /// keys, everything else nil -- the metadata fast path, no backend fetch, no channel link in
    /// the player header (`channelId` stays nil).
    func playerArgs(for item: FavoriteVideo) -> PlayerArgs {
        PlayerArgs(videoId: item.videoId, title: item.title, channelName: item.channelName,
                   thumbnailURL: item.thumbnailUrl.flatMap(URL.init(string:)), durationSeconds: item.durationSeconds)
    }

    /// Shared by `remove(_:)` (the store's only mutation entry point takes a `ContentItem`) and by
    /// `FavoritesView`/`MeGuestView` to build the `VideoRow` each row renders -- `type`/`category`/
    /// stats are irrelevant to a favorite and left nil.
    func contentItem(for item: FavoriteVideo) -> ContentItem {
        ContentItem(id: item.videoId, type: .video, title: item.title, category: nil, description: nil,
                    thumbnailURL: item.thumbnailUrl.flatMap(URL.init(string:)), durationSeconds: item.durationSeconds,
                    uploadedDaysAgo: nil, viewCount: nil, channelTitle: item.channelName,
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }
}
