package com.albunyaan.tube.data.search.dto

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SearchHitDto(
    @Json(name = "youtubeId")    val youtubeId: String,
    @Json(name = "name")         val name: String,
    @Json(name = "url")          val url: String,
    @Json(name = "thumbnailUrl") val thumbnailUrl: String? = null,
    @Json(name = "secondary")    val secondary: String? = null,
    @Json(name = "alreadyKnown") val alreadyKnown: Boolean = false,
    @Json(name = "knownStatus")  val knownStatus: String? = null
)

@JsonClass(generateAdapter = true)
data class YouTubeSearchResponseDto(
    @Json(name = "items")         val items: List<SearchHitDto>,
    @Json(name = "nextPageToken") val nextPageToken: String? = null
)

enum class YouTubeContentTypeDto { CHANNEL, PLAYLIST, VIDEO }
