package com.albunyaan.tube.data.local

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class SubscriptionDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var channels: SubscribedChannelDao
    private lateinit var playlists: SavedPlaylistDao

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        channels = db.subscribedChannelDao()
        playlists = db.savedPlaylistDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    @Test
    fun `upsert then observeAll orders by subscribedAt DESC`() = runTest {
        channels.upsert(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        channels.upsert(SubscribedChannel("UC2", "u2", "B", null, 2_000L))
        channels.upsert(SubscribedChannel("UC3", "u3", "C", null, 3_000L))

        val ordered = channels.observeAll(uid = "").first().map { it.channelId }
        assertEquals(listOf("UC3", "UC2", "UC1"), ordered)
    }

    @Test
    fun `observeIsSubscribed flips on insert and delete`() = runTest {
        assertFalse(channels.observeIsSubscribed(uid = "", id = "UCx").first())

        channels.upsert(SubscribedChannel("UCx", "u", "N", null, 0L))
        assertTrue(channels.observeIsSubscribed(uid = "", id = "UCx").first())

        channels.softDelete(uid = "", id = "UCx")
        assertFalse(channels.observeIsSubscribed(uid = "", id = "UCx").first())
    }

    @Test
    fun `delete is idempotent on missing id`() = runTest {
        channels.softDelete(uid = "", id = "UCnone")
        assertFalse(channels.observeIsSubscribed(uid = "", id = "UCnone").first())
    }

    @Test
    fun `upsert replaces on conflict`() = runTest {
        channels.upsert(SubscribedChannel("UC1", "u", "Original", null, 1L))
        channels.upsert(SubscribedChannel("UC1", "u", "Renamed", "avatar", 1L))

        val rows = channels.getAll(uid = "")
        assertEquals(1, rows.size)
        assertEquals("Renamed", rows[0].name)
        assertEquals("avatar", rows[0].avatarUrl)
    }

    @Test
    fun `saved playlist upsert and remove`() = runTest {
        assertFalse(playlists.observeIsSaved(uid = "", id = "PL1").first())

        playlists.upsert(SavedPlaylist("PL1", "url", "Name", null, "Up", 10L))
        assertTrue(playlists.observeIsSaved(uid = "", id = "PL1").first())
        assertEquals(1, playlists.getAll(uid = "").size)

        playlists.softDelete(uid = "", id = "PL1")
        assertFalse(playlists.observeIsSaved(uid = "", id = "PL1").first())
    }

    @Test
    fun `saved playlists observed in savedAt DESC`() = runTest {
        playlists.upsert(SavedPlaylist("PL1", "u", "A", null, null, 1_000L))
        playlists.upsert(SavedPlaylist("PL2", "u", "B", null, null, 3_000L))
        playlists.upsert(SavedPlaylist("PL3", "u", "C", null, null, 2_000L))

        val ordered = playlists.observeAll(uid = "").first().map { it.playlistId }
        assertEquals(listOf("PL2", "PL3", "PL1"), ordered)
    }
}
