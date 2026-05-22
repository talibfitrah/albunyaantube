package com.albunyaan.tube.data.approvals.dto

import com.squareup.moshi.FromJson
import com.squareup.moshi.Json
import com.squareup.moshi.JsonClass
import com.squareup.moshi.JsonQualifier
import com.squareup.moshi.JsonReader
import com.squareup.moshi.ToJson

@JsonClass(generateAdapter = true)
data class PendingApprovalDto(
    val id: String,
    val type: String,                    // "CHANNEL" | "PLAYLIST" | "VIDEO" (uppercase per backend)
    val entityId: String,                // youtubeId
    val title: String?,
    val category: String?,
    @FirestoreTimestamp val submittedAt: Long?,
    val submittedBy: String?,            // uid
    val submittedByDisplayName: String?,
    val submittedByEmail: String?,
    val status: String,                  // "PENDING" | "APPROVED" | "REJECTED" | "REQUEST_CHANGES"
    val rejectionReason: String? = null,
    val reviewNotes: String? = null,
    val submitterNote: String? = null,
    val thumbnailUrl: String? = null,
    val youtubeId: String? = null,
)

@JsonClass(generateAdapter = true)
data class CursorPageDto<T>(
    @Json(name = "data") val items: List<T>,
    val pageInfo: PageInfo? = null,
)

@JsonClass(generateAdapter = true)
data class PageInfo(
    val nextCursor: String? = null,
    val hasNext: Boolean = false,
    val totalCount: Int? = null,
    val truncated: Boolean? = null,
)

/**
 * Marks Long fields that arrive from the backend as a Firestore Timestamp object
 * (`{"seconds": N, "nanos": N}`) and need to be flattened into epoch milliseconds.
 * Tolerates a plain numeric or null too, in case a future endpoint pre-flattens.
 */
@JsonQualifier
@Retention(AnnotationRetention.RUNTIME)
annotation class FirestoreTimestamp

class FirestoreTimestampAdapter {
    @FromJson
    @FirestoreTimestamp
    fun fromJson(reader: JsonReader): Long? = when (reader.peek()) {
        JsonReader.Token.NULL -> reader.nextNull()
        JsonReader.Token.NUMBER -> reader.nextLong()
        JsonReader.Token.BEGIN_OBJECT -> {
            var seconds = 0L
            var nanos = 0L
            reader.beginObject()
            while (reader.hasNext()) {
                when (reader.nextName()) {
                    "seconds" -> seconds = reader.nextLong()
                    "nanos" -> nanos = reader.nextLong()
                    else -> reader.skipValue()
                }
            }
            reader.endObject()
            seconds * 1000L + nanos / 1_000_000L
        }
        else -> { reader.skipValue(); null }
    }

    @ToJson
    fun toJson(@FirestoreTimestamp value: Long?): Long? = value
}

@JsonClass(generateAdapter = true)
data class SubmitChannelRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val name: String? = null,
    val thumbnailUrl: String? = null,
    val submitterNote: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitPlaylistRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val submitterNote: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitVideoRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val title: String? = null,
    val thumbnailUrl: String? = null,
    val submitterNote: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitterNoteUpdateRequest(
    val submitterNote: String?,
)
