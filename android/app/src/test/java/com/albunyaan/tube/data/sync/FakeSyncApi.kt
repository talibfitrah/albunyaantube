package com.albunyaan.tube.data.sync

import com.albunyaan.tube.data.sync.dto.*
import retrofit2.Response

/**
 * Plan D test fake for SyncApi. Default behaviour: empty pull, no-op pushes.
 * Override the lambdas to customize per-test behaviour.
 */
class FakeSyncApi : SyncApi {

    var pullResponse: () -> Response<SyncResponseDto> = { Response.success(emptyResponse()) }
    var putSubResponse: (String, PutSubscriptionRequest) -> Response<SubscriptionSyncDto> = { id, req ->
        Response.success(SubscriptionSyncDto(id, false, 0L, req.channelUrl, req.name, req.avatarUrl, req.subscribedAt))
    }
    var deleteSubResponse: (String) -> Response<SubscriptionSyncDto> = { id ->
        Response.success(SubscriptionSyncDto(id, true, 0L, "", "", null, 0L))
    }
    var putPlaylistResponse: (String, PutPlaylistRequest) -> Response<PlaylistSyncDto> = { id, req ->
        Response.success(PlaylistSyncDto(id, false, 0L, req.playlistUrl, req.name, req.thumbnailUrl, req.uploaderName, req.savedAt))
    }
    var deletePlaylistResponse: (String) -> Response<PlaylistSyncDto> = { id ->
        Response.success(PlaylistSyncDto(id, true, 0L, "", "", null, null, 0L))
    }
    var putFavoriteResponse: (String, PutFavoriteRequest) -> Response<FavoriteSyncDto> = { id, req ->
        Response.success(FavoriteSyncDto(id, false, 0L, req.title, req.channelName, req.thumbnailUrl, req.durationSeconds, req.addedAt))
    }
    var deleteFavoriteResponse: (String) -> Response<FavoriteSyncDto> = { id ->
        Response.success(FavoriteSyncDto(id, true, 0L, "", "", null, 0, 0L))
    }

    override suspend fun pull(subs: Long, playlists: Long, favorites: Long): Response<SyncResponseDto> = pullResponse()
    override suspend fun putSubscription(id: String, body: PutSubscriptionRequest) = putSubResponse(id, body)
    override suspend fun deleteSubscription(id: String) = deleteSubResponse(id)
    override suspend fun putPlaylist(id: String, body: PutPlaylistRequest) = putPlaylistResponse(id, body)
    override suspend fun deletePlaylist(id: String) = deletePlaylistResponse(id)
    override suspend fun putFavorite(id: String, body: PutFavoriteRequest) = putFavoriteResponse(id, body)
    override suspend fun deleteFavorite(id: String) = deleteFavoriteResponse(id)

    private fun emptyResponse() = SyncResponseDto(
        SyncPageDto(emptyList(), null),
        SyncPageDto(emptyList(), null),
        SyncPageDto(emptyList(), null),
    )
}
