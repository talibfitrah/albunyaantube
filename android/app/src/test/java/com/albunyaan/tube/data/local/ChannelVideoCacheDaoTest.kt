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
    private lateinit var playlistLinks: PlaylistVideoLinkDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        cache = db.channelVideoCacheDao()
        channels = db.subscribedChannelDao()
        playlistLinks = db.playlistVideoLinkDao()
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

    @Test
    fun `pruneUnsubscribed keeps playlist-linked videos from non-subscribed channels`() = runTest {
        // UC1 subscribed; UC2 not subscribed but its video is in a saved playlist.
        channels.upsert(SubscribedChannel("UC1", "u", "A", null, 0L))
        cache.upsertAll(
            listOf(
                row("vA", "UC1", 1L),   // subscribed channel upload — keep
                row("vB", "UC2", 2L),   // not subscribed, but linked from saved playlist — keep
                row("vC", "UC2", 3L),   // not subscribed, not playlist-linked — drop
            )
        )
        // Saved playlist PL1 references vB but not vC.
        playlistLinks.upsertAll(listOf(PlaylistVideoLink("PL1", "vB")))

        cache.pruneUnsubscribed()

        // vA + vB survive (subscribed channel + playlist-linked).
        // vC dropped (neither subscribed nor playlist-linked).
        assertEquals(listOf("vA"), cache.getForChannel("UC1").map { it.videoId })
        assertEquals(listOf("vB"), cache.getForChannel("UC2").map { it.videoId })
    }
}
