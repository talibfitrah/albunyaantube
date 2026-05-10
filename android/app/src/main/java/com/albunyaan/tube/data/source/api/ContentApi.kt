package com.albunyaan.tube.data.source.api

import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.albunyaan.tube.data.model.api.models.PageInfo
import com.squareup.moshi.JsonClass
import retrofit2.Response
import retrofit2.http.GET
import retrofit2.http.HEAD
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Retrofit API interface using generated OpenAPI DTOs
 */
interface ContentApi {
    @GET("api/v1/content")
    suspend fun fetchContent(
        @Query("type") type: String?,  // Nullable to support ALL content types
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
        @Query("category") category: String?,
        @Query("length") length: String?,
        @Query("date") date: String?,
        @Query("sort") sort: String?,
        @Query("q") query: String? = null
    ): CursorPage

    @GET("api/v1/categories")
    suspend fun fetchCategories(): List<CategoryResponse>

    @GET("api/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String?,
        @Query("limit") limit: Int
    ): List<ContentItemDto>

    @GET("api/v1/home")
    suspend fun fetchHomeFeed(
        @Query("cursor") cursor: String?,
        @Query("categoryLimit") categoryLimit: Int,
        @Query("contentLimit") contentLimit: Int,
        @Query("category") category: String? = null
    ): HomeFeedResponse

    @HEAD("api/v1/channels/{id}")
    suspend fun checkChannelAvailable(@Path("id") id: String): Response<Unit>

    @HEAD("api/v1/playlists/{id}")
    suspend fun checkPlaylistAvailable(@Path("id") id: String): Response<Unit>

    @HEAD("api/v1/videos/{id}")
    suspend fun checkVideoAvailable(@Path("id") id: String): Response<Unit>
}

/**
 * Response wrapper for cursor-based pagination with ContentItemDto items
 * Adapts the generated CursorPageDto to work with our specific use case
 */
@JsonClass(generateAdapter = true)
data class CursorPage(
    val data: List<ContentItemDto>,
    val pageInfo: PageInfo
)
