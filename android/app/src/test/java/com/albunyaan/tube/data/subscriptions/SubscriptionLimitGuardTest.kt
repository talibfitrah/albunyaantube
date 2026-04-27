package com.albunyaan.tube.data.subscriptions

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SubscriptionLimitGuardTest {

    private lateinit var db: AppDatabase
    private lateinit var guard: SubscriptionLimitGuard

    @Before
    fun setup() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()
        guard = SubscriptionLimitGuard(db.subscribedChannelDao(), db)
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun trySubscribe_succeeds_when_under_cap() = runTest {
        repeat(SubscriptionLimitGuard.CAP - 1) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertEquals(SubscribeResult.Success, result)
        assertEquals(SubscriptionLimitGuard.CAP, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun trySubscribe_returns_LimitReached_at_cap() = runTest {
        repeat(SubscriptionLimitGuard.CAP) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertTrue(result is SubscribeResult.LimitReached)
        assertEquals(SubscriptionLimitGuard.CAP, (result as SubscribeResult.LimitReached).current)
        assertEquals(SubscriptionLimitGuard.CAP, db.subscribedChannelDao().getAll().size) // not added
    }

    @Test
    fun trySubscribe_idempotent_for_existing_subscription() = runTest {
        repeat(SubscriptionLimitGuard.CAP) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UC0")) // already exists
        assertEquals(SubscribeResult.Success, result)
        assertEquals(SubscriptionLimitGuard.CAP, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun playlists_do_not_count_toward_channel_cap() = runTest {
        // Property: playlists are unlimited — they must NOT pollute the channel
        // count check. With 29 channels (under cap) and an arbitrary number of
        // playlists, a 30th channel subscription must SUCCEED. A regression
        // that made the count include playlists (e.g. SELECT COUNT(*) FROM
        // subscribed_channels UNION ALL FROM saved_playlists) would fail this
        // test; the previous "30 channels + 50 playlists → LimitReached" shape
        // could not detect that regression because at 30 channels you are
        // already blocked regardless.
        repeat(SubscriptionLimitGuard.CAP - 1) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        repeat(100) { db.savedPlaylistDao().upsert(playlist("PL$it")) }

        val result = guard.trySubscribe(channel("UCnew"))

        assertEquals(SubscribeResult.Success, result)
        assertEquals(SubscriptionLimitGuard.CAP, db.subscribedChannelDao().getAll().size)
        assertEquals(100, db.savedPlaylistDao().getAll().size)
    }

    private fun channel(id: String) = SubscribedChannel(
        channelId = id,
        channelUrl = "https://www.youtube.com/channel/$id",
        name = id,
        avatarUrl = null,
        subscribedAt = System.currentTimeMillis(),
    )

    private fun playlist(id: String) = SavedPlaylist(
        playlistId = id,
        playlistUrl = "https://www.youtube.com/playlist?list=$id",
        name = id,
        thumbnailUrl = null,
        uploaderName = null,
        savedAt = System.currentTimeMillis(),
    )
}
