package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
interface SavedPlaylistDao {

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND deleted = 0 ORDER BY savedAt DESC")
    fun observeAll(uid: String): Flow<List<SavedPlaylist>>

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND deleted = 0 ORDER BY savedAt DESC")
    suspend fun getAll(uid: String): List<SavedPlaylist>

    @Query("SELECT * FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0")
    suspend fun getById(uid: String, id: String): SavedPlaylist?

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0)")
    fun observeIsSaved(uid: String, id: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM saved_playlists WHERE playlistId = :id AND user_id = :uid AND deleted = 0)")
    suspend fun isSaved(uid: String, id: String): Boolean

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(playlist: SavedPlaylist)

    @Query("UPDATE saved_playlists SET deleted = 1, dirty = 1 WHERE playlistId = :id AND user_id = :uid")
    suspend fun softDelete(uid: String, id: String)

    @Query("UPDATE saved_playlists SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM saved_playlists WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<SavedPlaylist>

    @Query("UPDATE saved_playlists SET updated_at = :ts, dirty = 0 WHERE playlistId = :id AND user_id = :uid")
    suspend fun clearDirty(uid: String, id: String, ts: Long)

    @Query("DELETE FROM saved_playlists WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)

    @Query("UPDATE saved_playlists SET deleted = 1, dirty = 0, updated_at = :ts WHERE playlistId = :id AND user_id = :uid")
    suspend fun applyTombstone(uid: String, id: String, ts: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromServer(playlist: SavedPlaylist)
}
