package com.albunyaan.tube.data.local

import androidx.paging.PagingSource
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

    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId = :channelId
           ORDER BY uploadedAt DESC"""
    )
    suspend fun getForChannel(channelId: String): List<ChannelVideoCache>

    /**
     * T11: paginated source for the Me-feed videos grid. Mirrors
     * [observeRecentForChannels] but returns a [PagingSource] so the UI
     * loads in PAGE_SIZE batches instead of pulling the whole 14-day
     * window into memory up front.
     *
     * - `channelIds`: subscribed-channel scope (capped at
     *   [com.albunyaan.tube.data.subscriptions.SubscriptionLimitGuard.CAP] in
     *   the repository).
     * - `cutoffMs`: minimum uploadedAt — the rolling 14-day window.
     * - `filterChannelId`: optional chip-driven filter; null = all subscribed.
     *
     * The `uploadedAt IS NOT NULL` guard mirrors [observeRecentForChannels]
     * because the entity column is nullable and untimed rows must never
     * surface in a date-ordered feed.
     *
     * The `isShort = 0` guard excludes Shorts: the Me tab renders Shorts in
     * a dedicated horizontal row above the long-form grid (see
     * [com.albunyaan.tube.ui.me.MeShortsAdapter]), so without this clause
     * every Short would be displayed twice.
     *
     * ANDROID-PERSONAL-02 round 4: no upload-date cutoff. Paging loads in
     * PAGE_SIZE batches; the cache is bounded by what ATOM returns
     * per-channel (~15 newest items × CAP=30 channels), so showing the full
     * cache newest-first is safe and matches user expectation (YouTube
     * subscription feed never hides old uploads).
     */
    @Query(
        """SELECT * FROM channel_video_cache
           WHERE channelId IN (:channelIds)
             AND isShort = 0
             AND uploadedAt IS NOT NULL
             AND (:filterChannelId IS NULL OR channelId = :filterChannelId)
           ORDER BY uploadedAt DESC"""
    )
    fun pagingForChannels(
        channelIds: List<String>,
        filterChannelId: String?,
    ): PagingSource<Int, ChannelVideoCache>

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
}
