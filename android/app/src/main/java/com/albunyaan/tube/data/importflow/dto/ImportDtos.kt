package com.albunyaan.tube.data.importflow.dto

import com.albunyaan.tube.data.model.api.models.ContentItemDto
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

/**
 * B8: DTOs for the backend import-resolve endpoint.
 * POST /api/account/import/resolve
 */

/** One candidate item sent to the backend for resolution. */
@JsonClass(generateAdapter = true)
data class ImportItemDto(
    /** "CHANNEL", "PLAYLIST", or "VIDEO" */
    @Json(name = "type") val type: String,
    @Json(name = "youtubeId") val youtubeId: String,
    @Json(name = "title") val title: String,
    @Json(name = "thumbnailUrl") val thumbnailUrl: String?,
    /** Channel's YouTube channel ID; present for VIDEO items, null for CHANNEL/PLAYLIST. */
    @Json(name = "channelId") val channelId: String?,
)

/** Request body for POST /api/account/import/resolve. */
@JsonClass(generateAdapter = true)
data class ImportResolveRequestDto(
    @Json(name = "items") val items: List<ImportItemDto>,
)

/**
 * One result entry in the resolve response.
 *
 * [content] is non-null ONLY when [disposition] == "APPROVED"; it is the
 * same [ContentItemDto] the app already deserialises from /api/v1 content
 * endpoints, so it can flow directly into the Me list.
 */
@JsonClass(generateAdapter = true)
data class ImportResultDto(
    @Json(name = "youtubeId") val youtubeId: String,
    /** "CHANNEL", "PLAYLIST", or "VIDEO" */
    @Json(name = "type") val type: String,
    /** "APPROVED", "PENDING", "REJECTED", or "ERROR" */
    @Json(name = "disposition") val disposition: String,
    /** Non-null only for APPROVED items. Reuses the existing content DTO. */
    @Json(name = "content") val content: ContentItemDto?,
)

/** Response body from POST /api/account/import/resolve. */
@JsonClass(generateAdapter = true)
data class ImportResolveResponseDto(
    @Json(name = "results") val results: List<ImportResultDto>,
)
