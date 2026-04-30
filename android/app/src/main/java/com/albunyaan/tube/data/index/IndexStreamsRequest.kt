package com.albunyaan.tube.data.index

import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class IndexStreamsRequest(
    @Json(name = "sourceType") val sourceType: String,
    @Json(name = "sourceId")   val sourceId: String,
    @Json(name = "items")      val items: List<StreamIndexItem>
)

@JsonClass(generateAdapter = true)
data class StreamIndexItem(
    @Json(name = "id")           val id: String,
    @Json(name = "name")         val name: String,
    @Json(name = "thumbnailUrl") val thumbnailUrl: String?,
    @Json(name = "uploaderName") val uploaderName: String?,
    @Json(name = "channelId")    val channelId: String?,
    @Json(name = "duration")     val duration: Long?,
    @Json(name = "viewCount")    val viewCount: Long?,
    @Json(name = "streamType")   val streamType: String
)
