import Foundation
import SwiftData
import Testing
@testable import FitrahTube

/// Plan C Task 5: the guest-local subscriptions store (RULING 27, `SubscriptionLimitGuard.kt:73`).
@Suite(.perTest)
struct SubscriptionsStoreTests {
    private func makeStore() throws -> SwiftDataSubscriptionsStore {
        let container = try ModelContainer(for: SubscribedChannel.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        return SwiftDataSubscriptionsStore(modelContainer: container)
    }

    @Test func theThirtyFirstSubscriptionIsRefusedWithTheCapMessage() throws {
        // RULING 27 + SubscriptionLimitGuard.kt:26,73 + strings.xml:183. Guest-local; no account needed.
        let store = try makeStore()
        for i in 0..<30 { try store.toggle(id: "UCchannel\(i)", name: "C\(i)", avatarURL: nil) }
        #expect(store.items.count == 30)
        #expect(throws: SubscriptionsError.capReached) { try store.toggle(id: "UCchannel30", name: nil, avatarURL: nil) }
        #expect(store.isSubscribed("UCchannel30") == false)
    }

    @Test func unsubscribingIsNeverCapped() throws {
        let store = try makeStore()
        for i in 0..<30 { try store.toggle(id: "UCchannel\(i)", name: nil, avatarURL: nil) }
        try store.toggle(id: "UCchannel0", name: nil, avatarURL: nil)   // unsubscribe at the cap
        #expect(store.isSubscribed("UCchannel0") == false)
        #expect(store.items.count == 29)
        try store.toggle(id: "UCchannel30", name: nil, avatarURL: nil)  // room again
        #expect(store.isSubscribed("UCchannel30"))
        // Tombstoned rows do not count against the cap and resurrect with fresh metadata.
        try store.toggle(id: "UCchannel30", name: nil, avatarURL: nil)
        try store.toggle(id: "UCchannel0", name: "Back", avatarURL: URL(string: "https://x/a.jpg"))
        #expect(store.items.first { $0.channelId == "UCchannel0" }?.title == "Back")
        #expect(store.items.first { $0.channelId == "UCchannel0" }?.dirty == true)
    }

    @Test func aMalformedChannelIdIsRefused() throws {
        // ChannelDetailFragment.kt:516 ^[A-Za-z0-9_-]{3,64}$
        let store = try makeStore()
        #expect(throws: SubscriptionsError.invalidChannelId) { try store.toggle(id: "UC bad", name: nil, avatarURL: nil) }
        #expect(throws: SubscriptionsError.invalidChannelId) { try store.toggle(id: "ab", name: nil, avatarURL: nil) }
        #expect(SwiftDataSubscriptionsStore.isValid("UCmMcOjsVehVlEOteyrhjI2Q"))
        #expect(SwiftDataSubscriptionsStore.isValid(String(repeating: "a", count: 65)) == false)
        #expect(store.items.isEmpty)
    }

    @Test func aV2StoreOnDiskMigratesToV3KeepingSavedPlaylists() throws {
        let url = URL(fileURLWithPath: NSTemporaryDirectory()).appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
        defer { for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) } }
        do {
            let v2 = Schema(versionedSchema: FavoritesSchemaV2.self)
            let container = try ModelContainer(for: v2, configurations: ModelConfiguration(schema: v2, url: url))
            let context = ModelContext(container)
            // The FROZEN V2 shape (fix round 1 / C1) -- the live type carries V5's five extra
            // columns, so seeding with it writes a file no V2 build could have written.
            context.insert(FavoritesSchemaV2.SavedPlaylist(playlistId: "PL1", title: "T", thumbnailUrl: nil, itemCount: 1))
            try context.save()
        }
        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        #expect(try ModelContext(container).fetchCount(FetchDescriptor<SavedPlaylist>()) == 1)
        let store = SwiftDataSubscriptionsStore(modelContainer: container)
        try store.toggle(id: "UCchannel1", name: "A", avatarURL: nil)
        #expect(store.isSubscribed("UCchannel1"))
    }
}
