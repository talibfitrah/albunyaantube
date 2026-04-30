package com.albunyaan.tube.data.local

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

@Dao
interface ChannelFeedRefreshStateDao {

    @Query("SELECT * FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun get(id: String): ChannelFeedRefreshState?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(state: ChannelFeedRefreshState)

    @Query("DELETE FROM channel_feed_refresh_state WHERE channelId = :id")
    suspend fun delete(id: String)

    /**
     * ANDROID-PERSONAL-02 / T9: returns the most recent successful fetch
     * timestamp across all channels, or null if no rows exist. Used by
     * `RefreshScheduler.enqueueForegroundBurstIfStale` to decide whether
     * the foreground burst is worth firing.
     */
    @Query("SELECT MAX(lastSuccessfulFetchAt) FROM channel_feed_refresh_state")
    suspend fun maxLastSuccessfulFetchAt(): Long?

    /**
     * ANDROID-PERSONAL-02 [Bug 4]: count of channelIds that have a
     * refresh-state row. Combined with the subscribed-channel count this
     * lets `RefreshScheduler.enqueueForegroundBurstIfStale` detect
     * brand-new subscriptions that have never been fetched — those won't
     * appear in the [maxLastSuccessfulFetchAt] reading at all so the burst
     * was previously skipped while they sat empty for up to 60 minutes.
     *
     * NOTE: round 2 [Bug D] superseded the count comparison with a
     * per-channelId set difference (see [getKnownChannelIds]) to fix the
     * orphan-row case. This count remains for any future caller that
     * just wants a cheap size check.
     */
    @Query("SELECT COUNT(*) FROM channel_feed_refresh_state")
    suspend fun knownChannelCount(): Int

    /**
     * ANDROID-PERSONAL-02 round 2 [Bug D]: returns the channelIds of every
     * row in the refresh-state table. Used by
     * `RefreshScheduler.enqueueForegroundBurstIfStale` to compute a
     * per-channel set difference against the subscribed channels — the
     * raw [knownChannelCount] comparison missed the orphan-row case
     * (subs={A,B}, refresh-state={A,orphan}: counts both 2, but B has
     * never been fetched).
     */
    @Query("SELECT channelId FROM channel_feed_refresh_state")
    suspend fun getKnownChannelIds(): List<String>

    /**
     * ANDROID-PERSONAL-02 / T9: batch round-robin lookup. Returns
     * `(channelId, lastSuccessfulFetchAt)` for every row in the table —
     * the caller does the join with the subscribed-channels list and
     * picks the bottom-K. Single Room round-trip instead of N — keeps
     * the per-tick latency cheap and makes the in-test virtual-time
     * behaviour deterministic.
     */
    @Query("SELECT channelId, lastSuccessfulFetchAt FROM channel_feed_refresh_state")
    suspend fun getAllLastSuccessfulFetchAt(): List<ChannelLastFetchRow>
}

/**
 * ANDROID-PERSONAL-02 / T9: lightweight row for round-robin scheduling.
 */
data class ChannelLastFetchRow(
    val channelId: String,
    val lastSuccessfulFetchAt: Long,
)
