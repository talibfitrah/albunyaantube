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
        repeat(29) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertEquals(SubscribeResult.Success, result)
        assertEquals(30, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun trySubscribe_returns_LimitReached_at_cap() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertTrue(result is SubscribeResult.LimitReached)
        assertEquals(30, (result as SubscribeResult.LimitReached).current)
        assertEquals(30, db.subscribedChannelDao().getAll().size) // not added
    }

    @Test
    fun trySubscribe_idempotent_for_existing_subscription() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        val result = guard.trySubscribe(channel("UC0")) // already exists
        assertEquals(SubscribeResult.Success, result)
        assertEquals(30, db.subscribedChannelDao().getAll().size)
    }

    @Test
    fun playlists_do_not_count_toward_cap() = runTest {
        repeat(30) { db.subscribedChannelDao().upsert(channel("UC$it")) }
        repeat(50) { db.savedPlaylistDao().upsert(playlist("PL$it")) }
        val result = guard.trySubscribe(channel("UCnew"))
        assertTrue(result is SubscribeResult.LimitReached)
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
