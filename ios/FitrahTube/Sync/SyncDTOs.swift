import Foundation

/// The three synced entity types. Declared HERE rather than with the client because
/// `SyncState.entityType` (`SyncModels.swift`) stores this enum's `rawValue` and the merge
/// decisions key on it.
nonisolated enum SyncEntityType: String, Sendable, CaseIterable {
    case subscriptions, playlists, favorites

    /// The PUT/DELETE path segment: `api/account/{subscriptions|playlists|favorites}/{id}`
    /// (`SyncController.java:86-138`).
    var path: String { rawValue }

    /// The GET query name, which is NOT the path segment for subscriptions: the pull endpoint
    /// takes `subs` / `subs_id` (`SyncController.java:41-64`). `SyncState.entityType` uses
    /// `rawValue` -- do not confuse the two, or the cursor is written under a name nothing reads.
    var queryName: String { self == .subscriptions ? "subs" : rawValue }
}

/// One type's slice of a pull response. The cursor is compound: `nextCursor` is the epoch-millis
/// timestamp and `nextCursorId` the last document id at it, so a page break inside a group of rows
/// sharing one millisecond resumes without skipping or repeating (`SyncDtos.kt:12-19`).
nonisolated struct SyncPage<T: Decodable & Sendable>: Decodable, Sendable {
    var items: [T]
    var nextCursor: Int?
    var nextCursorId: String?
}

/// `GET /api/account/sync` -- all three types in one round trip.
nonisolated struct SyncResponse: Decodable, Sendable {
    var subscriptions: SyncPage<SubscriptionSyncDTO>
    var playlists: SyncPage<PlaylistSyncDTO>
    var favorites: SyncPage<FavoriteSyncDTO>
}

// MARK: - Pull rows (`SyncDtos.kt:21-65`, wire names verbatim)

/// `deleted` is the WIRE name. The SwiftData property is `isRemoved`, because a `@Model` property
/// literally named `deleted` is silently reverted by the next `ModelContext.save()` (the Core Data
/// `isDeleted` KVC collision, `FavoriteVideo.swift:12-18`). `SyncCodec` is the ONE place that
/// bridges the two names (ruling C9) -- never spell either name against the other anywhere else.
/// Timestamps are epoch millis, as Android's `Long` columns are.
nonisolated struct SubscriptionSyncDTO: Codable, Sendable, Equatable {
    var entityId: String
    var deleted: Bool
    var updatedAt: Int
    var channelUrl: String
    var name: String
    var avatarUrl: String?
    var subscribedAt: Int
    var approvalStatus: String?
    var source: String?
    var importedAt: Int?
}

nonisolated struct PlaylistSyncDTO: Codable, Sendable, Equatable {
    var entityId: String
    var deleted: Bool
    var updatedAt: Int
    var playlistUrl: String
    var name: String
    var thumbnailUrl: String?
    var uploaderName: String?
    var savedAt: Int
    var approvalStatus: String?
    var source: String?
    var importedAt: Int?
}

nonisolated struct FavoriteSyncDTO: Codable, Sendable, Equatable {
    var entityId: String
    var deleted: Bool
    var updatedAt: Int
    var title: String
    var channelName: String
    var thumbnailUrl: String?
    var durationSeconds: Int
    var addedAt: Int
    var approvalStatus: String?
    var source: String?
    var importedAt: Int?
}
