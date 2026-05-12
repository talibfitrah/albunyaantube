package com.albunyaan.tube.data.approvals.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class PendingApprovalDto(
    val id: String,
    val type: String,                    // "channel" | "playlist" | "video"
    val entityId: String,                // youtubeId
    val title: String?,
    val category: String?,
    val submittedAt: Long?,
    val submittedBy: String?,            // uid
    val submittedByDisplayName: String?,
    val submittedByEmail: String?,
    val status: String,                  // "PENDING" | "APPROVED" | "REJECTED" | "REQUEST_CHANGES"
    val rejectionReason: String? = null,
    val reviewNotes: String? = null,
)

@JsonClass(generateAdapter = true)
data class CursorPageDto<T>(
    val items: List<T>,
    val nextCursor: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitChannelRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitPlaylistRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val name: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubmitVideoRequest(
    val youtubeId: String,
    val categoryIds: List<String>,
    val name: String? = null,
)
