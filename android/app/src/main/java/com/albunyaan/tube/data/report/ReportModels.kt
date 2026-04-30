package com.albunyaan.tube.data.report

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ReportRequest(
    val targetType: String,
    val targetId: String,
    val reasons: List<String>,
    val otherDescription: String?
)

enum class ReportTargetType { VIDEO, CHANNEL, PLAYLIST }

enum class ReportReason {
    MUSIC, NUDITY, BAD_LANGUAGE, FLIRTING, ROMANCE,
    AWRAH, SHIRK, BIDAH, VIOLENCE, MISINFORMATION, OTHER
}
