import Foundation
import SwiftData
import Testing
@testable import FitrahTube

@Suite(.perTest)
struct FavoritesStoreTests {
    private func makeContainer() -> ModelContainer {
        let configuration = ModelConfiguration(isStoredInMemoryOnly: true)
        return try! ModelContainer(for: FavoriteVideo.self, configurations: configuration)
    }

    private func makeStore(container: ModelContainer? = nil) -> SwiftDataFavoritesStore {
        SwiftDataFavoritesStore(modelContainer: container ?? makeContainer())
    }

    private func makeItem(id: String = "v1", title: String = "Video", channel: String = "Channel",
                           duration: Int = 120) -> ContentItem {
        ContentItem(id: id, type: .video, title: title, category: nil, description: nil, thumbnailURL: nil,
                    durationSeconds: duration, uploadedDaysAgo: nil, viewCount: nil, channelTitle: channel,
                    subscribers: nil, videoCount: nil, itemCount: nil)
    }

    @Test func startsEmpty() {
        let store = makeStore()
        #expect(store.items.isEmpty)
        #expect(store.isFavorite("v1") == false)
    }

    @Test func toggleInsertsThenSoftDeletes() throws {
        let store = makeStore()

        try store.toggle(makeItem())

        #expect(store.items.map(\.videoId) == ["v1"])
        #expect(store.isFavorite("v1") == true)
        let inserted = try #require(store.items.first)
        #expect(inserted.title == "Video")
        #expect(inserted.channelName == "Channel")
        #expect(inserted.durationSeconds == 120)
        #expect(inserted.userId == "")
        #expect(inserted.approvalStatus == "APPROVED")
        #expect(inserted.dirty == true)

        try store.toggle(makeItem())

        #expect(store.items.isEmpty)
        #expect(store.isFavorite("v1") == false)
    }

    @Test func retogglingSoftDeletedRowRevivesItWithFreshSnapshot() throws {
        let store = makeStore()
        try store.toggle(makeItem(title: "Original"))
        try store.toggle(makeItem(title: "Original")) // soft-delete

        try store.toggle(makeItem(title: "Updated title"))

        #expect(store.items.count == 1)
        #expect(store.items.first?.title == "Updated title")
        #expect(store.items.first?.isRemoved == false)
        #expect(store.isFavorite("v1") == true)
    }

    @Test func itemsExcludeAwaitingApprovalRowsButIsFavoriteDoesNot() throws {
        let container = makeContainer()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "awaiting", title: "T", channelName: "C", thumbnailUrl: nil,
                                      durationSeconds: 60, userId: "", approvalStatus: "AWAITING"))
        try context.save()

        let store = makeStore(container: container)

        #expect(store.items.isEmpty)
        #expect(store.isFavorite("awaiting") == true)
    }

    @Test func itemsAreScopedToCurrentUserIdAndReactToChanges() throws {
        let container = makeContainer()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "other", title: "T", channelName: "C", thumbnailUrl: nil,
                                      durationSeconds: 60, userId: "other-uid"))
        try context.save()

        let store = makeStore(container: container)
        #expect(store.items.isEmpty) // anon "" sentinel sees nothing

        store.currentUserId = "other-uid"
        #expect(store.items.map(\.videoId) == ["other"])
    }

    @Test func itemsSortedByAddedAtDescending() throws {
        let container = makeContainer()
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "older", title: "T", channelName: "C", thumbnailUrl: nil,
                                      durationSeconds: 60, addedAt: Date(timeIntervalSince1970: 100), userId: ""))
        context.insert(FavoriteVideo(videoId: "newer", title: "T", channelName: "C", thumbnailUrl: nil,
                                      durationSeconds: 60, addedAt: Date(timeIntervalSince1970: 200), userId: ""))
        try context.save()

        let store = makeStore(container: container)
        #expect(store.items.map(\.videoId) == ["newer", "older"])
    }

    @Test func clearAllSoftDeletesEveryLiveRow() throws {
        let store = makeStore()
        try store.toggle(makeItem(id: "v1"))
        try store.toggle(makeItem(id: "v2"))
        #expect(store.items.count == 2)

        try store.clearAll()

        #expect(store.items.isEmpty)
        #expect(store.isFavorite("v1") == false)
        #expect(store.isFavorite("v2") == false)
    }
}
