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
    // SYNC-CURSOR-PERSIST-01 (Cubic R7 P1) — compound-cursor tiebreaker
    // docId so the (updatedAt, docId) pair survives process death. The
    // first pull after restart now resumes from the same page boundary
    // instead of dropping rows tied to the same millisecond.
    val last_doc_id: String? = null,
    val last_sync_at: Long,
)
