import Foundation
import Testing
@testable import FitrahTube

/// Phase 4 Task 21, ruling C9's pin: `SyncCodec` is the ONE place the wire name `deleted` meets
/// the SwiftData property `isRemoved` (a `@Model` property literally named `deleted` is silently
/// reverted by the next save -- `FavoriteVideo.swift:12-18`). If a second bridge ever appears, or
/// this one starts emitting the Swift property names, these tests are what fails.
///
/// Row-shape asserts are made in memory: `apply` and `body(for:)` are pure functions over a
/// `@Model` object's properties, no container, no save.
@Suite(.perTest)
struct SyncCodecTests {

    // MARK: - Fixtures (approved ids only)

    private static let channelId = "UCmMcOjsVehVlEOteyrhjI2Q"
    private static let playlistId = "PL6SWGxz3wzpSrxgiBj2PCuEf-MenhYTCc"
    private static let videoId = "xc7keR2piUM"
    private static let updatedAtMillis = 1_700_000_000_500
    private static let stampedMillis = 1_690_000_000_000
    private static let importedMillis = 1_695_000_000_000

    private static let subscription = SubscriptionSyncDTO(
        entityId: channelId, deleted: false, updatedAt: updatedAtMillis,
        channelUrl: "https://www.youtube.com/channel/\(channelId)", name: "Alafasy",
        avatarUrl: "https://example.test/avatar.jpg", subscribedAt: stampedMillis,
        approvalStatus: "AWAITING", source: "USER_IMPORT", importedAt: importedMillis)

    private static let playlist = PlaylistSyncDTO(
        entityId: playlistId, deleted: false, updatedAt: updatedAtMillis,
        playlistUrl: "https://www.youtube.com/playlist?list=\(playlistId)", name: "Juz Amma",
        thumbnailUrl: "https://example.test/thumb.jpg", uploaderName: "Alafasy",
        savedAt: stampedMillis, approvalStatus: "AWAITING", source: "USER_IMPORT",
        importedAt: importedMillis)

    private static let favorite = FavoriteSyncDTO(
        entityId: videoId, deleted: false, updatedAt: updatedAtMillis,
        title: "Lecture", channelName: "Alafasy", thumbnailUrl: "https://example.test/thumb.jpg",
        durationSeconds: 754, addedAt: stampedMillis, approvalStatus: "AWAITING",
        source: "USER_IMPORT", importedAt: importedMillis)

    private static func keys(of value: some Encodable) throws -> Set<String> {
        let object = try JSONSerialization.jsonObject(with: JSONEncoder().encode(value))
        return Set(try #require(object as? [String: Any]).keys)
    }

    private static func decoded<T: Decodable>(_ json: String) throws -> T {
        try JSONDecoder().decode(T.self, from: Data(json.utf8))
    }

    // MARK: - The `deleted` <-> `isRemoved` bridge

    /// The wire name goes out verbatim and the Swift name never leaks.
    @Test func everyDTOEncodesTheWireNameDeletedAndNeverIsRemoved() throws {
        for keys in [try Self.keys(of: Self.subscription), try Self.keys(of: Self.playlist),
                     try Self.keys(of: Self.favorite)] {
            #expect(keys.contains("deleted"))
            #expect(!keys.contains("isRemoved"))
        }
    }

    /// The other half of the bridge: a server `"deleted": true` lands on `row.isRemoved`.
    @Test func aDecodedTombstoneAppliesAsIsRemovedOnEveryRowType() throws {
        let channel = SubscribedChannel(channelId: Self.channelId, title: "before", avatarUrl: nil)
        let subscription: SubscriptionSyncDTO = try Self.decoded("""
            {"entityId":"\(Self.channelId)","deleted":true,"updatedAt":\(Self.updatedAtMillis),
             "channelUrl":"https://www.youtube.com/channel/\(Self.channelId)","name":"Alafasy",
             "avatarUrl":null,"subscribedAt":\(Self.stampedMillis)}
            """)
        SyncCodec.apply(subscription, to: channel)
        #expect(channel.isRemoved)

        let saved = SavedPlaylist(playlistId: Self.playlistId, title: "before", thumbnailUrl: nil, itemCount: 3)
        let playlist: PlaylistSyncDTO = try Self.decoded("""
            {"entityId":"\(Self.playlistId)","deleted":true,"updatedAt":\(Self.updatedAtMillis),
             "playlistUrl":"https://www.youtube.com/playlist?list=\(Self.playlistId)","name":"Juz Amma",
             "thumbnailUrl":null,"uploaderName":null,"savedAt":\(Self.stampedMillis)}
            """)
        SyncCodec.apply(playlist, to: saved)
        #expect(saved.isRemoved)

        let video = FavoriteVideo(videoId: Self.videoId, title: "before", channelName: "before",
                                  thumbnailUrl: nil, durationSeconds: 1)
        let favorite: FavoriteSyncDTO = try Self.decoded("""
            {"entityId":"\(Self.videoId)","deleted":true,"updatedAt":\(Self.updatedAtMillis),
             "title":"Lecture","channelName":"Alafasy","thumbnailUrl":null,
             "durationSeconds":754,"addedAt":\(Self.stampedMillis)}
            """)
        SyncCodec.apply(favorite, to: video)
        #expect(video.isRemoved)
    }

    @Test func everyDTORoundTripsThroughJSON() throws {
        let encoder = JSONEncoder()
        let decoder = JSONDecoder()
        #expect(try decoder.decode(SubscriptionSyncDTO.self, from: encoder.encode(Self.subscription)) == Self.subscription)
        #expect(try decoder.decode(PlaylistSyncDTO.self, from: encoder.encode(Self.playlist)) == Self.playlist)
        #expect(try decoder.decode(FavoriteSyncDTO.self, from: encoder.encode(Self.favorite)) == Self.favorite)
    }

    // MARK: - apply()

    /// `SyncManager.kt:379,397,415` -- a null server `approvalStatus` means APPROVED, never nil and
    /// never "keep whatever was there" (an imported AWAITING row the moderator approved would stay
    /// hidden forever).
    @Test func aNullServerApprovalStatusAppliesAsApproved() {
        var subscription = Self.subscription
        subscription.approvalStatus = nil
        let channel = SubscribedChannel(channelId: Self.channelId, title: "x", avatarUrl: nil,
                                        approvalStatus: "AWAITING")
        SyncCodec.apply(subscription, to: channel)
        #expect(channel.approvalStatus == "APPROVED")

        var playlist = Self.playlist
        playlist.approvalStatus = nil
        let saved = SavedPlaylist(playlistId: Self.playlistId, title: "x", thumbnailUrl: nil,
                                  itemCount: 0, approvalStatus: "AWAITING")
        SyncCodec.apply(playlist, to: saved)
        #expect(saved.approvalStatus == "APPROVED")

        var favorite = Self.favorite
        favorite.approvalStatus = nil
        let video = FavoriteVideo(videoId: Self.videoId, title: "x", channelName: "x",
                                  thumbnailUrl: nil, durationSeconds: 0, approvalStatus: "AWAITING")
        SyncCodec.apply(favorite, to: video)
        #expect(video.approvalStatus == "APPROVED")
    }

    /// Room `name`/`subscribedAt` land on Swift `title`/`followedAt`, millis become `Date`, and the
    /// row comes out clean -- the server copy is by definition already synced.
    @Test func applyingASubscriptionFillsEveryColumnAndClearsDirty() {
        let channel = SubscribedChannel(channelId: Self.channelId, title: "before", avatarUrl: nil, dirty: true)
        SyncCodec.apply(Self.subscription, to: channel)
        #expect(channel.channelId == Self.channelId)          // apply never rewrites identity
        #expect(channel.channelUrl == Self.subscription.channelUrl)
        #expect(channel.title == "Alafasy")
        #expect(channel.avatarUrl == "https://example.test/avatar.jpg")
        #expect(channel.followedAt == Date(timeIntervalSince1970: 1_690_000_000))
        #expect(channel.updatedAt == Date(timeIntervalSince1970: 1_700_000_000.5))
        #expect(channel.isRemoved == false)
        #expect(channel.dirty == false)
        #expect(channel.approvalStatus == "AWAITING")
        #expect(channel.source == "USER_IMPORT")
        #expect(channel.importedAt == Date(timeIntervalSince1970: 1_695_000_000))
    }

    @Test func applyingAPlaylistFillsEveryColumnAndLeavesItemCountAlone() {
        let saved = SavedPlaylist(playlistId: Self.playlistId, title: "before", thumbnailUrl: nil,
                                  itemCount: 12, dirty: true)
        SyncCodec.apply(Self.playlist, to: saved)
        #expect(saved.playlistId == Self.playlistId)
        #expect(saved.playlistUrl == Self.playlist.playlistUrl)
        #expect(saved.title == "Juz Amma")
        #expect(saved.thumbnailUrl == "https://example.test/thumb.jpg")
        #expect(saved.uploaderName == "Alafasy")
        #expect(saved.addedAt == Date(timeIntervalSince1970: 1_690_000_000))   // Room's savedAt
        #expect(saved.updatedAt == Date(timeIntervalSince1970: 1_700_000_000.5))
        #expect(saved.isRemoved == false)
        #expect(saved.dirty == false)
        #expect(saved.approvalStatus == "AWAITING")
        #expect(saved.source == "USER_IMPORT")
        #expect(saved.importedAt == Date(timeIntervalSince1970: 1_695_000_000))
        #expect(saved.itemCount == 12)   // no sync DTO carries it; the count chip renders it
    }

    @Test func applyingAFavoriteFillsEveryColumnAndClearsDirty() {
        let video = FavoriteVideo(videoId: Self.videoId, title: "before", channelName: "before",
                                  thumbnailUrl: nil, durationSeconds: 1, dirty: true)
        SyncCodec.apply(Self.favorite, to: video)
        #expect(video.videoId == Self.videoId)
        #expect(video.title == "Lecture")
        #expect(video.channelName == "Alafasy")
        #expect(video.thumbnailUrl == "https://example.test/thumb.jpg")
        #expect(video.durationSeconds == 754)
        #expect(video.addedAt == Date(timeIntervalSince1970: 1_690_000_000))
        #expect(video.updatedAt == Date(timeIntervalSince1970: 1_700_000_000.5))
        #expect(video.isRemoved == false)
        #expect(video.dirty == false)
        #expect(video.approvalStatus == "AWAITING")
        #expect(video.source == "USER_IMPORT")
        #expect(video.importedAt == Date(timeIntervalSince1970: 1_695_000_000))
    }

    // MARK: - body()

    /// The push body speaks Room's field names, not Swift's: `name`, never `title`; `subscribedAt`,
    /// never `followedAt`. Every optional is populated here so the whole key set is asserted.
    @Test func theSubscriptionBodyUsesRoomFieldNames() throws {
        let channel = SubscribedChannel(
            channelId: Self.channelId, title: "Alafasy", avatarUrl: "https://example.test/avatar.jpg",
            followedAt: Date(timeIntervalSince1970: 1_690_000_000),
            channelUrl: "https://www.youtube.com/channel/\(Self.channelId)",
            approvalStatus: "AWAITING", source: "USER_IMPORT",
            importedAt: Date(timeIntervalSince1970: 1_695_000_000))
        let body = try #require(try JSONSerialization.jsonObject(with: SyncCodec.body(for: channel)) as? [String: Any])
        #expect(Set(body.keys) == ["channelUrl", "name", "avatarUrl", "subscribedAt",
                                   "approvalStatus", "source", "importedAt"])
        #expect(body["name"] as? String == "Alafasy")
        #expect(body["subscribedAt"] as? Int == 1_690_000_000_000)
        #expect(body["importedAt"] as? Int == 1_695_000_000_000)
        #expect(body["approvalStatus"] as? String == "AWAITING")
    }

    @Test func thePlaylistBodyUsesSavedAtNotAddedAt() throws {
        let saved = SavedPlaylist(
            playlistId: Self.playlistId, title: "Juz Amma", thumbnailUrl: nil, itemCount: 12,
            addedAt: Date(timeIntervalSince1970: 1_690_000_000),
            playlistUrl: "https://www.youtube.com/playlist?list=\(Self.playlistId)",
            uploaderName: "Alafasy", approvalStatus: "APPROVED", source: "USER_IMPORT",
            importedAt: Date(timeIntervalSince1970: 1_695_000_000))
        let body = try #require(try JSONSerialization.jsonObject(with: SyncCodec.body(for: saved)) as? [String: Any])
        #expect(Set(body.keys) == ["playlistUrl", "name", "uploaderName", "savedAt",
                                   "approvalStatus", "source", "importedAt"])
        #expect(body["savedAt"] as? Int == 1_690_000_000_000)
        #expect(body["name"] as? String == "Juz Amma")
        #expect(!body.keys.contains("itemCount"))   // not on the wire at all
        #expect(!body.keys.contains("thumbnailUrl"))  // nil is OMITTED, never sent as JSON null
    }

    @Test func theFavoriteBodyUsesRoomFieldNames() throws {
        let video = FavoriteVideo(
            videoId: Self.videoId, title: "Lecture", channelName: "Alafasy",
            thumbnailUrl: "https://example.test/thumb.jpg", durationSeconds: 754,
            addedAt: Date(timeIntervalSince1970: 1_690_000_000), approvalStatus: "APPROVED",
            source: "USER_IMPORT", importedAt: Date(timeIntervalSince1970: 1_695_000_000))
        let body = try #require(try JSONSerialization.jsonObject(with: SyncCodec.body(for: video)) as? [String: Any])
        #expect(Set(body.keys) == ["title", "channelName", "thumbnailUrl", "durationSeconds",
                                   "addedAt", "approvalStatus", "source", "importedAt"])
        #expect(body["durationSeconds"] as? Int == 754)
        #expect(body["addedAt"] as? Int == 1_690_000_000_000)
        #expect(!body.keys.contains("deleted"))   // a tombstone is a DELETE, never a PUT with a flag
    }

    // MARK: - The response envelope and the entity type

    @Test func aSyncResponseDecodesItsThreePagesAndTheCursorPair() throws {
        let response: SyncResponse = try Self.decoded("""
            {"subscriptions":{"items":[{"entityId":"\(Self.channelId)","deleted":false,
              "updatedAt":\(Self.updatedAtMillis),"channelUrl":"https://www.youtube.com/channel/\(Self.channelId)",
              "name":"Alafasy","avatarUrl":null,"subscribedAt":\(Self.stampedMillis)}],
              "nextCursor":\(Self.updatedAtMillis),"nextCursorId":"\(Self.channelId)"},
             "playlists":{"items":[]},
             "favorites":{"items":[],"nextCursor":null,"nextCursorId":null}}
            """)
        #expect(response.subscriptions.items.count == 1)
        #expect(response.subscriptions.items.first?.name == "Alafasy")
        #expect(response.subscriptions.nextCursor == Self.updatedAtMillis)
        #expect(response.subscriptions.nextCursorId == Self.channelId)
        #expect(response.playlists.items.isEmpty)
        #expect(response.playlists.nextCursor == nil)     // absent, not null
        #expect(response.favorites.nextCursorId == nil)
    }

    /// `SyncController.java:41-64` takes `subs`, not `subscriptions` -- and `SyncState.entityType`
    /// is the rawValue, which is NOT the query name. Confusing the two silently syncs nothing.
    @Test func theSubscriptionsQueryNameIsSubsWhileItsPathStaysTheRawValue() {
        #expect(SyncEntityType.subscriptions.rawValue == "subscriptions")
        #expect(SyncEntityType.subscriptions.path == "subscriptions")
        #expect(SyncEntityType.subscriptions.queryName == "subs")
        for type in [SyncEntityType.playlists, .favorites] {
            #expect(type.path == type.rawValue)
            #expect(type.queryName == type.rawValue)
        }
        #expect(SyncEntityType.allCases.map(\.rawValue) == ["subscriptions", "playlists", "favorites"])
    }
}
