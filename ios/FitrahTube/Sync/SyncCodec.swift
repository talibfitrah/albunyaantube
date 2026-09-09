import Foundation

/// Ruling C9: the ONE bridge between the sync wire and the store. Two name drifts live here and
/// nowhere else --
///  * the wire's `deleted` against the SwiftData `isRemoved` (see `SyncDTOs.swift` for why the
///    property cannot be called `deleted`), and
///  * Room's field names against the Swift ones (`name`/`title`, `subscribedAt`/`followedAt`,
///    `savedAt`/`addedAt`), which Task 20 kept deliberately because renaming a `@Model` property
///    is not a lightweight migration.
///
/// `apply` never touches the row's identity (`channelId`/`playlistId`/`videoId`) or its `userId`:
/// the caller fetched or inserted the row under that pair, and rewriting it here would break the
/// `#Unique` it was found by.
nonisolated enum SyncCodec {

    // MARK: - Server row -> local row

    /// A null server `approvalStatus` defaults to "APPROVED" (`SyncManager.kt:379,397,415`), and
    /// the row comes out clean: the server copy is by definition already synced.
    @MainActor static func apply(_ dto: SubscriptionSyncDTO, to row: SubscribedChannel) {
        row.channelUrl = dto.channelUrl
        row.title = dto.name
        row.avatarUrl = dto.avatarUrl
        row.followedAt = date(millis: dto.subscribedAt)
        row.updatedAt = date(millis: dto.updatedAt)
        row.isRemoved = dto.deleted
        row.dirty = false
        row.approvalStatus = dto.approvalStatus ?? "APPROVED"
        row.source = dto.source
        row.importedAt = dto.importedAt.map { date(millis: $0) }
    }

    /// `itemCount` is untouched: no sync DTO carries it and the count chip renders it.
    @MainActor static func apply(_ dto: PlaylistSyncDTO, to row: SavedPlaylist) {
        row.playlistUrl = dto.playlistUrl
        row.title = dto.name
        row.thumbnailUrl = dto.thumbnailUrl
        row.uploaderName = dto.uploaderName
        row.addedAt = date(millis: dto.savedAt)
        row.updatedAt = date(millis: dto.updatedAt)
        row.isRemoved = dto.deleted
        row.dirty = false
        row.approvalStatus = dto.approvalStatus ?? "APPROVED"
        row.source = dto.source
        row.importedAt = dto.importedAt.map { date(millis: $0) }
    }

    @MainActor static func apply(_ dto: FavoriteSyncDTO, to row: FavoriteVideo) {
        row.title = dto.title
        row.channelName = dto.channelName
        row.thumbnailUrl = dto.thumbnailUrl
        row.durationSeconds = dto.durationSeconds
        row.addedAt = date(millis: dto.addedAt)
        row.updatedAt = date(millis: dto.updatedAt)
        row.isRemoved = dto.deleted
        row.dirty = false
        row.approvalStatus = dto.approvalStatus ?? "APPROVED"
        row.source = dto.source
        row.importedAt = dto.importedAt.map { date(millis: $0) }
    }

    // MARK: - Local row -> push body

    /// `PUT api/account/subscriptions/{id}`. A tombstone is a DELETE, so no body carries `deleted`.
    @MainActor static func body(for row: SubscribedChannel) -> Data {
        json(PutSubscriptionRequest(channelUrl: row.channelUrl, name: row.title,
                                    avatarUrl: row.avatarUrl, subscribedAt: millis(row.followedAt),
                                    approvalStatus: row.approvalStatus, source: row.source,
                                    importedAt: row.importedAt.map(millis)))
    }

    @MainActor static func body(for row: SavedPlaylist) -> Data {
        json(PutPlaylistRequest(playlistUrl: row.playlistUrl, name: row.title,
                                thumbnailUrl: row.thumbnailUrl, uploaderName: row.uploaderName,
                                savedAt: millis(row.addedAt), approvalStatus: row.approvalStatus,
                                source: row.source, importedAt: row.importedAt.map(millis)))
    }

    @MainActor static func body(for row: FavoriteVideo) -> Data {
        json(PutFavoriteRequest(title: row.title, channelName: row.channelName,
                                thumbnailUrl: row.thumbnailUrl, durationSeconds: row.durationSeconds,
                                addedAt: millis(row.addedAt), approvalStatus: row.approvalStatus,
                                source: row.source, importedAt: row.importedAt.map(millis)))
    }

    // MARK: - Epoch millis <-> Date

    /// The wire and `SyncState.lastCursor` are epoch millis (Android's `Long`); the store is `Date`.
    static func date(millis: Int) -> Date { Date(timeIntervalSince1970: Double(millis) / 1000) }
    static func millis(_ date: Date) -> Int { Int((date.timeIntervalSince1970 * 1000).rounded()) }

    // MARK: - Wire bodies (`SyncDtos.kt:67-101`)

    /// Private: `body(for:)` is the only producer, and every field name here is a wire name that
    /// must not leak into a second spelling. A nil optional is synthesised as `encodeIfPresent`,
    /// so it is OMITTED rather than sent as JSON null.
    private struct PutSubscriptionRequest: Encodable {
        let channelUrl: String
        let name: String
        let avatarUrl: String?
        let subscribedAt: Int
        let approvalStatus: String?
        let source: String?
        let importedAt: Int?
    }

    private struct PutPlaylistRequest: Encodable {
        let playlistUrl: String
        let name: String
        let thumbnailUrl: String?
        let uploaderName: String?
        let savedAt: Int
        let approvalStatus: String?
        let source: String?
        let importedAt: Int?
    }

    private struct PutFavoriteRequest: Encodable {
        let title: String
        let channelName: String
        let thumbnailUrl: String?
        let durationSeconds: Int
        let addedAt: Int
        let approvalStatus: String?
        let source: String?
        let importedAt: Int?
    }

    /// Encoding a fixed `Encodable` shape of strings and integers never fails (same idiom as
    /// `PlayerRequestBuilder.swift:48`).
    private static func json(_ body: some Encodable) -> Data {
        (try? JSONEncoder().encode(body)) ?? Data()
    }
}
