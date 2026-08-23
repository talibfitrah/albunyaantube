import Foundation
import SwiftData
import Testing
@testable import FitrahTube

/// Android's `FavoritesViewModel` (`favorites-settings-about.md:1.2,1.4`). `FavoritesViewModel`
/// is a thin wrapper over `FavoritesStore`: `items`/`recentFavorites` are straight passthroughs,
/// `remove`/`clearAll` call the store's `toggle`/`clearAll`, and `playerArgs(for:)` builds exactly
/// the 5-key bundle Android's `FavoritesFragment` passes to the player (no backend fetch fast path).
@Suite(.perTest)
struct FavoritesViewModelTests {
    private func makeStore() -> SwiftDataFavoritesStore {
        let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        let container = try! ModelContainer(for: FavoriteVideo.self, configurations: configuration)
        return SwiftDataFavoritesStore(modelContainer: container)
    }

    private func makeItem(id: String, title: String = "Video", channel: String = "Channel",
                           thumbnail: String? = "https://example.com/thumb.jpg", duration: Int = 125) -> ContentItem {
        ContentItem(id: id, type: .video, title: title, category: nil, description: nil,
                    thumbnailURL: thumbnail.flatMap(URL.init(string:)), durationSeconds: duration,
                    uploadedDaysAgo: nil, viewCount: nil, channelTitle: channel,
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }

    // MARK: - remove (soft delete via toggle)

    @Test func removeSoftDeletesAFavorite() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1"))
        let viewModel = FavoritesViewModel(store: store)
        #expect(viewModel.items.map(\.videoId) == ["v1"])

        try viewModel.remove(viewModel.items[0])

        #expect(viewModel.items.isEmpty)
        #expect(store.isFavorite("v1") == false)
    }

    // MARK: - clearAll

    @Test func clearAllRemovesEveryFavorite() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1"))
        try store.toggle(makeItem(id: "v2"))
        let viewModel = FavoritesViewModel(store: store)
        #expect(viewModel.items.count == 2)

        try viewModel.clearAll()

        #expect(viewModel.items.isEmpty)
    }

    // MARK: - playerArgs mapping (exactly the 5 Android args, no more)

    @Test func playerArgsMapsExactlyTheFiveAndroidKeys() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1", title: "Title", channel: "Channel",
                                   thumbnail: "https://example.com/thumb.jpg", duration: 125))
        let viewModel = FavoritesViewModel(store: store)
        let favorite = try #require(viewModel.items.first)

        let args = viewModel.playerArgs(for: favorite)

        #expect(args.videoId == "v1")
        #expect(args.title == "Title")
        #expect(args.channelName == "Channel")
        #expect(args.thumbnailURL == URL(string: "https://example.com/thumb.jpg"))
        #expect(args.durationSeconds == 125)
        // The other 7 PlayerArgs fields must stay nil/default -- favorites-settings-about.md:1.4
        // ("metadata fast path, no backend fetch, no channel link in the player header").
        #expect(args.playlistId == nil)
        #expect(args.description == nil)
        #expect(args.viewCount == nil)
        #expect(args.channelId == nil)
    }

    @Test func playerArgsHandlesNilThumbnail() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1", thumbnail: nil))
        let viewModel = FavoritesViewModel(store: store)
        let favorite = try #require(viewModel.items.first)

        #expect(viewModel.playerArgs(for: favorite).thumbnailURL == nil)
    }

    // MARK: - recentFavorites (MeGuestView data source: first 5, most-recently-added first)

    @Test func recentFavoritesIsCappedAtFiveMostRecent() throws {
        let store = makeStore()
        for id in ["v1", "v2", "v3", "v4", "v5", "v6"] {
            try store.toggle(makeItem(id: id))
        }
        let viewModel = FavoritesViewModel(store: store)

        #expect(viewModel.recentFavorites.count == 5)
        // Store sorts by addedAt descending -- the most recently toggled ("v6") is newest.
        #expect(viewModel.recentFavorites.map(\.videoId) == ["v6", "v5", "v4", "v3", "v2"])
    }

    @Test func recentFavoritesReturnsAllWhenFewerThanFive() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1"))
        let viewModel = FavoritesViewModel(store: store)

        #expect(viewModel.recentFavorites.map(\.videoId) == ["v1"])
    }
}
