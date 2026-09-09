import Foundation
import SwiftData
import Testing
@testable import FitrahTube

/// Phase 4 Task 20: schema V5 -- the URL/import columns the sync wire needs on the two synced
/// models, plus the two sync bookkeeping models (`SyncState`, `AccountBinding`, ruling F3).
///
/// Every assertion about a new column is made on a **re-read after a save**, never on in-memory
/// state: `FavoriteVideo.swift` records that a `@Model` property named `deleted` mutates in memory
/// and is silently reverted by the next save, so a column is only proven by a fresh fetch.
@Suite(.perTest)
struct SchemaV5MigrationTests {

    private static func temporaryStoreURL() -> URL {
        URL(fileURLWithPath: NSTemporaryDirectory())
            .appendingPathComponent("FitrahTubeTests-\(UUID().uuidString).store")
    }

    private static func remove(_ url: URL) {
        for suffix in ["", "-shm", "-wal"] { try? FileManager.default.removeItem(at: URL(fileURLWithPath: url.path + suffix)) }
    }

    /// A V4 store on disk with one row of each synced type. Scoped in its own `do` block at the
    /// call site so the V4 container is gone before the V5 plan opens the same file.
    private static func makeV4Store(at url: URL) throws {
        let v4 = Schema(versionedSchema: FavoritesSchemaV4.self)
        let container = try ModelContainer(for: v4, configurations: ModelConfiguration(schema: v4, url: url))
        let context = ModelContext(container)
        context.insert(FavoriteVideo(videoId: "xc7keR2piUM", title: "F", channelName: "C", thumbnailUrl: nil, durationSeconds: 12))
        context.insert(SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "P", thumbnailUrl: nil, itemCount: 7))
        context.insert(SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q", title: "S", avatarUrl: nil))
        try context.save()
    }

    /// The migration pin: every V4 row survives the V5 stage with its values intact. Fails against
    /// a `makeModelContainer` still on `FavoritesSchemaV4`.
    @Test func aV4StoreOnDiskMigratesToV5KeepingEveryRow() throws {
        let url = Self.temporaryStoreURL()
        defer { Self.remove(url) }
        try Self.makeV4Store(at: url)

        let context = ModelContext(AppContainer.makeModelContainer(inMemory: false, storeURL: url))
        let favorite = try #require(try context.fetch(FetchDescriptor<FavoriteVideo>()).first)
        #expect(favorite.videoId == "xc7keR2piUM")
        #expect(favorite.durationSeconds == 12)
        let playlist = try #require(try context.fetch(FetchDescriptor<SavedPlaylist>()).first)
        #expect(playlist.playlistId == "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc")
        #expect(playlist.itemCount == 7)   // no sync DTO carries it; PlaylistRow's chip renders it
        let channel = try #require(try context.fetch(FetchDescriptor<SubscribedChannel>()).first)
        #expect(channel.channelId == "UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(channel.title == "S")
        #expect(try context.fetchCount(FetchDescriptor<OfflineItem>()) == 0)
    }

    /// V5's channel columns: defaulted on the migrated row (a non-optional with no default is not
    /// lightweight-migratable) and each one round-trips through a save.
    @Test func theMigratedChannelCarriesTheV5ColumnsAndTheyRoundTrip() throws {
        let url = Self.temporaryStoreURL()
        defer { Self.remove(url) }
        try Self.makeV4Store(at: url)

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        let context = ModelContext(container)
        let channel = try #require(try context.fetch(FetchDescriptor<SubscribedChannel>()).first)
        #expect(channel.channelUrl == "")
        #expect(channel.approvalStatus == "APPROVED")
        #expect(channel.source == nil)
        #expect(channel.importedAt == nil)

        let imported = Date(timeIntervalSince1970: 1_700_000_000)
        channel.channelUrl = "https://www.youtube.com/channel/UCmMcOjsVehVlEOteyrhjI2Q"
        channel.approvalStatus = "AWAITING"
        channel.source = "USER_IMPORT"
        channel.importedAt = imported
        try context.save()

        let reread = try #require(try ModelContext(container).fetch(FetchDescriptor<SubscribedChannel>()).first)
        #expect(reread.channelUrl == "https://www.youtube.com/channel/UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(reread.approvalStatus == "AWAITING")
        #expect(reread.source == "USER_IMPORT")
        #expect(reread.importedAt == imported)
    }

    /// The same for the playlist's five new columns.
    @Test func theMigratedPlaylistCarriesTheV5ColumnsAndTheyRoundTrip() throws {
        let url = Self.temporaryStoreURL()
        defer { Self.remove(url) }
        try Self.makeV4Store(at: url)

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        let context = ModelContext(container)
        let playlist = try #require(try context.fetch(FetchDescriptor<SavedPlaylist>()).first)
        #expect(playlist.playlistUrl == "")
        #expect(playlist.uploaderName == nil)
        #expect(playlist.approvalStatus == "APPROVED")
        #expect(playlist.source == nil)
        #expect(playlist.importedAt == nil)

        let imported = Date(timeIntervalSince1970: 1_700_000_001)
        playlist.playlistUrl = "https://www.youtube.com/playlist?list=PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
        playlist.uploaderName = "Alafasy"
        playlist.approvalStatus = "AWAITING"
        playlist.source = "USER_IMPORT"
        playlist.importedAt = imported
        try context.save()

        let reread = try #require(try ModelContext(container).fetch(FetchDescriptor<SavedPlaylist>()).first)
        #expect(reread.playlistUrl == "https://www.youtube.com/playlist?list=PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc")
        #expect(reread.uploaderName == "Alafasy")
        #expect(reread.approvalStatus == "AWAITING")
        #expect(reread.source == "USER_IMPORT")
        #expect(reread.importedAt == imported)
        #expect(reread.itemCount == 7)
    }

    /// `SyncState` is live in the migrated schema: it inserts into a V4-store-turned-V5 and every
    /// field re-fetches, including the compound cursor's second half.
    @Test func aSyncStateRowInsertsIntoAMigratedStoreAndRefetches() throws {
        let url = Self.temporaryStoreURL()
        defer { Self.remove(url) }
        try Self.makeV4Store(at: url)

        let container = AppContainer.makeModelContainer(inMemory: false, storeURL: url)
        let context = ModelContext(container)
        #expect(try context.fetchCount(FetchDescriptor<SyncState>()) == 0)
        let syncedAt = Date(timeIntervalSince1970: 1_700_000_002)
        context.insert(SyncState(entityType: "subscriptions", userId: "uid-1", lastCursor: 1_699_999_999_000,
                                 lastDocId: "UCmMcOjsVehVlEOteyrhjI2Q", lastSyncAt: syncedAt))
        try context.save()

        let state = try #require(try ModelContext(container).fetch(FetchDescriptor<SyncState>()).first)
        #expect(state.entityType == "subscriptions")
        #expect(state.userId == "uid-1")
        #expect(state.lastCursor == 1_699_999_999_000)
        #expect(state.lastDocId == "UCmMcOjsVehVlEOteyrhjI2Q")
        #expect(state.lastSyncAt == syncedAt)
    }

    /// Room's composite primary key: one cursor per (entityType, userId) pair, and a second write
    /// of the same pair advances that row instead of adding one.
    @Test func theSyncStateCursorIsUniquePerEntityTypeAndUser() throws {
        let container = try ModelContainer(for: SyncState.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = ModelContext(container)
        context.insert(SyncState(entityType: "subscriptions", userId: "uid-1", lastCursor: 1))
        context.insert(SyncState(entityType: "playlists", userId: "uid-1", lastCursor: 2))
        context.insert(SyncState(entityType: "subscriptions", userId: "uid-2", lastCursor: 3))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<SyncState>()) == 3)

        context.insert(SyncState(entityType: "subscriptions", userId: "uid-1", lastCursor: 99))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<SyncState>()) == 3)
        let uid1Subs = FetchDescriptor<SyncState>(predicate: #Predicate { $0.entityType == "subscriptions" && $0.userId == "uid-1" })
        #expect(try context.fetch(uid1Subs).first?.lastCursor == 99)
    }

    /// `AccountBinding` is Android's single-row table: unique on `userId`, `initialMergeDone`
    /// round-trips (a `Bool` on a `@Model` is exactly the shape the `deleted` trap bit).
    @Test func anAccountBindingIsUniquePerUserAndItsMergeFlagRoundTrips() throws {
        let container = try ModelContainer(for: AccountBinding.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let context = ModelContext(container)
        let boundAt = Date(timeIntervalSince1970: 1_700_000_003)
        context.insert(AccountBinding(userId: "uid-1", boundAt: boundAt))
        try context.save()
        let binding = try #require(try context.fetch(FetchDescriptor<AccountBinding>()).first)
        #expect(binding.boundAt == boundAt)
        #expect(binding.initialMergeDone == false)

        binding.initialMergeDone = true
        try context.save()
        #expect(try ModelContext(container).fetch(FetchDescriptor<AccountBinding>()).first?.initialMergeDone == true)

        context.insert(AccountBinding(userId: "uid-1", boundAt: Date(timeIntervalSince1970: 1_700_000_004)))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<AccountBinding>()) == 1)
    }

    /// V5 column behaviour, same subject as the migration: an AWAITING (imported, unreviewed)
    /// channel must not render as an ordinary chip and must not count against the 30-channel cap,
    /// while `isSubscribed` stays unfiltered (matching `isFavorite`) so a re-add cannot duplicate.
    @Test func anAwaitingChannelIsHiddenFromItemsButStillReadsAsSubscribed() throws {
        let container = try ModelContainer(for: SubscribedChannel.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let seed = ModelContext(container)
        seed.insert(SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q", title: "Awaiting", avatarUrl: nil,
                                      approvalStatus: "AWAITING", source: "USER_IMPORT"))
        seed.insert(SubscribedChannel(channelId: "UCapproved00000000000000", title: "Approved", avatarUrl: nil))
        try seed.save()

        let store = SwiftDataSubscriptionsStore(modelContainer: container)
        #expect(store.items.map(\.channelId) == ["UCapproved00000000000000"])
        #expect(store.isSubscribed("UCmMcOjsVehVlEOteyrhjI2Q"))

        // A re-add of an awaiting row must not create a second row for the same channel.
        try store.toggle(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Re-added", avatarURL: nil)
        let rows = FetchDescriptor<SubscribedChannel>(predicate: #Predicate { $0.channelId == "UCmMcOjsVehVlEOteyrhjI2Q" })
        #expect(try ModelContext(container).fetchCount(rows) == 1)
    }

    /// The playlist twin.
    @Test func anAwaitingPlaylistIsHiddenFromItemsButStillReadsAsSaved() throws {
        let container = try ModelContainer(for: SavedPlaylist.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let seed = ModelContext(container)
        seed.insert(SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Awaiting", thumbnailUrl: nil,
                                  itemCount: 3, approvalStatus: "AWAITING", source: "USER_IMPORT"))
        seed.insert(SavedPlaylist(playlistId: "PLapproved", title: "Approved", thumbnailUrl: nil, itemCount: 1))
        try seed.save()

        let store = SwiftDataSavedPlaylistsStore(modelContainer: container)
        #expect(store.items.map(\.playlistId) == ["PLapproved"])
        #expect(store.isSaved("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"))

        try store.toggle(id: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Re-added", thumbnailURL: nil, itemCount: 3)
        let rows = FetchDescriptor<SavedPlaylist>(predicate: #Predicate { $0.playlistId == "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc" })
        #expect(try ModelContext(container).fetchCount(rows) == 1)
    }
}
