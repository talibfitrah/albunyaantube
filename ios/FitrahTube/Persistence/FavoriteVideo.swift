import Foundation
import SwiftData

/// The migration ladder, FROZEN per version (Task 20 fix round 1, review finding C1).
///
/// Each `FavoritesSchemaVn` owns the entity shapes THAT version shipped. An entity is *declared*
/// in the version that last changed it and *aliased forward* by every later version, so
/// `Schema(versionedSchema: FavoritesSchemaV4.self)` still hashes to what a V4 build wrote.
///
/// Before this freeze every version returned the LIVE Swift types, which is fine only while each
/// bump adds a whole new entity -- true at V1->V2, V2->V3 and V3->V4. `26d6bde9` (V5) was the
/// first bump to add COLUMNS to an already-versioned entity, and it therefore also changed what
/// "V4" hashed to: a store written by any earlier build became an unknown model version
/// (`NSCocoaErrorDomain 134504`, "Cannot use staged migration with an unknown model version") and
/// `AppContainer.makeModelContainer`'s recovery path deleted and rebuilt it -- every local
/// favorite, subscription and saved playlist gone on upgrade.
///
/// **The rule for the next column** (Part B tasks 23 and 28 both add some): never add a property
/// to a type an older version still points at. Declare `FavoritesSchemaV7.<Entity>` with the new
/// column, alias every untouched entity forward, add the lightweight stage, and repoint the live
/// typealiases at the bottom of this file. `SchemaV5MigrationTests`'
/// `everyFrozenVersionKeepsItsHistoricalColumnSet` is what turns a retroactive edit red.
enum FavoritesSchemaV1: VersionedSchema {
    static let versionIdentifier = Schema.Version(1, 0, 0)
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self] }

    /// Android's Room `favorite_videos` table, columns verbatim (`camelCase` here vs `snake_case`
    /// there is SwiftData's convention, not a semantic change) --
    /// `docs/superpowers/plans/2026-08-23-ios-phase1-research/favorites-settings-about.md:20-33`.
    /// Defaults mirror the Room column defaults so a plain `insert(FavoriteVideo(...))` behaves
    /// like Android's fresh row: `addedAt` = now (sort key), `updatedAt` = epoch 0 (unset "server
    /// timestamp, monotonicity guard" -- only the sync layer advances it), `isRemoved`/`dirty` =
    /// false, `approvalStatus` = ImportProvenance.approved.
    ///
    /// The Room/spec column is called `deleted`, but that exact identifier is reserved by
    /// SwiftData's CoreData-backed storage: a `@Model` property literally named `deleted` mutates
    /// correctly in memory but is silently reverted to its previous value by the next
    /// `ModelContext.save()` -- confirmed by a controlled A/B test (toggling `existing.deleted` on
    /// an existing row, saving, then re-reading the same row in the same context returns the
    /// *pre-save* value; renaming the property to `isRemoved` with no other change makes the
    /// identical sequence persist correctly). Kept as `isRemoved` for that reason; the tombstone
    /// semantics are unchanged.
    ///
    /// This shape has not changed since `2c611683`, so V2-V6 alias it rather than re-declaring it.
    @Model final class FavoriteVideo {
        /// Gate wave-2 W11: unique on the *pair*, not on `videoId` alone. SwiftData's unique
        /// attribute upserts on collision, so once phase 4 sets a real `currentUserId`, user B
        /// favoriting a video user A already had would have silently rewritten A's row -- its
        /// `userId`, its sync metadata, its snapshot fields -- with no error, while
        /// `FavoritesStore` documents per-user scoping the schema could not actually provide.
        /// Done inside V1, because it cost nothing while `userId` was uniformly `""` (the pair is
        /// exactly as unique as `videoId` was) and nothing had shipped; after auth lands it would
        /// need a data migration to deduplicate first.
        #Unique<FavoriteVideo>([\.videoId, \.userId])

        var videoId: String
        var title: String
        var channelName: String
        var thumbnailUrl: String?
        var durationSeconds: Int
        var addedAt: Date
        var userId: String
        var updatedAt: Date
        var isRemoved: Bool
        var dirty: Bool
        var approvalStatus: String
        var source: String?
        var importedAt: Date?

        init(videoId: String, title: String, channelName: String, thumbnailUrl: String?, durationSeconds: Int,
             addedAt: Date = Date(), userId: String = "", updatedAt: Date = Date(timeIntervalSince1970: 0),
             isRemoved: Bool = false, dirty: Bool = false, approvalStatus: String = ImportProvenance.approved,
             source: String? = nil, importedAt: Date? = nil) {
            self.videoId = videoId
            self.title = title
            self.channelName = channelName
            self.thumbnailUrl = thumbnailUrl
            self.durationSeconds = durationSeconds
            self.addedAt = addedAt
            self.userId = userId
            self.updatedAt = updatedAt
            self.isRemoved = isRemoved
            self.dirty = dirty
            self.approvalStatus = approvalStatus
            self.source = source
            self.importedAt = importedAt
        }
    }
}

/// Plan C Task 4 (`52549ec7`) adds `SavedPlaylist` (a new entity, nothing else changes) -- the
/// lightweight stage below is exactly the migration the V1 doc comment promised. That version's
/// frozen shape is declared in `SavedPlaylistsStore.swift`.
enum FavoritesSchemaV2: VersionedSchema {
    static let versionIdentifier = Schema.Version(2, 0, 0)
    typealias FavoriteVideo = FavoritesSchemaV1.FavoriteVideo
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self] }
}

/// Plan C Task 5 (`976d2e6f`) adds `SubscribedChannel`, the same way -- frozen shape in
/// `SubscriptionsStore.swift`.
enum FavoritesSchemaV3: VersionedSchema {
    static let versionIdentifier = Schema.Version(3, 0, 0)
    typealias FavoriteVideo = FavoritesSchemaV1.FavoriteVideo
    typealias SavedPlaylist = FavoritesSchemaV2.SavedPlaylist
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self] }
}

/// Phase 3 Task 3 (`7471ea99`) adds `OfflineItem` (Save for offline), the same way -- shape in
/// `Features/Offline/OfflineItem.swift`. That entity has not changed since, so V5 aliases it.
enum FavoritesSchemaV4: VersionedSchema {
    static let versionIdentifier = Schema.Version(4, 0, 0)
    typealias FavoriteVideo = FavoritesSchemaV1.FavoriteVideo
    typealias SavedPlaylist = FavoritesSchemaV2.SavedPlaylist
    typealias SubscribedChannel = FavoritesSchemaV3.SubscribedChannel
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self] }
}

/// Phase 4 Task 20: the sync columns. `SubscribedChannel`/`SavedPlaylist` gain the URL and import
/// columns the wire needs (mirroring Room v11) -- so both are RE-DECLARED here at their new shapes
/// rather than aliased -- and the two sync bookkeeping entities arrive. Every addition is a
/// defaulted/optional property or a new entity, so the stage stays lightweight.
/// The Room/Swift name drift (`title`/`name`, `followedAt`/`subscribedAt`, `addedAt`/`savedAt`,
/// `isRemoved`/`deleted`) is kept DELIBERATELY: renaming a `@Model` property is not a lightweight
/// migration, and the drift is encoded once, in the sync codec.
enum FavoritesSchemaV5: VersionedSchema {
    static let versionIdentifier = Schema.Version(5, 0, 0)
    typealias FavoriteVideo = FavoritesSchemaV1.FavoriteVideo
    typealias OfflineItem = FavoritesSchemaV4.OfflineItem
    static var models: [any PersistentModel.Type] {
        [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self,
         SyncState.self, AccountBinding.self]
    }
}

/// Task 41 (CF-A-50): `OfflineItem` gains its owner column, so it is RE-DECLARED at V6
/// (`Features/Offline/OfflineItem.swift`) and every other entity is aliased forward from the
/// version that last declared it. One defaulted property, so the stage stays lightweight —
/// `SchemaV5MigrationTests.aV5StoreOnDiskMigratesToV6GivingOfflineItemsTheGuestOwner` is the proof.
enum FavoritesSchemaV6: VersionedSchema {
    static let versionIdentifier = Schema.Version(6, 0, 0)
    typealias FavoriteVideo = FavoritesSchemaV1.FavoriteVideo
    typealias SavedPlaylist = FavoritesSchemaV5.SavedPlaylist
    typealias SubscribedChannel = FavoritesSchemaV5.SubscribedChannel
    typealias SyncState = FavoritesSchemaV5.SyncState
    typealias AccountBinding = FavoritesSchemaV5.AccountBinding
    static var models: [any PersistentModel.Type] {
        [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self,
         SyncState.self, AccountBinding.self]
    }
}

/// The LIVE entities: the app, its stores and the sync codec all spell these unqualified, and they
/// always name the newest version's shapes. Repoint them (and only them) when a V7 lands.
typealias FavoriteVideo = FavoritesSchemaV6.FavoriteVideo
typealias SavedPlaylist = FavoritesSchemaV6.SavedPlaylist
typealias SubscribedChannel = FavoritesSchemaV6.SubscribedChannel
typealias OfflineItem = FavoritesSchemaV6.OfflineItem
typealias SyncState = FavoritesSchemaV6.SyncState
typealias AccountBinding = FavoritesSchemaV6.AccountBinding

enum FavoritesMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [FavoritesSchemaV1.self, FavoritesSchemaV2.self, FavoritesSchemaV3.self, FavoritesSchemaV4.self, FavoritesSchemaV5.self, FavoritesSchemaV6.self] }
    static var stages: [MigrationStage] {
        [.lightweight(fromVersion: FavoritesSchemaV1.self, toVersion: FavoritesSchemaV2.self),
         .lightweight(fromVersion: FavoritesSchemaV2.self, toVersion: FavoritesSchemaV3.self),
         .lightweight(fromVersion: FavoritesSchemaV3.self, toVersion: FavoritesSchemaV4.self),
         .lightweight(fromVersion: FavoritesSchemaV4.self, toVersion: FavoritesSchemaV5.self),
         .lightweight(fromVersion: FavoritesSchemaV5.self, toVersion: FavoritesSchemaV6.self)]
    }
}
