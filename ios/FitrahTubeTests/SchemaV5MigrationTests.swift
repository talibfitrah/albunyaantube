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

    /// A byte-historical V4 store on disk with one row of each synced type -- written through the
    /// FROZEN V4 types (`FavoritesSchemaV2.SavedPlaylist` and `FavoritesSchemaV3.SubscribedChannel`
    /// as `52549ec7`/`976d2e6f` declared them, aliased forward by V4), never through the live ones.
    /// That distinction is the whole of finding C1: writing it with today's types produces a file
    /// no pre-V5 build could have written, and the migration it "proves" is a tautology.
    private static func makeV4Store(at url: URL) throws {
        let v4 = Schema(versionedSchema: FavoritesSchemaV4.self)
        let container = try ModelContainer(for: v4, configurations: ModelConfiguration(schema: v4, url: url))
        let context = ModelContext(container)
        context.insert(FavoritesSchemaV4.FavoriteVideo(videoId: "xc7keR2piUM", title: "F", channelName: "C", thumbnailUrl: nil, durationSeconds: 12))
        context.insert(FavoritesSchemaV4.SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "P", thumbnailUrl: nil, itemCount: 7))
        context.insert(FavoritesSchemaV4.SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q", title: "S", avatarUrl: nil))
        try context.save()
    }

    /// The migration pin, and the assertion finding C1 says was never really made: every row of a
    /// store written by the FROZEN V4 types survives the V4 -> V5 stage with its values intact.
    /// Against the pre-freeze ladder (every `FavoritesSchemaVn.models` returning the LIVE types)
    /// this fails -- V5's new columns also changed what "V4" hashed to, the store opened as
    /// `NSCocoaErrorDomain 134504` "Cannot use staged migration with an unknown model version",
    /// and `makeModelContainer`'s recovery path deleted it, so all three `#require`s got nil.
    /// The earlier comment here claimed it would fail against a container still on
    /// `FavoritesSchemaV4`; it would not -- that container reopens the same file with no migration
    /// at all. `aSyncStateRowInsertsIntoAMigratedStoreAndRefetches` is what pins the constant flip.
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

        let rebound = Date(timeIntervalSince1970: 1_700_000_004)
        context.insert(AccountBinding(userId: "uid-1", boundAt: rebound))
        try context.save()
        #expect(try context.fetchCount(FetchDescriptor<AccountBinding>()) == 1)
        // What `#Unique`'s upsert actually does to the row it collides with -- on the record so
        // Task 23 does not re-bind by inserting and silently lose an already-merged flag.
        let reread = try #require(try ModelContext(container).fetch(FetchDescriptor<AccountBinding>()).first)
        #expect(reread.boundAt == rebound)
        #expect(reread.initialMergeDone == false)
    }

    /// V5 column behaviour, same subject as the migration: an AWAITING (imported, unreviewed)
    /// channel must not render as an ordinary chip and must not count against the 30-channel cap,
    /// while `isSubscribed` stays unfiltered (matching `isFavorite`) so a re-add cannot duplicate.
    ///
    /// **Task 23 review I2** adds the third and fourth rows of the table. The M3 ruling made this
    /// predicate FAIL CLOSED (`== "APPROVED"`, not `!= "AWAITING"`) and nothing distinguished the
    /// two spellings: an APPROVED row and an AWAITING row behave identically under either, so
    /// reverting the ruled fix was green. A REJECTED row and a status no build has heard of are
    /// what tell them apart — under the old spelling both rendered as ordinary chips and both
    /// counted against the cap.
    @Test func anAwaitingChannelIsHiddenFromItemsButStillReadsAsSubscribed() throws {
        let container = try ModelContainer(for: SubscribedChannel.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let seed = ModelContext(container)
        seed.insert(SubscribedChannel(channelId: "UCmMcOjsVehVlEOteyrhjI2Q", title: "Awaiting", avatarUrl: nil,
                                      approvalStatus: "AWAITING", source: "USER_IMPORT"))
        seed.insert(SubscribedChannel(channelId: "UCrejected00000000000000", title: "Rejected", avatarUrl: nil,
                                      approvalStatus: "REJECTED", source: "USER_IMPORT"))
        seed.insert(SubscribedChannel(channelId: "UCunknown000000000000000", title: "Unknown", avatarUrl: nil,
                                      approvalStatus: "SOME_LATER_STATUS", source: "USER_IMPORT"))
        seed.insert(SubscribedChannel(channelId: "UCapproved00000000000000", title: "Approved", avatarUrl: nil))
        try seed.save()

        let store = SwiftDataSubscriptionsStore(modelContainer: container)
        #expect(store.items.map(\.channelId) == ["UCapproved00000000000000"])
        #expect(store.isSubscribed("UCmMcOjsVehVlEOteyrhjI2Q"))
        #expect(store.isSubscribed("UCrejected00000000000000"))

        // A re-add of an awaiting row must not create a second row. TWICE: `toggle` on a live row
        // unsubscribes it, so only the second call takes the re-add branch -- asserting after the
        // first call cannot tell the two paths apart, since both leave exactly one row.
        try store.toggle(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Tombstoned", avatarURL: nil)
        try store.toggle(id: "UCmMcOjsVehVlEOteyrhjI2Q", name: "Re-added", avatarURL: nil)
        let rows = FetchDescriptor<SubscribedChannel>(predicate: #Predicate { $0.channelId == "UCmMcOjsVehVlEOteyrhjI2Q" })
        let after = ModelContext(container)
        #expect(try after.fetchCount(rows) == 1)
        let row = try #require(try after.fetch(rows).first)
        #expect(row.isRemoved == false)
        #expect(row.title == "Re-added")
        // The re-add does not launder an unreviewed row into an approved one: still hidden.
        #expect(row.approvalStatus == "AWAITING")
        #expect(store.items.map(\.channelId) == ["UCapproved00000000000000"])
    }

    /// The playlist twin, including review I2's REJECTED and unknown-status rows.
    @Test func anAwaitingPlaylistIsHiddenFromItemsButStillReadsAsSaved() throws {
        let container = try ModelContainer(for: SavedPlaylist.self, configurations: ModelConfiguration(isStoredInMemoryOnly: true))
        let seed = ModelContext(container)
        seed.insert(SavedPlaylist(playlistId: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Awaiting", thumbnailUrl: nil,
                                  itemCount: 3, approvalStatus: "AWAITING", source: "USER_IMPORT"))
        seed.insert(SavedPlaylist(playlistId: "PLrejected", title: "Rejected", thumbnailUrl: nil,
                                  itemCount: 2, approvalStatus: "REJECTED", source: "USER_IMPORT"))
        seed.insert(SavedPlaylist(playlistId: "PLunknown", title: "Unknown", thumbnailUrl: nil,
                                  itemCount: 4, approvalStatus: "SOME_LATER_STATUS", source: "USER_IMPORT"))
        seed.insert(SavedPlaylist(playlistId: "PLapproved", title: "Approved", thumbnailUrl: nil, itemCount: 1))
        try seed.save()

        let store = SwiftDataSavedPlaylistsStore(modelContainer: container)
        #expect(store.items.map(\.playlistId) == ["PLapproved"])
        #expect(store.isSaved("PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"))
        #expect(store.isSaved("PLrejected"))

        // Same two-call shape as the channel test above, for the same reason.
        try store.toggle(id: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Tombstoned", thumbnailURL: nil, itemCount: 3)
        try store.toggle(id: "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc", title: "Re-added", thumbnailURL: nil, itemCount: 3)
        let rows = FetchDescriptor<SavedPlaylist>(predicate: #Predicate { $0.playlistId == "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc" })
        let after = ModelContext(container)
        #expect(try after.fetchCount(rows) == 1)
        let row = try #require(try after.fetch(rows).first)
        #expect(row.isRemoved == false)
        #expect(row.title == "Re-added")
        #expect(row.approvalStatus == "AWAITING")
        #expect(store.items.map(\.playlistId) == ["PLapproved"])
    }

    /// The freeze itself (fix round 1 / C1): every version's entity set, and every entity's stored
    /// column set, exactly as the commit that introduced that version declared it. Adding a column
    /// to a live entity WITHOUT declaring a `FavoritesSchemaV6` turns this red -- which is the
    /// point: mutating a type an older version still names rewrites what THAT version hashes to,
    /// and every store written by a shipped build becomes an unknown model version. It also pins
    /// the assumption the nesting rests on -- a nested `@Model` keeps its plain entity name, so
    /// `FavoritesSchemaV3.SubscribedChannel` and V5's are one CoreData entity across the stage.
    @Test func everyFrozenVersionKeepsItsHistoricalColumnSet() {
        func shape(_ versioned: any VersionedSchema.Type) -> [String: [String]] {
            Schema(versionedSchema: versioned).entitiesByName.mapValues { $0.storedPropertiesByName.keys.sorted() }
        }
        // `423ae0ec` + `2c611683`, unchanged through V5.
        let favoriteVideo = ["addedAt", "approvalStatus", "channelName", "dirty", "durationSeconds",
                             "importedAt", "isRemoved", "source", "thumbnailUrl", "title",
                             "updatedAt", "userId", "videoId"]
        // `52549ec7`, unchanged through V4.
        let savedPlaylistV2 = ["addedAt", "dirty", "isRemoved", "itemCount", "playlistId",
                               "thumbnailUrl", "title", "updatedAt", "userId"]
        // `976d2e6f`, unchanged through V4.
        let subscribedChannelV3 = ["avatarUrl", "channelId", "dirty", "followedAt", "isRemoved",
                                   "title", "updatedAt", "userId"]
        // `7471ea99`, unchanged through V5.
        let offlineItem = ["audioOnly", "bytesWritten", "channelName", "completedAt", "createdAt",
                           "errorCode", "id", "localPath", "qualityLabel", "resumeData", "status",
                           "thumbnailUrl", "title", "totalBytes", "videoId"]
        // `26d6bde9`: five new columns on the playlist, four on the channel, two new entities.
        let savedPlaylistV5 = ["addedAt", "approvalStatus", "dirty", "importedAt", "isRemoved",
                               "itemCount", "playlistId", "playlistUrl", "source", "thumbnailUrl",
                               "title", "updatedAt", "uploaderName", "userId"]
        let subscribedChannelV5 = ["approvalStatus", "avatarUrl", "channelId", "channelUrl",
                                   "dirty", "followedAt", "importedAt", "isRemoved", "source",
                                   "title", "updatedAt", "userId"]

        #expect(shape(FavoritesSchemaV1.self) == ["FavoriteVideo": favoriteVideo])
        #expect(shape(FavoritesSchemaV2.self) == ["FavoriteVideo": favoriteVideo,
                                                  "SavedPlaylist": savedPlaylistV2])
        #expect(shape(FavoritesSchemaV3.self) == ["FavoriteVideo": favoriteVideo,
                                                  "SavedPlaylist": savedPlaylistV2,
                                                  "SubscribedChannel": subscribedChannelV3])
        #expect(shape(FavoritesSchemaV4.self) == ["FavoriteVideo": favoriteVideo,
                                                  "SavedPlaylist": savedPlaylistV2,
                                                  "SubscribedChannel": subscribedChannelV3,
                                                  "OfflineItem": offlineItem])
        #expect(shape(FavoritesSchemaV5.self) == ["FavoriteVideo": favoriteVideo,
                                                  "SavedPlaylist": savedPlaylistV5,
                                                  "SubscribedChannel": subscribedChannelV5,
                                                  "OfflineItem": offlineItem,
                                                  "SyncState": ["entityType", "lastCursor", "lastDocId",
                                                                "lastSyncAt", "userId"],
                                                  "AccountBinding": ["boundAt", "initialMergeDone", "userId"]])
        // CF-A-50 (Task 41): ONE new column, `userId` on `OfflineItem`; everything else aliased.
        #expect(shape(FavoritesSchemaV6.self) == ["FavoriteVideo": favoriteVideo,
                                                  "SavedPlaylist": savedPlaylistV5,
                                                  "SubscribedChannel": subscribedChannelV5,
                                                  "OfflineItem": (offlineItem + ["userId"]).sorted(),
                                                  "SyncState": ["entityType", "lastCursor", "lastDocId",
                                                                "lastSyncAt", "userId"],
                                                  "AccountBinding": ["boundAt", "initialMergeDone", "userId"]])
    }

    /// CF-A-50 (Task 41): a V5 store's `OfflineItem` rows -- written through the FROZEN V5 type,
    /// which has no owner column -- survive the V5 -> V6 lightweight stage and read back as the
    /// GUEST's (`userId == ""`): the only honest default, since nothing recorded who saved them.
    /// The on-disk stage is the proof that a defaulted column is a lightweight migration here.
    @Test func aV5StoreOnDiskMigratesToV6GivingOfflineItemsTheGuestOwner() throws {
        let url = Self.temporaryStoreURL()
        defer { Self.remove(url) }
        try Self.makeV5Store(at: url)

        let context = ModelContext(AppContainer.makeModelContainer(inMemory: false, storeURL: url))
        let items = try context.fetch(FetchDescriptor<OfflineItem>())
        #expect(items.count == 1, "the row was lost, or the recovery path rebuilt the store")
        let item = try #require(items.first)
        #expect(item.videoId == "xc7keR2piUM")
        #expect(item.userId == "")
        let favorite = try #require(try context.fetch(FetchDescriptor<FavoriteVideo>()).first)
        #expect(favorite.videoId == "xc7keR2piUM", "an untouched entity did not survive the stage")
    }

    /// A V5 store on disk written through the FROZEN V5 types (`FavoritesSchemaV5.OfflineItem` has
    /// no owner column) -- the `makeV4Store` shape, so the V5 container is released before
    /// `makeModelContainer` migrates the file.
    private static func makeV5Store(at url: URL) throws {
        let v5 = Schema(versionedSchema: FavoritesSchemaV5.self)
        let container = try ModelContainer(for: v5, configurations: ModelConfiguration(schema: v5, url: url))
        let context = ModelContext(container)
        context.insert(FavoritesSchemaV5.OfflineItem(videoId: "xc7keR2piUM", title: "Lecture", channelName: nil,
                                                     thumbnailUrl: nil, qualityLabel: "360p", audioOnly: true))
        context.insert(FavoritesSchemaV5.FavoriteVideo(videoId: "xc7keR2piUM", title: "F", channelName: "C",
                                                       thumbnailUrl: nil, durationSeconds: 12))
        try context.save()
    }
}
