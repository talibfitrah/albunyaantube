package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.Index

/**
 * Many-to-many link between a saved playlist and the videos it contains.
 *
 * Video metadata (title, channel, uploadedAt, thumbnail) lives in
 * [ChannelVideoCache] keyed by videoId — playlist refresh upserts those
 * rows alongside the link rows here so the Me-tab feed can union channel
 * uploads with playlist contents without duplicating metadata storage.
 *
 * Composite PK (playlistId + videoId) lets the same video belong to more
 * than one saved playlist without a row collision.
 */
@Entity(
    tableName = "playlist_video_link",
    primaryKeys = ["playlistId", "videoId"],
    indices = [Index("playlistId"), Index("videoId")],
)
data class PlaylistVideoLink(
    val playlistId: String,
    val videoId: String,
)
