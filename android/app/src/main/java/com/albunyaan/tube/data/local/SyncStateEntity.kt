package com.albunyaan.tube.data.local

import androidx.room.Entity

/**
 * Plan D — per-(uid, entityType) sync cursor + last sync time.
 * `entityType` is one of "subscriptions", "playlists", "favorites".
 */
@Entity(tableName = "sync_state", primaryKeys = ["entityType", "user_id"])
data class SyncStateEntity(
    val entityType: String,
    val user_id: String,
    val last_cursor: Long,
    val last_sync_at: Long,
)
