package com.albunyaan.tube.data.source.api

import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.albunyaan.tube.data.model.api.models.PageInfo
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Response wrapper for the /api/v1/home endpoint.
 * Contains paginated category sections, each with their content items.
 */
@JsonClass(generateAdapter = true)
data class HomeFeedResponse(
    @Json(name = "data")
    val data: List<HomeCategorySection>,
    @Json(name = "pageInfo")
    val pageInfo: PageInfo
)

/**
 * A single category section in the home feed.
 * Contains category metadata and a preview list of content items.
 */
@JsonClass(generateAdapter = true)
data class HomeCategorySection(
    @Json(name = "id")
    val id: String,
    @Json(name = "name")
    val name: String,
    @Json(name = "slug")
    val slug: String? = null,
    @Json(name = "localizedNames")
    val localizedNames: Map<String, String>? = null,
    @Json(name = "displayOrder")
    val displayOrder: Int? = null,
    @Json(name = "icon")
    val icon: String? = null,
    @Json(name = "items")
    val items: List<ContentItemDto>,
    @Json(name = "totalContentCount")
    val totalContentCount: Int = 0
)
