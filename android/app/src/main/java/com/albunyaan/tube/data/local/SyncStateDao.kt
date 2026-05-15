package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {

    @Query("SELECT last_cursor FROM sync_state WHERE entityType = :type AND user_id = :uid")
    suspend fun cursorFor(uid: String, type: String): Long?

    // SYNC-CURSOR-PERSIST-01 — paired with cursorFor; reads the docId
    // tiebreaker so SyncManager can rebuild the (cursor_ts, docId) compound
    // cursor after a process restart.
    @Query("SELECT last_doc_id FROM sync_state WHERE entityType = :type AND user_id = :uid")
    suspend fun cursorIdFor(uid: String, type: String): String?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE user_id = :uid")
    suspend fun clearForUid(uid: String)
}
