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
    fun `replaceForChannel removes old and inserts new`() = runTest {
        cache.upsertAll(listOf(row("v1", "UC1", 1_000L), row("v2", "UC1", 2_000L)))
        cache.replaceForChannel("UC1", listOf(row("v3", "UC1", 3_000L)))

        val remaining = cache.getForChannel("UC1")
        assertEquals(1, remaining.size)
        assertEquals("v3", remaining[0].videoId)
    }

    @Test
    fun `replaceForChannel with empty list clears the channel`() = runTest {
        cache.upsertAll(listOf(row("v1", "UC1", 1L), row("v2", "UC1", 2L)))
        cache.replaceForChannel("UC1", emptyList())
        assertTrue(cache.getForChannel("UC1").isEmpty())
    }

    @Test
    fun `observeRecent excludes rows with null uploadedAt`() = runTest {
        cache.upsertAll(
            listOf(
                row("v1", "UC1", 2_000L),
                row("v2", "UC1", null),
                row("v3", "UC1", 3_000L),
            )
        )
        val rows = cache.observeRecent(minUploadedAt = 0L).first()
        assertEquals(listOf("v3", "v1"), rows.map { it.videoId })
    }

    @Test
    fun `observeRecent excludes rows older than min`() = runTest {
        cache.upsertAll(
            listOf(
                row("old", "UC1", 500L),
                row("new", "UC1", 5_000L),
            )
        )
        val rows = cache.observeRecent(minUploadedAt = 1_000L).first()
        assertEquals(listOf("new"), rows.map { it.videoId })
    }

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
