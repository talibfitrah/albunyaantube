package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import androidx.room.Transaction
import kotlinx.coroutines.flow.Flow

@Dao
interface ChannelVideoCacheDao {

    /**
     * Recent videos across any channel. Kept for tests and ad-hoc queries.
     * Use [observeRecentForChannels] for the Me-feed path so unsubscribed
     * channels' leftover rows are never surfaced.
     */
    @Query(
        """SELECT * FROM channel_video_cache
           WHERE uploadedAt IS NOT NULL AND uploadedAt >= :minUploadedAt
           ORDER BY uploadedAt DESC
           LIMIT 500"""
    )
    fun observeRecent(minUploadedAt: Long): Flow<List<ChannelVideoCache>>

    /**
     * Recent videos scoped to a given set of subscribed channel IDs. This
     * guarantees that unsubscribing a channel immediately removes its items
     * from the Me feed even if the cache rows linger (they are pruned on
     * the next refresh via [pruneUnsubscribed]).
     */
    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId IN (:channelIds)
             AND uploadedAt IS NOT NULL AND uploadedAt >= :minUploadedAt
           ORDER BY uploadedAt DESC
           LIMIT 500"""
    )
    fun observeRecentForChannels(
        channelIds: List<String>,
        minUploadedAt: Long,
    ): Flow<List<ChannelVideoCache>>

    /**
     * ANDROID-PERSONAL-03 / T4: per-week observation for the Me-tab feed.
     *
     * Returns the cached rows for the half-open `[fromMs, toMs)` upload
     * window scoped to the given channel IDs. Newest-first ordering. The
     * `uploadedAt IS NOT NULL` guard mirrors [observeRecentForChannels] —
     * untimed rows must never surface in a date-ordered window.
     *
     * The repository layer splits results into shorts vs videos by reading
     * the `isShort` flag — the DAO does not pre-bucket because a single
     * query keeps Room's invalidation tracker simple (one observer per
     * week instead of two).
     */
    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId IN (:channelIds)
             AND uploadedAt IS NOT NULL
             AND uploadedAt >= :fromMs
             AND uploadedAt < :toMs
           ORDER BY uploadedAt DESC"""
    )
    fun observeRangeForChannels(
        channelIds: List<String>,
        fromMs: Long,
        toMs: Long,
    ): Flow<List<ChannelVideoCache>>

    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId = :channelId
           ORDER BY uploadedAt DESC"""
    )
    suspend fun getForChannel(channelId: String): List<ChannelVideoCache>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsertAll(rows: List<ChannelVideoCache>)

    @Query("DELETE FROM channel_video_cache WHERE channelId = :channelId")
    suspend fun deleteForChannel(channelId: String)

    @Transaction
    suspend fun replaceForChannel(channelId: String, rows: List<ChannelVideoCache>) {
        deleteForChannel(channelId)
        if (rows.isNotEmpty()) upsertAll(rows)
    }

    @Query(
        """DELETE FROM channel_video_cache
           WHERE channelId NOT IN (SELECT channelId FROM subscribed_channels)"""
    )
    suspend fun pruneUnsubscribed()

    /**
     * Total cached row count for the given channels. Used by the Me-tab
     * deep-page loop's progress check — the SQL aggregate avoids
     * materialising the full row list just to call `.size`.
     *
     * ANDROID-PERSONAL-03 round 8 review [P1].
     */
    @Query("SELECT COUNT(*) FROM channel_video_cache WHERE channelId IN (:channelIds)")
    suspend fun countForChannels(channelIds: List<String>): Int

    /**
     * Oldest `uploadedAt` per channel (smallest non-null value), for the
     * candidate filter in [com.albunyaan.tube.data.me.MeFeedRepository.fillWeekIfNeeded].
     * Channels with zero rows are absent from the result. Returns
     * `(channelId, oldestMs)` pairs.
     *
     * ANDROID-PERSONAL-03 round 8 review [P1]: avoids loading the full
     * cache table to compute one MIN per channel.
     */
    @Query(
        """SELECT channelId, MIN(uploadedAt) AS oldestMs FROM channel_video_cache
           WHERE channelId IN (:channelIds) AND uploadedAt IS NOT NULL
           GROUP BY channelId"""
    )
    suspend fun oldestPerChannel(channelIds: List<String>): List<ChannelOldest>
}

/**
 * Projection row for [ChannelVideoCacheDao.oldestPerChannel].
 */
data class ChannelOldest(
    val channelId: String,
    val oldestMs: Long,
)
