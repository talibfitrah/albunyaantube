package com.albunyaan.tube.data.sync.dto

import com.squareup.moshi.JsonClass

@JsonClass(generateAdapter = true)
data class SyncResponseDto(
    val subscriptions: SyncPageDto<SubscriptionSyncDto>,
    val playlists:     SyncPageDto<PlaylistSyncDto>,
    val favorites:     SyncPageDto<FavoriteSyncDto>,
)

@JsonClass(generateAdapter = true)
data class SyncPageDto<T>(
    val items: List<T>,
    val nextCursor: Long? = null,
    /** Compound-cursor tiebreaker — last row's docId (cubic R3/R4 P1). */
    val nextCursorId: String? = null,
)

@JsonClass(generateAdapter = true)
data class SubscriptionSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PlaylistSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long,
)

@JsonClass(generateAdapter = true)
data class FavoriteSyncDto(
    val entityId: String,
    val deleted: Boolean,
    val updatedAt: Long,
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long,
)

// ── Push bodies ────────────────────────────────────────────────────────

@JsonClass(generateAdapter = true)
data class PutSubscriptionRequest(
    val channelUrl: String,
    val name: String,
    val avatarUrl: String?,
    val subscribedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PutPlaylistRequest(
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long,
)

@JsonClass(generateAdapter = true)
data class PutFavoriteRequest(
    val title: String,
    val channelName: String,
    val thumbnailUrl: String?,
    val durationSeconds: Int,
    val addedAt: Long,
)
