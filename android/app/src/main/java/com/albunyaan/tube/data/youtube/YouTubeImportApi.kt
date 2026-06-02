package com.albunyaan.tube.data.youtube

import com.albunyaan.tube.data.youtube.dto.LikedVideosResponse
import com.albunyaan.tube.data.youtube.dto.PlaylistListResponse
import com.albunyaan.tube.data.youtube.dto.SubscriptionListResponse
import retrofit2.http.GET
import retrofit2.http.Header
import retrofit2.http.Query

/**
 * B6: Retrofit interface for the YouTube Data API v3.
 *
 * Base URL must be https://www.googleapis.com/youtube/v3/ — supplied by the
 * Retrofit instance injected at the call-site (B15). Do NOT hardcode it here.
 *
 * The OAuth access token is supplied per-call as an Authorization: Bearer
 * header so that different scopes / users can be served by the same interface
 * without a global interceptor.
 */
interface YouTubeImportApi {

    /**
     * List the authenticated user's channel subscriptions.
     * Maps to GET subscriptions?part=snippet&mine=true
     */
    @GET("subscriptions")
    suspend fun subscriptions(
        @Header("Authorization")  bearer:     String,
        @Query("part")            part:       String  = "snippet",
        @Query("mine")            mine:       Boolean = true,
        @Query("maxResults")      maxResults: Int     = 50,
        @Query("pageToken")       pageToken:  String? = null,
    ): SubscriptionListResponse

    /**
     * List the authenticated user's playlists.
     * Maps to GET playlists?part=snippet&mine=true
     */
    @GET("playlists")
    suspend fun playlists(
        @Header("Authorization")  bearer:     String,
        @Query("part")            part:       String  = "snippet",
        @Query("mine")            mine:       Boolean = true,
        @Query("maxResults")      maxResults: Int     = 50,
        @Query("pageToken")       pageToken:  String? = null,
    ): PlaylistListResponse

    /**
     * List videos the authenticated user has liked.
     * Maps to GET videos?part=snippet&myRating=like
     */
    @GET("videos")
    suspend fun likedVideos(
        @Header("Authorization")  bearer:     String,
        @Query("part")            part:       String  = "snippet",
        @Query("myRating")        myRating:   String  = "like",
        @Query("maxResults")      maxResults: Int     = 50,
        @Query("pageToken")       pageToken:  String? = null,
    ): LikedVideosResponse
}
