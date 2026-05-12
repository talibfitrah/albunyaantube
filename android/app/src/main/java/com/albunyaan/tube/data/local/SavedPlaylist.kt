package com.albunyaan.tube.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "saved_playlists")
data class SavedPlaylist(
    @PrimaryKey val playlistId: String,
    val playlistUrl: String,
    val name: String,
    val thumbnailUrl: String?,
    val uploaderName: String?,
    val savedAt: Long = System.currentTimeMillis(),
    // Plan D — sync metadata
    val user_id: String = "",
    val updated_at: Long = 0L,
    val deleted: Boolean = false,
    val dirty: Boolean = false,
)
