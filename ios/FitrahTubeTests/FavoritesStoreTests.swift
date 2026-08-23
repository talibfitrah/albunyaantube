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

    /// Gate wave-4 V9: `toggle`/`clearAll` mutate model objects before saving, so a failed save
    /// used to leave those mutations pending in the context -- and the next successful save of any
    /// unrelated operation committed the toggle the user was told had failed. A read-only store
    /// (`allowsSave: false`) is the reachable way to make `save()` throw; `fetch` includes pending
    /// changes, so a favorite that reads back as absent is the proof the rollback happened.
    @Test func failedSaveRollsBackInsteadOfLeavingTheMutationPending() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("\(UUID().uuidString).store")
        let schema = Schema([FavoriteVideo.self])
        let container = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url))
        // Same file, reopened read-only -- creating it writable first keeps the failure in `save()`
        // rather than in the container build.
        let readOnly = try ModelContainer(for: schema, configurations: ModelConfiguration(schema: schema, url: url, allowsSave: false))
        _ = container
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }
        let store = SwiftDataFavoritesStore(modelContainer: readOnly)

        #expect(throws: (any Error).self) { try store.toggle(makeItem()) }

        #expect(store.isFavorite("v1") == false)
        #expect(store.items.isEmpty)
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

    /// Gate wave-2 W12: `updatedAt` is the server timestamp and phase 4's monotonicity guard --
    /// local mutations set `dirty` and leave it at the epoch-0 sentinel, exactly as Android's DAO
    /// does. A locally stamped (possibly future) timestamp would make the server's own updates to
    /// that row fail the `updated_at < :ts` guard forever.
    @Test func localMutationsNeverAdvanceUpdatedAt() throws {
        let container = makeContainer()
        let store = makeStore(container: container)
        let epoch = Date(timeIntervalSince1970: 0)

        try store.toggle(makeItem())
        #expect(store.items.first?.updatedAt == epoch)

        try store.toggle(makeItem())   // soft-delete
        try store.toggle(makeItem())   // resurrect
        #expect(store.items.first?.updatedAt == epoch)

        try store.clearAll()
        #expect(store.items.isEmpty)

        // `items` is filtered on `isRemoved == false`, so an empty list is equally consistent with
        // a hard delete -- which would lose the tombstone phase 4 needs to push (gate wave-3 D6).
        // Read the row back through a deleted-agnostic fetch instead.
        let rows = try ModelContext(container).fetch(FetchDescriptor<FavoriteVideo>())
        #expect(rows.count == 1)
        #expect(rows.first?.isRemoved == true)
        #expect(rows.first?.dirty == true)
        #expect(rows.first?.updatedAt == epoch) // still the server sentinel, even on clearAll
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
