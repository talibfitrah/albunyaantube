package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {

    @Query("SELECT * FROM saved_playlists ORDER BY savedAt DESC")
    fun observeAll(): Flow<List<SavedPlaylist>>

    @Query("SELECT * FROM saved_playlists ORDER BY savedAt DESC")
    suspend fun getAll(): List<SavedPlaylist>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id)")
    fun observeIsSaved(id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id)")
    suspend fun isSaved(id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: SavedPlaylist)

    @Query("DELETE FROM saved_playlists WHERE playlistId = :id")
    suspend fun delete(id: String)
}
