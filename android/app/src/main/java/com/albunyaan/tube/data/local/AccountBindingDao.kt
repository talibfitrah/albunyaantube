package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface AccountBindingDao {

    @Query("SELECT * FROM account_binding LIMIT 1")
    suspend fun get(): AccountBindingEntity?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(binding: AccountBindingEntity)

    @Query("UPDATE account_binding SET initial_merge_done = 1 WHERE user_id = :uid")
    suspend fun markMergeDone(uid: String)

    @Query("DELETE FROM account_binding")
    suspend fun clear()
}
