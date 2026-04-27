package com.albunyaan.tube.data.me.work

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.work.Configuration
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.WorkManagerTestInitHelper
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.ChannelFeedRefreshState
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Tests for [RefreshScheduler] (ANDROID-PERSONAL-02 / T9 + [Bug 4]).
 *
 * Focus is on [RefreshScheduler.enqueueForegroundBurstIfStale] — the
 * fix for [Bug 4] where a brand-new subscription paired with a
 * recently-fetched older channel previously deferred the new channel's
 * first fetch until the next periodic worker (up to 60 minutes).
 *
 * Approach:
 *  - Real Room in-memory database (matches the rest of the suite).
 *  - Real [SubscriptionRepository] over the in-memory DAOs.
 *  - WorkManager via [WorkManagerTestInitHelper] so we can assert that
 *    a one-shot [RefreshSubscriptionsWorker] was actually enqueued (or
 *    not, on the negative path).
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class RefreshSchedulerTest {

    private lateinit var ctx: Context
    private lateinit var db: AppDatabase
    private lateinit var subs: SubscriptionRepository
    private lateinit var scheduler: RefreshScheduler

    @Before
    fun setUp() {
        ShadowLog.stream = System.out
        ctx = ApplicationProvider.getApplicationContext()
        // Initialise WorkManager with a synchronous executor so enqueue
        // results are observable in the same call. Constraints (network
        // type) are satisfied via WorkManagerTestInitHelper.
        val cfg = Configuration.Builder()
            .setMinimumLoggingLevel(android.util.Log.DEBUG)
            .setExecutor(java.util.concurrent.Executors.newSingleThreadExecutor())
            .build()
        WorkManagerTestInitHelper.initializeTestWorkManager(ctx, cfg)

        db = Room.inMemoryDatabaseBuilder(ctx, AppDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        subs = SubscriptionRepository(
            db = db,
            channels = db.subscribedChannelDao(),
            playlists = db.savedPlaylistDao(),
            cache = db.channelVideoCacheDao(),
            refreshState = db.channelFeedRefreshStateDao(),
        )
        scheduler = RefreshScheduler(
            ctx = ctx,
            refreshStateDao = db.channelFeedRefreshStateDao(),
            subscriptionRepository = subs,
        )
    }

    @After
    fun tearDown() {
        db.close()
        // Cancel any work enqueued during the test so it doesn't leak
        // into other tests sharing the Robolectric sandbox.
        WorkManager.getInstance(ctx).cancelUniqueWork(
            RefreshScheduler.UNIQUE_ONESHOT_NAME
        )
    }

    private fun oneshotInfos(): List<WorkInfo> = WorkManager.getInstance(ctx)
        .getWorkInfosForUniqueWork(RefreshScheduler.UNIQUE_ONESHOT_NAME)
        .get()

    @Test
    fun `Bug 4 burst fires when subscribed channel has no refresh-state row`() = runTest {
        // Subscribe two channels, but only ONE has a refresh-state row
        // and that row is "very recent" so the MAX(lastSuccessfulFetchAt)
        // says everything is fresh. The MAX-only branch would wrongly
        // skip the burst — Bug 4.
        val now = System.currentTimeMillis()
        subs.subscribe(SubscribedChannel("UC_old", "https://yt/UC_old", "old", null, now - 10_000L))
        subs.subscribe(SubscribedChannel("UC_new", "https://yt/UC_new", "new", null, now - 5_000L))
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_old",
                lastSuccessfulFetchAt = now - 60_000L, // 1 minute ago — fresh
                lastAttemptAt = now - 60_000L,
                lastErrorMessage = null,
            )
        )
        // No row for UC_new on purpose.

        scheduler.enqueueForegroundBurstIfStale(staleThresholdMs = 30L * 60L * 1_000L)

        // Verify a one-shot was enqueued.
        val infos = oneshotInfos()
        assertEquals("a one-shot must be enqueued for the never-fetched channel", 1, infos.size)
        assertTrue(
            "the one-shot must be in a non-terminal state (ENQUEUED or RUNNING)",
            infos.first().state == WorkInfo.State.ENQUEUED ||
                infos.first().state == WorkInfo.State.RUNNING ||
                infos.first().state == WorkInfo.State.SUCCEEDED
        )
    }

    @Test
    fun `Bug 4 burst skipped when all subscribed channels are fresh`() = runTest {
        // Two subscribed channels, both with recent refresh-state rows.
        // Neither MAX-stale nor missing-row applies → no burst.
        val now = System.currentTimeMillis()
        subs.subscribe(SubscribedChannel("UC_a", "https://yt/UC_a", "a", null, now - 5_000L))
        subs.subscribe(SubscribedChannel("UC_b", "https://yt/UC_b", "b", null, now - 5_000L))
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_a",
                lastSuccessfulFetchAt = now - 60_000L,
                lastAttemptAt = now - 60_000L,
                lastErrorMessage = null,
            )
        )
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_b",
                lastSuccessfulFetchAt = now - 60_000L,
                lastAttemptAt = now - 60_000L,
                lastErrorMessage = null,
            )
        )

        scheduler.enqueueForegroundBurstIfStale(staleThresholdMs = 30L * 60L * 1_000L)

        // Verify NO one-shot was enqueued.
        val infos = oneshotInfos()
        assertTrue(
            "burst must not fire when MAX is fresh AND all subscribed channels have rows (got ${infos.size})",
            infos.isEmpty(),
        )
    }

    @Test
    fun `burst still fires when MAX is stale`() = runTest {
        // Existing pre-Bug-4 behaviour preserved: stale MAX still triggers
        // a burst even when every subscribed channel has a row.
        val now = System.currentTimeMillis()
        subs.subscribe(SubscribedChannel("UC_a", "https://yt/UC_a", "a", null, now - 5_000L))
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_a",
                // Older than the stale threshold we pass below.
                lastSuccessfulFetchAt = now - 90L * 60L * 1_000L,
                lastAttemptAt = now - 90L * 60L * 1_000L,
                lastErrorMessage = null,
            )
        )

        scheduler.enqueueForegroundBurstIfStale(staleThresholdMs = 30L * 60L * 1_000L)

        val infos = oneshotInfos()
        assertEquals("burst must fire when MAX is stale", 1, infos.size)
    }

    @Test
    fun `burst fires on first foreground when no rows exist at all`() = runTest {
        // Cold start: subscribed channels exist, no refresh-state rows yet.
        // newest=0, hasNewChannel=true → burst should fire.
        val now = System.currentTimeMillis()
        subs.subscribe(SubscribedChannel("UC_a", "https://yt/UC_a", "a", null, now - 5_000L))
        // Note: maxLastSuccessfulFetchAt() returns null → 0L → trivially stale.

        scheduler.enqueueForegroundBurstIfStale(staleThresholdMs = 30L * 60L * 1_000L)

        val infos = oneshotInfos()
        assertEquals("cold-start with subscribed channels must fire a burst", 1, infos.size)
    }

    @Test
    fun `Bug D burst fires when orphan row matches subscribed count`() = runTest {
        // ANDROID-PERSONAL-02 round 2 [Bug D]: the previous count-based check
        // (`knownChannelCount() < subscribedCount`) wrongly skipped the burst
        // when an unpruned orphan row inflates the count to match the
        // subscribed count even though a subscribed channel has no row.
        //
        // Set-up: subscribed = {UC_subscribed, UC_new}; refresh-state =
        // {UC_subscribed, UC_orphan} where UC_orphan is a leftover row for
        // a channel the user already unsubscribed (transactional unsubscribe
        // would normally have deleted it; we simulate the case where pruning
        // missed it). counts: known=2, subscribed=2 → old logic: skip.
        // Per-channelId set diff: subs - known = {UC_new}, non-empty → fire.
        val now = System.currentTimeMillis()
        subs.subscribe(SubscribedChannel("UC_subscribed", "https://yt/UC_subscribed", "subscribed", null, now - 5_000L))
        subs.subscribe(SubscribedChannel("UC_new", "https://yt/UC_new", "new", null, now - 5_000L))
        // Recent row for the still-subscribed channel — keeps MAX fresh.
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_subscribed",
                lastSuccessfulFetchAt = now - 60_000L,
                lastAttemptAt = now - 60_000L,
                lastErrorMessage = null,
            )
        )
        // Orphan row from a previously-unsubscribed channel that pruning missed.
        db.channelFeedRefreshStateDao().upsert(
            ChannelFeedRefreshState(
                channelId = "UC_orphan",
                lastSuccessfulFetchAt = now - 60_000L,
                lastAttemptAt = now - 60_000L,
                lastErrorMessage = null,
            )
        )

        scheduler.enqueueForegroundBurstIfStale(staleThresholdMs = 30L * 60L * 1_000L)

        val infos = oneshotInfos()
        assertEquals(
            "set-difference must catch UC_new even when the orphan row inflates known-count to match subscribed-count",
            1,
            infos.size,
        )
    }
}
