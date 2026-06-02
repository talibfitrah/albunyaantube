package com.albunyaan.tube.data.youtube

/** The kind of YouTube content this import candidate represents. */
enum class CandidateType { CHANNEL, PLAYLIST, VIDEO }

/**
 * A single YouTube item surfaced during the import flow — either a subscribed
 * channel, a user playlist, or a liked video.
 *
 * @param type         What kind of content this is.
 * @param youtubeId    The YouTube ID for this item (channelId, playlistId, or videoId).
 * @param title        Human-readable title.
 * @param thumbnailUrl Best available thumbnail URL; null if unavailable.
 * @param channelId    The owning channel ID. Non-null for [CandidateType.VIDEO];
 *                     null for [CandidateType.CHANNEL] and [CandidateType.PLAYLIST].
 */
data class ImportCandidate(
    val type:         CandidateType,
    val youtubeId:    String,
    val title:        String,
    val thumbnailUrl: String?,
    val channelId:    String?,
)

/**
 * Result of a [YouTubeImportRemoteSource.fetchAll] call.
 *
 * @param candidates   Every item successfully fetched across all types.
 * @param failedTypes  Any [CandidateType] whose fetch threw an exception.
 *                     The remaining types' candidates are still present in [candidates].
 */
data class ImportFetchResult(
    val candidates:  List<ImportCandidate>,
    val failedTypes: Set<CandidateType>,
)
