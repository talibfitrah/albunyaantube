package com.albunyaan.tube.data.source.api

import com.squareup.moshi.JsonClass
import retrofit2.http.GET
import retrofit2.http.Query

interface ContentApi {
    @GET("api/v1/content")
    suspend fun fetchContent(
        @Query("type") type: String,
        @Query("cursor") cursor: String?,
        @Query("limit") limit: Int,
        @Query("category") category: String?,
        @Query("length") length: String?,
        @Query("date") date: String?,
        @Query("sort") sort: String?
    ): CursorPageDto

    @GET("api/v1/categories")
    suspend fun fetchCategories(): List<CategoryDto>

    @GET("api/v1/search")
    suspend fun search(
        @Query("q") query: String,
        @Query("type") type: String?,
        @Query("limit") limit: Int
    ): SearchResponseDto
}

@JsonClass(generateAdapter = true)
data class CursorPageDto(
    val data: List<ContentDto>,
    val pageInfo: PageInfoDto
)

@JsonClass(generateAdapter = true)
data class PageInfoDto(
    val nextCursor: String?
)

@JsonClass(generateAdapter = true)
data class ContentDto(
    val id: String,
    val title: String? = null,
    val name: String? = null,
    val category: String? = null,
    val durationMinutes: Int? = null,
    val uploadedDaysAgo: Int? = null,
    val description: String? = null,
    val subscribers: Int? = null,
    val itemCount: Int? = null,
    val type: String? = null
)

@JsonClass(generateAdapter = true)
data class CategoryDto(
    val id: String,
    val name: String,
    val slug: String,
    val parentId: String? = null
)

@JsonClass(generateAdapter = true)
data class SearchResponseDto(
    val results: List<ContentDto>,
    val total: Int? = null
)
