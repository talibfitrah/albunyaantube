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

/**
 * ANDROID-IMPORT-02 — verifies the approval_status-filtered feed queries and
 * the new AWAITING queries added to the three Me-feed DAOs.
 *
 * Each assertion group checks three things:
 *  1. The approved (feed) query returns ONLY 'APPROVED' rows.
 *  2. The awaiting query returns ONLY 'AWAITING' rows.
 *  3. The unfiltered sync query (observeAll / getAll / getAllFavorites) still
 *     returns BOTH rows, confirming sync paths are unaffected.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class ImportDaoTest {

    private lateinit var db: AppDatabase
    private lateinit var channels: SubscribedChannelDao
    private lateinit var playlists: SavedPlaylistDao
    private lateinit var favorites: FavoriteVideoDao

    companion object {
        private const val UID = "test-uid"
    }

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        channels = db.subscribedChannelDao()
        playlists = db.savedPlaylistDao()
        favorites = db.favoriteVideoDao()
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SubscribedChannelDao
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `channels - observeApproved returns only APPROVED rows`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))

        val result = channels.observeApprovedChannels(UID).first()
        assertEquals(1, result.size)
        assertEquals("UC1", result[0].channelId)
    }

    @Test
    fun `channels - observeAwaiting returns only AWAITING rows`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))

        val result = channels.observeAwaitingChannels(UID).first()
        assertEquals(1, result.size)
        assertEquals("UC2", result[0].channelId)
    }

    @Test
    fun `channels - getApproved returns only APPROVED rows`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))

        val result = channels.getApprovedSubscribedChannels(UID)
        assertEquals(1, result.size)
        assertEquals("UC1", result[0].channelId)
    }

    @Test
    fun `channels - sync query observeAll is unfiltered (returns both statuses)`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))

        val result = channels.observeAll(UID).first()
        assertEquals(2, result.size)
    }

    @Test
    fun `channels - sync query getAll is unfiltered (returns both statuses)`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))

        val result = channels.getAll(UID)
        assertEquals(2, result.size)
    }

    @Test
    fun `channels - deleted rows excluded from approved and awaiting queries`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(awaitingChannel("UC2"))
        channels.softDelete(UID, "UC1")
        channels.softDelete(UID, "UC2")

        assertTrue(channels.observeApprovedChannels(UID).first().isEmpty())
        assertTrue(channels.observeAwaitingChannels(UID).first().isEmpty())
        assertTrue(channels.getApprovedSubscribedChannels(UID).isEmpty())
    }

    @Test
    fun `channels - empty awaiting when all rows are APPROVED`() = runTest {
        channels.upsert(approvedChannel("UC1"))
        channels.upsert(approvedChannel("UC3"))

        assertTrue(channels.observeAwaitingChannels(UID).first().isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // SavedPlaylistDao
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `playlists - observeApproved returns only APPROVED rows`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))

        val result = playlists.observeApprovedPlaylists(UID).first()
        assertEquals(1, result.size)
        assertEquals("PL1", result[0].playlistId)
    }

    @Test
    fun `playlists - observeAwaiting returns only AWAITING rows`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))

        val result = playlists.observeAwaitingPlaylists(UID).first()
        assertEquals(1, result.size)
        assertEquals("PL2", result[0].playlistId)
    }

    @Test
    fun `playlists - getApproved returns only APPROVED rows`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))

        val result = playlists.getApprovedSavedPlaylists(UID)
        assertEquals(1, result.size)
        assertEquals("PL1", result[0].playlistId)
    }

    @Test
    fun `playlists - sync query observeAll is unfiltered (returns both statuses)`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))

        val result = playlists.observeAll(UID).first()
        assertEquals(2, result.size)
    }

    @Test
    fun `playlists - sync query getAll is unfiltered (returns both statuses)`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))

        val result = playlists.getAll(UID)
        assertEquals(2, result.size)
    }

    @Test
    fun `playlists - deleted rows excluded from approved and awaiting queries`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))
        playlists.upsert(awaitingPlaylist("PL2"))
        playlists.softDelete(UID, "PL1")
        playlists.softDelete(UID, "PL2")

        assertTrue(playlists.observeApprovedPlaylists(UID).first().isEmpty())
        assertTrue(playlists.observeAwaitingPlaylists(UID).first().isEmpty())
        assertTrue(playlists.getApprovedSavedPlaylists(UID).isEmpty())
    }

    @Test
    fun `playlists - empty awaiting when all rows are APPROVED`() = runTest {
        playlists.upsert(approvedPlaylist("PL1"))

        assertTrue(playlists.observeAwaitingPlaylists(UID).first().isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // FavoriteVideoDao
    // ──────────────────────────────────────────────────────────────────────────

    @Test
    fun `favorites - observeApproved returns only APPROVED rows`() = runTest {
        favorites.addFavorite(approvedFavorite("vid1"))
        favorites.addFavorite(awaitingFavorite("vid2"))

        val result = favorites.observeApprovedFavorites(UID).first()
        assertEquals(1, result.size)
        assertEquals("vid1", result[0].videoId)
    }

    @Test
    fun `favorites - observeAwaiting returns only AWAITING rows`() = runTest {
        favorites.addFavorite(approvedFavorite("vid1"))
        favorites.addFavorite(awaitingFavorite("vid2"))

        val result = favorites.observeAwaitingFavorites(UID).first()
        assertEquals(1, result.size)
        assertEquals("vid2", result[0].videoId)
    }

    @Test
    fun `favorites - sync query getAllFavorites is unfiltered (returns both statuses)`() = runTest {
        favorites.addFavorite(approvedFavorite("vid1"))
        favorites.addFavorite(awaitingFavorite("vid2"))

        val result = favorites.getAllFavorites(UID).first()
        assertEquals(2, result.size)
    }

    @Test
    fun `favorites - deleted rows excluded from approved and awaiting queries`() = runTest {
        favorites.addFavorite(approvedFavorite("vid1"))
        favorites.addFavorite(awaitingFavorite("vid2"))
        favorites.softDelete(UID, "vid1")
        favorites.softDelete(UID, "vid2")

        val approved: List<FavoriteVideo> = favorites.observeApprovedFavorites(UID).first()
        val awaiting: List<FavoriteVideo> = favorites.observeAwaitingFavorites(UID).first()
        assertTrue(approved.isEmpty())
        assertTrue(awaiting.isEmpty())
    }

    @Test
    fun `favorites - empty awaiting when all rows are APPROVED`() = runTest {
        favorites.addFavorite(approvedFavorite("vid1"))

        val awaiting: List<FavoriteVideo> = favorites.observeAwaitingFavorites(UID).first()
        assertTrue(awaiting.isEmpty())
    }

    // ──────────────────────────────────────────────────────────────────────────
    // Helpers
    // ──────────────────────────────────────────────────────────────────────────

    private fun approvedChannel(id: String) = SubscribedChannel(
        channelId = id,
        channelUrl = "https://yt.com/channel/$id",
        name = "Channel $id",
        avatarUrl = null,
        subscribedAt = 1_000L,
        user_id = UID,
        approvalStatus = "APPROVED",
    )

    private fun awaitingChannel(id: String) = SubscribedChannel(
        channelId = id,
        channelUrl = "https://yt.com/channel/$id",
        name = "Channel $id",
        avatarUrl = null,
        subscribedAt = 2_000L,
        user_id = UID,
        approvalStatus = "AWAITING",
        source = "YOUTUBE_IMPORT",
        importedAt = 3_000L,
    )

    private fun approvedPlaylist(id: String) = SavedPlaylist(
        playlistId = id,
        playlistUrl = "https://yt.com/playlist?list=$id",
        name = "Playlist $id",
        thumbnailUrl = null,
        uploaderName = null,
        savedAt = 1_000L,
        user_id = UID,
        approvalStatus = "APPROVED",
    )

    private fun awaitingPlaylist(id: String) = SavedPlaylist(
        playlistId = id,
        playlistUrl = "https://yt.com/playlist?list=$id",
        name = "Playlist $id",
        thumbnailUrl = null,
        uploaderName = null,
        savedAt = 2_000L,
        user_id = UID,
        approvalStatus = "AWAITING",
        source = "YOUTUBE_IMPORT",
        importedAt = 3_000L,
    )

    private fun approvedFavorite(id: String) = FavoriteVideo(
        videoId = id,
        title = "Video $id",
        channelName = "Channel",
        thumbnailUrl = null,
        durationSeconds = 120,
        addedAt = 1_000L,
        user_id = UID,
        approvalStatus = "APPROVED",
    )

    private fun awaitingFavorite(id: String) = FavoriteVideo(
        videoId = id,
        title = "Video $id",
        channelName = "Channel",
        thumbnailUrl = null,
        durationSeconds = 120,
        addedAt = 2_000L,
        user_id = UID,
        approvalStatus = "AWAITING",
        source = "YOUTUBE_IMPORT",
        importedAt = 3_000L,
    )
}
