package com.albunyaan.tube.data.sync

import com.albunyaan.tube.data.sync.dto.*
import retrofit2.Response
import retrofit2.http.*

interface SyncApi {

    @GET("api/account/sync")
    suspend fun pull(
        @Query("subs")          subs: Long = 0L,
        @Query("playlists")     playlists: Long = 0L,
        @Query("favorites")     favorites: Long = 0L,
        // Compound-cursor tiebreakers (cubic R3/R4 P1) — the previous page's
        // last docId, sent so the backend uses startAfter(ts, id) and rows
        // tied on the same millisecond don't drop on page boundaries.
        @Query("subs_id")       subsId: String? = null,
        @Query("playlists_id")  playlistsId: String? = null,
        @Query("favorites_id")  favoritesId: String? = null,
    ): Response<SyncResponseDto>

    // Subscriptions
    @PUT("api/account/subscriptions/{id}")
    suspend fun putSubscription(
        @Path("id") id: String,
        @Body body: PutSubscriptionRequest,
    ): Response<SubscriptionSyncDto>

    @DELETE("api/account/subscriptions/{id}")
    suspend fun deleteSubscription(@Path("id") id: String): Response<SubscriptionSyncDto>

    // Playlists
    @PUT("api/account/playlists/{id}")
    suspend fun putPlaylist(
        @Path("id") id: String,
        @Body body: PutPlaylistRequest,
    ): Response<PlaylistSyncDto>

    @DELETE("api/account/playlists/{id}")
    suspend fun deletePlaylist(@Path("id") id: String): Response<PlaylistSyncDto>

    // Favorites
    @PUT("api/account/favorites/{id}")
    suspend fun putFavorite(
        @Path("id") id: String,
        @Body body: PutFavoriteRequest,
    ): Response<FavoriteSyncDto>

    @DELETE("api/account/favorites/{id}")
    suspend fun deleteFavorite(@Path("id") id: String): Response<FavoriteSyncDto>
}
