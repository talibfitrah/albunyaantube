package com.albunyaan.tube.data.local

import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.auth.currentUid
import com.albunyaan.tube.data.sync.SyncManager
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Interface for managing favorite videos.
 *
 * Provides a clean abstraction for favorites operations, enabling
 * testing with fake implementations.
 */
interface FavoritesRepository {
    /**
     * Get all favorite videos as a Flow.
     * Emits updates automatically when favorites change.
     */
    fun getAllFavorites(): Flow<List<FavoriteVideo>>

    /** B3: Live list of APPROVED favorites for Me-feed composition. */
    fun observeApprovedFavorites(): Flow<List<FavoriteVideo>>

    /** B3: Live list of AWAITING favorites for the awaiting-imports surface. */
    fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>>

    /**
     * Check if a video is favorited (reactive).
     */
    fun isFavorite(videoId: String): Flow<Boolean>

    /**
     * Check if a video is favorited (one-shot).
     */
    suspend fun isFavoriteOnce(videoId: String): Boolean

    /**
     * Add a video to favorites.
     */
    suspend fun addFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    )

    /**
     * Remove a video from favorites.
     */
    suspend fun removeFavorite(videoId: String)

    /**
     * Toggle favorite status.
     * Returns true if now favorited, false if removed.
     */
    suspend fun toggleFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ): Boolean

    /**
     * Get the count of favorites.
     */
    fun getFavoriteCount(): Flow<Int>

    /**
     * Clear all favorites.
     */
    suspend fun clearAll()
}

/**
 * Default implementation of FavoritesRepository using Room DAO.
 *
 * Plan D T26: all DAO calls now source the uid from [AccountRepository.currentUid].
 * When no user is signed in this returns "" (anon-era sentinel), matching prior
 * behaviour. SyncManager.bind() tags those rows with the real uid on first sign-in.
 */
@Singleton
class FavoritesRepositoryImpl @Inject constructor(
    private val favoriteVideoDao: FavoriteVideoDao,
    private val accountRepository: AccountRepository,
    private val syncManager: SyncManager,
) : FavoritesRepository {

    // Cubic R5 P0 #5 — see SubscriptionRepository: Flow factories must
    // rescope on accountState transitions, otherwise the captured uid leaks
    // across sign-in / sign-out (anon rows persist after cold-start sign-in;
    // previous user's rows persist after sign-out).

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getAllFavorites(): Flow<List<FavoriteVideo>> =
        accountRepository.accountState.flatMapLatest { state ->
            favoriteVideoDao.getAllFavorites(uid = uidOf(state))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeApprovedFavorites(): Flow<List<FavoriteVideo>> =
        accountRepository.accountState.flatMapLatest { state ->
            favoriteVideoDao.observeApprovedFavorites(uid = uidOf(state))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun observeAwaitingFavorites(): Flow<List<FavoriteVideo>> =
        accountRepository.accountState.flatMapLatest { state ->
            favoriteVideoDao.observeAwaitingFavorites(uid = uidOf(state))
        }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun isFavorite(videoId: String): Flow<Boolean> =
        accountRepository.accountState.flatMapLatest { state ->
            favoriteVideoDao.isFavorite(uid = uidOf(state), videoId = videoId)
        }

    override suspend fun isFavoriteOnce(videoId: String): Boolean {
        return favoriteVideoDao.isFavoriteOnce(uid = accountRepository.currentUid(), videoId = videoId)
    }

    override suspend fun addFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ) {
        val uid = accountRepository.currentUid()
        val favorite = FavoriteVideo(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            user_id = uid,
            dirty = true,
        )
        // Cubic R7 P1 — resurrect a soft-deleted row before upsert.
        //
        // Pre-fix upsertFavorite's exists-check (isFavoriteOnce) excluded
        // deleted=1 rows, so the upsert path took the addFavorite branch with
        // OnConflictStrategy.IGNORE — which silently no-op'd against the
        // existing tombstone. The favorite never made it back to the list.
        // clearSoftDelete flips the tombstone back live so the upsert sees
        // the row, takes the updateMetadata branch, refreshes title/channel/
        // thumbnail, and stamps dirty=1 for sync push.
        //
        // Cubic R-final2 P1 — bundled into a single @Transaction DAO method.
        // Pre-fix the pair was non-atomic; a process kill between
        // clearSoftDelete and upsertFavorite would leave the row at
        // deleted=0/dirty=1 with stale metadata, and pushDirty would ship
        // the stale payload to the server (losing the fresher user input).
        favoriteVideoDao.resurrectAndUpsert(favorite)
        syncManager.pushDirtyAsync(uid)
    }

    override suspend fun removeFavorite(videoId: String) {
        val uid = accountRepository.currentUid()
        favoriteVideoDao.softDelete(uid = uid, videoId = videoId)
        syncManager.pushDirtyAsync(uid)
    }

    override suspend fun toggleFavorite(
        videoId: String,
        title: String,
        channelName: String,
        thumbnailUrl: String?,
        durationSeconds: Int
    ): Boolean {
        val uid = accountRepository.currentUid()
        val video = FavoriteVideo(
            videoId = videoId,
            title = title,
            channelName = channelName,
            thumbnailUrl = thumbnailUrl,
            durationSeconds = durationSeconds,
            user_id = uid,
            dirty = true,
        )
        val result = favoriteVideoDao.toggleFavorite(video)
        syncManager.pushDirtyAsync(uid)
        return result
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    override fun getFavoriteCount(): Flow<Int> =
        accountRepository.accountState.flatMapLatest { state ->
            favoriteVideoDao.getFavoriteCount(uid = uidOf(state))
        }

    override suspend fun clearAll() {
        favoriteVideoDao.clearAll(uid = accountRepository.currentUid())
    }

    private fun uidOf(state: AccountState): String =
        (state as? AccountState.Loaded)?.uid ?: ""
}
