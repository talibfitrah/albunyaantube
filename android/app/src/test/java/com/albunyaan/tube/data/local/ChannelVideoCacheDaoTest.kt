package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
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
class ChannelVideoCacheDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var cache: ChannelVideoCacheDao
    private lateinit var channels: SubscribedChannelDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        cache = db.channelVideoCacheDao()
        channels = db.subscribedChannelDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    private fun row(
        id: String,
        channel: String,
        uploadedAt: Long?,
        isShort: Boolean = false,
    ) = ChannelVideoCache(
        videoId = id,
        channelId = channel,
        channelName = "Ch $channel",
        title = "t",
        thumbnailUrl = null,
        durationSeconds = null,
        viewCount = null,
        uploadedAt = uploadedAt,
        isShort = isShort,
        fetchedAt = 0L,
    )

    @Test
    fun `pruneUnsubscribed keeps only rows whose channel is in subscribed_channels`() = runTest {
        channels.upsert(SubscribedChannel("UC1", "u", "A", null, 0L))
        // UC2 not subscribed
        cache.upsertAll(listOf(row("vA", "UC1", 1L), row("vB", "UC2", 2L)))

        cache.pruneUnsubscribed()

        assertEquals(listOf("vA"), cache.getForChannel("UC1").map { it.videoId })
        assertTrue(cache.getForChannel("UC2").isEmpty())
    }
}
