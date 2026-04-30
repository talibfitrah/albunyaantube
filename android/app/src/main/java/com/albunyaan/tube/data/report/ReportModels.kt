package com.albunyaan.tube.data.report

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class ReportRequest(
    val targetType: String,
    val targetId: String,
    val reasons: List<String>,
    val otherDescription: String?,
    // Optional parent context. parentType is CHANNEL or PLAYLIST when the
    // user reported an item from inside a channel- or playlist-detail
    // screen; parentId is the parent's YouTube ID. contentSubType narrows
    // a VIDEO target into SHORT / LIVESTREAM / POST so the resolve flow
    // adds the exclusion to the correct bucket on the channel.
    val parentType: String? = null,
    val parentId: String? = null,
    val contentSubType: String? = null,
)

enum class ReportTargetType { VIDEO, CHANNEL, PLAYLIST }

/** Sub-type for VIDEO reports — distinguishes shorts/livestreams from a regular video. */
enum class ReportContentSubType { SHORT, LIVESTREAM, POST }

enum class ReportReason {
    MUSIC, NUDITY, BAD_LANGUAGE, FLIRTING, ROMANCE,
    AWRAH, SHIRK, BIDAH, VIOLENCE, MISINFORMATION, OTHER
}
