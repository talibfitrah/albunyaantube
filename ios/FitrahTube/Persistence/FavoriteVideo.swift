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

enum FavoritesMigrationPlan: SchemaMigrationPlan {
    static var schemas: [any VersionedSchema.Type] { [FavoritesSchemaV1.self] }
    static var stages: [MigrationStage] { [] }
}

@Model final class FavoriteVideo {
    @Attribute(.unique) var videoId: String
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
