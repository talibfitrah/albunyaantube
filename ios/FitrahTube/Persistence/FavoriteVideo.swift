import Foundation
import SwiftData

/// Android's Room `favorite_videos` table, columns verbatim (`camelCase` here vs `snake_case`
/// there is SwiftData's convention, not a semantic change) --
/// `docs/superpowers/plans/2026-08-23-ios-phase1-research/favorites-settings-about.md:20-33`.
/// Defaults mirror the Room column defaults so a plain `insert(FavoriteVideo(...))` behaves like
/// Android's fresh row: `addedAt` = now (sort key), `updatedAt` = epoch 0 (unset "server
/// timestamp, monotonicity guard" -- only the sync layer advances it), `isRemoved`/`dirty` =
/// false, `approvalStatus` = "APPROVED".
///
/// The Room/spec column is called `deleted`, but that exact identifier is reserved by SwiftData's
/// CoreData-backed storage: a `@Model` property literally named `deleted` mutates correctly in
/// memory but is silently reverted to its previous value by the next `ModelContext.save()` --
/// confirmed by a controlled A/B test (toggling `existing.deleted` on an existing row, saving,
/// then re-reading the same row in the same context returns the *pre-save* value; renaming the
/// property to `isRemoved` with no other change makes the identical sequence persist correctly).
/// Kept as `isRemoved` for that reason; the tombstone semantics are unchanged.
/// Gate A-I1. Introduced while there is exactly one version and it costs nothing: an unversioned
/// `@Model` gives `ModelContainer(for:)` no way to migrate, so the first change lightweight
/// migration can't infer (a non-optional property with no default, a rename, a type change) throws
/// for every user who already has a store on disk -- and never on a clean simulator, so it ships.
/// Add a `V2` `VersionedSchema` plus a `MigrationStage` to `stages` when that change lands.
enum FavoritesSchemaV1: VersionedSchema {
    static let versionIdentifier = Schema.Version(1, 0, 0)
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self] }
}

/// Plan C Task 4 adds `SavedPlaylist` (a new entity, nothing else changes) -- the lightweight
/// stage below is exactly the migration the V1 doc comment promised.
enum FavoritesSchemaV2: VersionedSchema {
    static let versionIdentifier = Schema.Version(2, 0, 0)
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self] }
}

/// Plan C Task 5 adds `SubscribedChannel`, the same way.
enum FavoritesSchemaV3: VersionedSchema {
    static let versionIdentifier = Schema.Version(3, 0, 0)
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self] }
}

/// Phase 3 Task 3 adds `OfflineItem` (Save for offline), the same way.
enum FavoritesSchemaV4: VersionedSchema {
    static let versionIdentifier = Schema.Version(4, 0, 0)
    static var models: [any PersistentModel.Type] { [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self] }
}

/// Phase 4 Task 20: the sync columns. `SubscribedChannel`/`SavedPlaylist` gain the URL and import
/// columns the wire needs (mirroring Room v11) and the two sync bookkeeping entities arrive --
/// every addition is a defaulted/optional property or a new entity, so the stage stays lightweight.
/// The Room/Swift name drift (`title`/`name`, `followedAt`/`subscribedAt`, `addedAt`/`savedAt`,
/// `isRemoved`/`deleted`) is kept DELIBERATELY: renaming a `@Model` property is not a lightweight
/// migration, and the drift is encoded once, in the sync codec.
enum FavoritesSchemaV5: VersionedSchema {
    static let versionIdentifier = Schema.Version(5, 0, 0)
    static var models: [any PersistentModel.Type] {
        [FavoriteVideo.self, SavedPlaylist.self, SubscribedChannel.self, OfflineItem.self,
         SyncState.self, AccountBinding.self]
    }
}

enum FavoritesMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [FavoritesSchemaV1.self, FavoritesSchemaV2.self, FavoritesSchemaV3.self, FavoritesSchemaV4.self, FavoritesSchemaV5.self] }
    static var stages: [MigrationStage] {
        [.lightweight(fromVersion: FavoritesSchemaV1.self, toVersion: FavoritesSchemaV2.self),
         .lightweight(fromVersion: FavoritesSchemaV2.self, toVersion: FavoritesSchemaV3.self),
         .lightweight(fromVersion: FavoritesSchemaV3.self, toVersion: FavoritesSchemaV4.self),
         .lightweight(fromVersion: FavoritesSchemaV4.self, toVersion: FavoritesSchemaV5.self)]
    }
}

@Model final class FavoriteVideo {
    /// Gate wave-2 W11: unique on the *pair*, not on `videoId` alone. SwiftData's unique attribute
    /// upserts on collision, so once phase 4 sets a real `currentUserId`, user B favoriting a video
    /// user A already had would have silently rewritten A's row -- its `userId`, its sync metadata,
    /// its snapshot fields -- with no error, while `FavoritesStore` documents per-user scoping the
    /// schema could not actually provide. Done now, inside V1, because it costs nothing while
    /// `userId` is uniformly `""` (the pair is exactly as unique as `videoId` was) and nothing has
    /// shipped; after auth lands it would need a data migration to deduplicate first.
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
         isRemoved: Bool = false, dirty: Bool = false, approvalStatus: String = "APPROVED",
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
