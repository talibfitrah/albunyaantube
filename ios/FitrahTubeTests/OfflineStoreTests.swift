import Foundation
import SwiftData
import Testing
@testable import FitrahTube

/// Phase 3 Task 3: `OfflineItem` persistence — the V3→V4 on-disk migration pin (the
/// `aV2StoreOnDiskMigratesToV3KeepingSavedPlaylists` pattern, extended) and the
/// `OfflineStore` behaviours Task 4's manager and Task 6's Saved screen build on.
@Suite(.perTest)
struct OfflineStoreTests {
    private static func makeItem(videoId: String = "xc7keR2piUM", title: String = "Lecture",
                                 quality: String = "360p") -> OfflineItem {
        OfflineItem(videoId: videoId, title: title, channelName: "Channel", thumbnailUrl: nil,
                    qualityLabel: quality, audioOnly: false)
    }

    private func makeStore() -> (OfflineStore, ModelContainer) {
        let container = AppContainer.makeModelContainer(inMemory: true)
        return (OfflineStore(modelContainer: container), container)
    }

    /// The migration pin: a V3 store on disk (a favorite + a saved playlist + a subscription)
    /// opens under the V4 plan with every row intact, and the new `OfflineItem` entity is live —
    /// reads AND writes. Fails against a `makeModelContainer` still on `FavoritesSchemaV3`.
    @Test func aV3StoreOnDiskMigratesToV4KeepingEveryRow() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }
        do {
            let v3 = Schema(versionedSchema: FavoritesSchemaV3.self)
            let container = try ModelContainer(for: v3, configurations: ModelConfiguration(schema: v3, url: url))
            let context = ModelContext(container)
            context.insert(FavoriteVideo(videoId: "v1", title: "F", channelName: "C", thumbnailUrl: nil, durationSeconds: 1))
            context.insert(SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "P", thumbnailUrl: nil, itemCount: 1))
            context.insert(SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q", title: "S", avatarUrl: nil))
            try context.save()
        }

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<FavoriteVideo>()) == 1)
        #expect(try context.fetchCount(FetchDescriptor<SavedPlaylist>()) == 1)
        #expect(try context.fetchCount(FetchDescriptor<SubscribedChannel>()) == 1)
        // The new entity exists in the migrated schema…
        #expect(try context.fetchCount(FetchDescriptor<OfflineItem>()) == 0)
        // …and accepts an insert whose fields round-trip through a save (the `deleted`-property
        // lesson in FavoriteVideo.swift: assert on a re-read, never on in-memory state — this
        // also guards `id`, a name SwiftData half-reserves like `deleted`).
        context.insert(Self.makeItem())
        try context.save()
        let fetched = try #require(try context.fetch(FetchDescriptor<OfflineItem>()).first)
        #expect(fetched.videoId == "xc7keR2piUM")
        #expect(fetched.status == OfflineStatus.queued.rawValue)
        #expect(UUID(uuidString: fetched.id) != nil)
    }

    @Test func aFreshItemDefaultsToQueuedWithNothingWritten() {
        let item = Self.makeItem()
        #expect(item.status == OfflineStatus.queued.rawValue)
        #expect(item.bytesWritten == 0)
        #expect(item.totalBytes == nil)
        #expect(item.errorCode == nil)
        #expect(item.localPath == nil)
        #expect(item.resumeData == nil)
        #expect(item.completedAt == nil)
        #expect(UUID(uuidString: item.id) != nil)
    }

    /// `#Unique` on `videoId` alone: ONE saved copy per video — a re-save at a different
    /// quality replaces the row (fork F: no `playlistId|quality` dedupe, bulk saves deferred).
    @Test func aReSaveOfTheSameVideoReplacesTheRow() throws {
        let (store, _) = makeStore()
        try store.insert(Self.makeItem(quality: "360p"))
        try store.insert(Self.makeItem(quality: "720p"))
        #expect(store.items.count == 1)
        #expect(store.items.first?.qualityLabel == "720p")
    }

    /// `DownloadsFragment` sort parity: alphabetical by title — Task 6's screen renders
    /// `items` verbatim.
    @Test func itemsAreSortedAlphabeticallyByTitle() throws {
        let (store, _) = makeStore()
        try store.insert(Self.makeItem(videoId: "vidB", title: "Bee"))
        try store.insert(Self.makeItem(videoId: "vidA", title: "Ant"))
        try store.insert(Self.makeItem(videoId: "vidC", title: "Cat"))
        #expect(store.items.map(\.title) == ["Ant", "Bee", "Cat"])
    }

    @Test func rowsAreFoundByVideoIdAndById() throws {
        let (store, _) = makeStore()
        let item = Self.makeItem()
        try store.insert(item)
        #expect(store.item(videoId: "xc7keR2piUM")?.id == item.id)
        #expect(store.item(id: item.id)?.videoId == "xc7keR2piUM")
        #expect(store.item(videoId: "missing") == nil)
        #expect(store.item(id: "missing") == nil)
    }

    @Test func aStatusUpdatePersistsThroughSave() throws {
        let (store, container) = makeStore()
        try store.insert(Self.makeItem())
        let row = try #require(store.item(videoId: "xc7keR2piUM"))
        row.status = OfflineStatus.running.rawValue
        row.bytesWritten = 1_234
        try store.save()
        // Re-read through a fresh context: the mutation really persisted.
        let reread = try #require(try ModelContext(container).fetch(FetchDescriptor<OfflineItem>()).first)
        #expect(reread.status == OfflineStatus.running.rawValue)
        #expect(reread.bytesWritten == 1_234)
    }

    @Test func deleteRemovesTheRow() throws {
        let (store, container) = makeStore()
        try store.insert(Self.makeItem())
        let row = try #require(store.item(videoId: "xc7keR2piUM"))
        try store.delete(row)
        #expect(store.items.isEmpty)
        #expect(store.item(videoId: "xc7keR2piUM") == nil)
        #expect(try ModelContext(container).fetchCount(FetchDescriptor<OfflineItem>()) == 0)
    }
}
