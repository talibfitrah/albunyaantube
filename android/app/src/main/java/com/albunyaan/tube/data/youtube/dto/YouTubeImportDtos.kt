package com.albunyaan.tube.data.youtube.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

// ── Shared thumbnail types ─────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class YtThumbnailEntry(
    @Json(name = "url") val url: String,
)

/**
 * Thumbnail map returned by the YouTube Data API v3.
 * Only [default] and [medium] are captured; higher resolutions are ignored.
 * Callers should prefer [medium] and fall back to [default].
 */
@JsonClass(generateAdapter = true)
data class YtThumbnails(
    @Json(name = "default") val default: YtThumbnailEntry? = null,
    @Json(name = "medium")  val medium:  YtThumbnailEntry? = null,
) {
    /** Best available URL: medium first, then default, then null. */
    fun bestUrl(): String? = medium?.url ?: default?.url
}

// ── Subscriptions ──────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class YtResourceId(
    @Json(name = "channelId") val channelId: String,
)

@JsonClass(generateAdapter = true)
data class SubscriptionSnippet(
    @Json(name = "title")      val title:      String,
    @Json(name = "resourceId") val resourceId: YtResourceId,
    @Json(name = "thumbnails") val thumbnails: YtThumbnails,
)

@JsonClass(generateAdapter = true)
data class SubscriptionItem(
    @Json(name = "snippet") val snippet: SubscriptionSnippet,
)

@JsonClass(generateAdapter = true)
data class SubscriptionListResponse(
    @Json(name = "items")         val items:         List<SubscriptionItem>,
    @Json(name = "nextPageToken") val nextPageToken: String? = null,
)

// ── Playlists ──────────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class PlaylistSnippet(
    @Json(name = "title")      val title:      String,
    @Json(name = "thumbnails") val thumbnails: YtThumbnails,
)

@JsonClass(generateAdapter = true)
data class PlaylistItem(
    @Json(name = "id")      val id:      String,
    @Json(name = "snippet") val snippet: PlaylistSnippet,
)

@JsonClass(generateAdapter = true)
data class PlaylistListResponse(
    @Json(name = "items")         val items:         List<PlaylistItem>,
    @Json(name = "nextPageToken") val nextPageToken: String? = null,
)

// ── Liked videos ───────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class LikedVideoSnippet(
    @Json(name = "title")      val title:      String,
    @Json(name = "channelId")  val channelId:  String,
    @Json(name = "thumbnails") val thumbnails: YtThumbnails,
)

@JsonClass(generateAdapter = true)
data class LikedVideoItem(
    @Json(name = "id")      val id:      String,
    @Json(name = "snippet") val snippet: LikedVideoSnippet,
)

@JsonClass(generateAdapter = true)
data class LikedVideosResponse(
    @Json(name = "items")         val items:         List<LikedVideoItem>,
    @Json(name = "nextPageToken") val nextPageToken: String? = null,
)
