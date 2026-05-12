package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface SyncStateDao {

    @Query("SELECT last_cursor FROM sync_state WHERE entityType = :type AND user_id = :uid")
    suspend fun cursorFor(uid: String, type: String): Long?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(row: SyncStateEntity)

    @Query("DELETE FROM sync_state WHERE user_id = :uid")
    suspend fun clearForUid(uid: String)
}
