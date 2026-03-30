package com.albunyaan.tube.data.source.api

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * Matches the backend public API CategoryDto JSON shape.
 * The generated CategoryDto is missing displayOrder/localizedNames/icon,
 * and the generated Category uses parentCategoryId instead of parentId.
 */
@JsonClass(generateAdapter = true)
data class CategoryResponse(
    @Json(name = "id") val id: String,
    @Json(name = "name") val name: String,
    @Json(name = "slug") val slug: String? = null,
    @Json(name = "parentId") val parentId: String? = null,
    @Json(name = "displayOrder") val displayOrder: Int = 0,
    @Json(name = "localizedNames") val localizedNames: Map<String, String>? = null,
    @Json(name = "icon") val icon: String? = null
)
