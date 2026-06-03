package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

/**
 * Data Access Object for favorite videos.
 *
 * All reads scope to current user (uid) and exclude soft-deleted rows.
 * All writes set sync metadata (user_id, dirty). Hard deletes are reserved
 * for account-switch via [wipeForUid].
 */
@Dao
interface FavoriteVideoDao {

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 ORDER BY addedAt DESC")
    fun getAllFavorites(uid: String): Flow<List<FavoriteVideo>>

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 ORDER BY addedAt DESC")
    suspend fun getAll(uid: String): List<FavoriteVideo>

    // ── Feed-composition queries (approval_status filtered) ───────────────────
    // These are the only queries that may filter by approval_status. The
    // unfiltered getAllFavorites / getAll above are shared with FavoritesRepository
    // (main favorites list), FavoritesRepositoryImpl, and SyncManager push/pull
    // paths — all must remain untouched.

    /** Me-feed composition: live list of APPROVED favorite videos (B2). */
    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 AND approval_status = 'APPROVED' ORDER BY addedAt DESC")
    fun observeApprovedFavorites(uid: String): Flow<List<FavoriteVideo>>

    /** Awaiting-review surface: live list of AWAITING favorite videos (B2). */
    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND deleted = 0 AND approval_status = 'AWAITING' ORDER BY addedAt DESC")
    fun observeAwaitingFavorites(uid: String): Flow<List<FavoriteVideo>>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid AND deleted = 0)")
    fun isFavorite(uid: String, videoId: String): Flow<Boolean>

    @Query("SELECT EXISTS(SELECT 1 FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid AND deleted = 0)")
    suspend fun isFavoriteOnce(uid: String, videoId: String): Boolean

    @Insert(onConflict = OnConflictStrategy.IGNORE)
    suspend fun addFavorite(video: FavoriteVideo)

    @Query("""
        UPDATE favorite_videos
        SET title = :title, channelName = :channelName, thumbnailUrl = :thumbnailUrl, durationSeconds = :durationSeconds, dirty = 1
        WHERE videoId = :videoId AND user_id = :uid
    """)
    suspend fun updateMetadata(
        uid: String,
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    )

    @Transaction
    suspend fun upsertFavorite(video: FavoriteVideo) {
        val exists = isFavoriteOnce(video.user_id, video.videoId)
        if (exists) {
            updateMetadata(video.user_id, video.videoId, video.title, video.channelName, video.thumbnailUrl, video.durationSeconds)
        } else {
            addFavorite(video)
        }
    }

    @Query("UPDATE favorite_videos SET deleted = 1, dirty = 1 WHERE videoId = :videoId AND user_id = :uid")
    suspend fun softDelete(uid: String, videoId: String)

    // Cubic R-final P2 — match SubscribedChannelDao + SavedPlaylistDao
    // semantics: `getById` filters deleted=0, `getByIdAny` is the
    // deleted-agnostic lookup. Pre-fix FavoriteVideoDao.getById was the
    // odd-one-out; future callers reaching for it expecting the filtered
    // variant could ship UI bugs returning soft-deleted rows.
    @Query("SELECT * FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid AND deleted = 0 LIMIT 1")
    suspend fun getById(uid: String, videoId: String): FavoriteVideo?

    /** Deleted-agnostic lookup — used by [toggleFavorite] re-add path + SyncManager. */
    @Query("SELECT * FROM favorite_videos WHERE videoId = :videoId AND user_id = :uid LIMIT 1")
    suspend fun getByIdAny(uid: String, videoId: String): FavoriteVideo?

    /**
     * Resurrects a soft-deleted row in place (deleted=0, dirty=1).
     * Plan D: [toggleFavorite]'s re-add path calls this BEFORE [upsertFavorite] so
     * the latter's [isFavoriteOnce] check (which excludes deleted=1 rows) sees
     * the row, takes the [updateMetadata] branch, and refreshes title/channel/
     * thumbnail/durationSeconds in addition to the dirty=1 + deleted=0 we set
     * here. [updateMetadata] also stamps dirty=1, so the sync push fires
     * regardless of whether the metadata actually changed.
     */
    @Query("UPDATE favorite_videos SET deleted = 0, dirty = 1 WHERE videoId = :videoId AND user_id = :uid")
    suspend fun clearSoftDelete(uid: String, videoId: String)

    /**
     * Toggle favorite status.
     *
     * The re-add path uses [clearSoftDelete] + [upsertFavorite] instead of plain
     * [addFavorite] so that a previously soft-deleted row is resurrected rather
     * than silently skipped by the IGNORE conflict strategy.
     */
    @Transaction
    suspend fun toggleFavorite(video: FavoriteVideo): Boolean {
        val existing = getByIdAny(video.user_id, video.videoId)
        if (existing != null && !existing.deleted) {
            softDelete(video.user_id, video.videoId)
            return false
        } else {
            // Fresh add OR re-add of a previously soft-deleted row.
            clearSoftDelete(video.user_id, video.videoId)
            upsertFavorite(video)
            return true
        }
    }

    /**
     * Cubic R-final2 P1 — single transactional resurrect-and-upsert for
     * FavoritesRepository.addFavorite. Pre-fix the repository called
     * clearSoftDelete + upsertFavorite as two separate DAO ops; a process
     * kill between them left the row at deleted=0/dirty=1 with stale
     * metadata, which pushDirty would then ship to the server, silently
     * losing the fresher payload the user just supplied. Wrapping in
     * @Transaction makes the pair atomic at the SQLite level.
     */
    @Transaction
    suspend fun resurrectAndUpsert(video: FavoriteVideo) {
        clearSoftDelete(video.user_id, video.videoId)
        upsertFavorite(video)
    }

    @Query("SELECT COUNT(*) FROM favorite_videos WHERE user_id = :uid AND deleted = 0")
    fun getFavoriteCount(uid: String): Flow<Int>

    @Query("DELETE FROM favorite_videos WHERE user_id = :uid")
    suspend fun clearAll(uid: String)

    // ── Sync surface ──────────────────────────────────────────────────

    @Query("UPDATE favorite_videos SET user_id = :uid, dirty = 1 WHERE user_id = ''")
    suspend fun tagAnonRowsToUid(uid: String): Int

    @Query("SELECT * FROM favorite_videos WHERE user_id = :uid AND dirty = 1")
    suspend fun selectDirty(uid: String): List<FavoriteVideo>

    /**
     * Cubic R7 P1 — monotonicity guard.
     *
     * Pre-fix the WHERE clause did not compare timestamps, so a slow PULL
     * arriving with T1 followed by a clearDirty for the fresher local edit
     * at T2 (or the reverse interleave) could clobber a fresher state. The
     * AND updated_at &lt; :ts predicate makes the write a no-op when the
     * existing row already has a more-recent server timestamp; the row's
     * local edit then survives until the next genuine server-side change.
     */
    @Query("UPDATE favorite_videos SET updated_at = :ts, dirty = 0 WHERE videoId = :videoId AND user_id = :uid AND updated_at < :ts")
    suspend fun clearDirty(uid: String, videoId: String, ts: Long)

    @Query("DELETE FROM favorite_videos WHERE user_id = :uid")
    suspend fun wipeForUid(uid: String)

    /** Cubic R7 P1 — same monotonicity guard as clearDirty. */
    @Query("UPDATE favorite_videos SET deleted = 1, dirty = 0, updated_at = :ts WHERE videoId = :videoId AND user_id = :uid AND updated_at < :ts")
    suspend fun applyTombstone(uid: String, videoId: String, ts: Long)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertFromServer(video: FavoriteVideo)
}
