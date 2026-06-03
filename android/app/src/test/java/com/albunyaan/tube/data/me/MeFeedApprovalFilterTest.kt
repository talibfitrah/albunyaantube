package com.albunyaan.tube.data.me

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepositoryImpl
import com.albunyaan.tube.data.local.SavedPlaylist
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.mock
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.time.LocalDate

/**
 * B3: Verifies that feed-composition reads exclude AWAITING rows and that
 * [MeFeedRepository.observeAwaiting] surfaces only AWAITING rows.
 *
 * Uses real in-memory Room — same pattern as [MeFeedRepositoryTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeFeedApprovalFilterTest {

    private lateinit var db: AppDatabase
    private lateinit var subs: SubscriptionRepository
    private lateinit var favs: FavoritesRepositoryImpl
    private lateinit var repo: MeFeedRepository

    @Before
    fun setUp() {
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java,
        ).allowMainThreadQueries().build()

        val fakeAccount = FakeAccountRepository()

        subs = SubscriptionRepository(
            db = db,
            channels = db.subscribedChannelDao(),
            playlists = db.savedPlaylistDao(),
            cache = db.channelVideoCacheDao(),
            refreshState = db.channelFeedRefreshStateDao(),
            accountRepository = fakeAccount,
            syncManager = mock(),
            playlistLinks = db.playlistVideoLinkDao(),
        )

        favs = FavoritesRepositoryImpl(
            favoriteVideoDao = db.favoriteVideoDao(),
            accountRepository = fakeAccount,
            syncManager = mock(),
        )

        repo = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
            favoritesRepository = favs,
        )
    }

    @After
    fun tearDown() {
        db.close()
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private suspend fun insertChannel(id: String, status: String) {
        db.subscribedChannelDao().upsert(
            SubscribedChannel(
                channelId = id,
                channelUrl = "https://yt/$id",
                name = "name-$id",
                avatarUrl = null,
                user_id = "",
                approvalStatus = status,
            )
        )
    }

    private suspend fun insertPlaylist(id: String, status: String) {
        db.savedPlaylistDao().upsert(
            SavedPlaylist(
                playlistId = id,
                playlistUrl = "https://yt/playlist?list=$id",
                name = "playlist-$id",
                thumbnailUrl = null,
                uploaderName = null,
                user_id = "",
                approvalStatus = status,
            )
        )
    }

    private suspend fun insertFavorite(id: String, status: String) {
        db.favoriteVideoDao().upsertFavorite(
            FavoriteVideo(
                videoId = id,
                title = "title-$id",
                channelName = "channel",
                thumbnailUrl = null,
                durationSeconds = 60,
                user_id = "",
                approvalStatus = status,
            )
        )
    }

    // ── Feed-composition: approved channels ──────────────────────────────────

    @Test
    fun `observeApprovedSubscribedChannels excludes AWAITING channels`() = runBlocking {
        insertChannel("CH_approved", "APPROVED")
        insertChannel("CH_awaiting", "AWAITING")

        val result = subs.observeApprovedSubscribedChannels().first()

        assertEquals(1, result.size)
        assertEquals("CH_approved", result.single().channelId)
    }

    @Test
    fun `getApprovedSubscribedChannels one-shot excludes AWAITING channels`() = runBlocking {
        insertChannel("CH_approved", "APPROVED")
        insertChannel("CH_awaiting", "AWAITING")

        val result = subs.getApprovedSubscribedChannels()

        assertEquals(1, result.size)
        assertEquals("CH_approved", result.single().channelId)
    }

    // ── Feed-composition: approved playlists ─────────────────────────────────

    @Test
    fun `observeApprovedSavedPlaylists excludes AWAITING playlists`() = runBlocking {
        insertPlaylist("PL_approved", "APPROVED")
        insertPlaylist("PL_awaiting", "AWAITING")

        val result = subs.observeApprovedSavedPlaylists().first()

        assertEquals(1, result.size)
        assertEquals("PL_approved", result.single().playlistId)
    }

    @Test
    fun `getApprovedSavedPlaylists one-shot excludes AWAITING playlists`() = runBlocking {
        insertPlaylist("PL_approved", "APPROVED")
        insertPlaylist("PL_awaiting", "AWAITING")

        val result = subs.getApprovedSavedPlaylists()

        assertEquals(1, result.size)
        assertEquals("PL_approved", result.single().playlistId)
    }

    // ── Feed-composition: approved favorites ─────────────────────────────────

    @Test
    fun `observeApprovedFavorites excludes AWAITING videos`() = runBlocking {
        insertFavorite("VID_approved", "APPROVED")
        insertFavorite("VID_awaiting", "AWAITING")

        val result = favs.observeApprovedFavorites().first()

        assertEquals(1, result.size)
        assertEquals("VID_approved", result.single().videoId)
    }

    @Test
    fun `AWAITING favorite excluded from feed favorites row, APPROVED favorite included`() = runBlocking {
        insertFavorite("VID_approved", "APPROVED")
        insertFavorite("VID_awaiting", "AWAITING")

        // observeApprovedFavorites (used by MeViewModel.state) must exclude AWAITING
        val approved = favs.observeApprovedFavorites().first()
        assertEquals(1, approved.size)
        assertEquals("VID_approved", approved.single().videoId)

        // observeAwaiting.videos (used by MeViewModel.awaiting) must include AWAITING
        val awaiting = repo.observeAwaiting().first()
        assertEquals(1, awaiting.videos.size)
        assertEquals("VID_awaiting", awaiting.videos.single().videoId)
    }

        // ── observeAwaiting combines all three awaiting streams ──────────────────

    @Test
    fun `observeAwaiting emits AWAITING channels playlists and videos`() = runBlocking {
        // Insert mixed rows for each type
        insertChannel("CH_approved", "APPROVED")
        insertChannel("CH_awaiting_1", "AWAITING")
        insertChannel("CH_awaiting_2", "AWAITING")

        insertPlaylist("PL_approved", "APPROVED")
        insertPlaylist("PL_awaiting", "AWAITING")

        insertFavorite("VID_approved", "APPROVED")
        insertFavorite("VID_awaiting", "AWAITING")

        val awaiting = repo.observeAwaiting().first()

        assertEquals(
            "channels: got ${awaiting.channels.map { it.channelId }}",
            2, awaiting.channels.size,
        )
        assertTrue(awaiting.channels.all { it.approvalStatus == "AWAITING" })

        assertEquals(
            "playlists: got ${awaiting.playlists.map { it.playlistId }}",
            1, awaiting.playlists.size,
        )
        assertTrue(awaiting.playlists.all { it.approvalStatus == "AWAITING" })

        assertEquals(
            "videos: got ${awaiting.videos.map { it.videoId }}",
            1, awaiting.videos.size,
        )
        assertTrue(awaiting.videos.all { it.approvalStatus == "AWAITING" })
    }

    @Test
    fun `observeAwaiting emits empty when no AWAITING rows exist`() = runBlocking {
        insertChannel("CH_approved", "APPROVED")
        insertPlaylist("PL_approved", "APPROVED")
        insertFavorite("VID_approved", "APPROVED")

        val awaiting = repo.observeAwaiting().first()

        assertTrue(awaiting.channels.isEmpty())
        assertTrue(awaiting.playlists.isEmpty())
        assertTrue(awaiting.videos.isEmpty())
    }

    @Test
    fun `observeAwaiting emits empty when repository has no rows at all`() = runBlocking {
        val awaiting = repo.observeAwaiting().first()

        assertTrue(awaiting.channels.isEmpty())
        assertTrue(awaiting.playlists.isEmpty())
        assertTrue(awaiting.videos.isEmpty())
    }

    // ── Sync paths are NOT filtered: all rows visible ────────────────────────

    @Test
    fun `all subscribed channels visible via unfiltered getSubscribedChannels`() = runBlocking {
        insertChannel("CH_approved", "APPROVED")
        insertChannel("CH_awaiting", "AWAITING")

        val result = subs.getSubscribedChannels()

        assertEquals(2, result.size)
    }

    @Test
    fun `all saved playlists visible via unfiltered getSavedPlaylists`() = runBlocking {
        insertPlaylist("PL_approved", "APPROVED")
        insertPlaylist("PL_awaiting", "AWAITING")

        val result = subs.getSavedPlaylists()

        assertEquals(2, result.size)
    }

    @Test
    fun `all favorites visible via unfiltered getAllFavorites`() = runBlocking {
        insertFavorite("VID_approved", "APPROVED")
        insertFavorite("VID_awaiting", "AWAITING")

        val result = favs.getAllFavorites().first()

        assertEquals(2, result.size)
    }

    // ── Fake dependencies ────────────────────────────────────────────────────

    private object NoopFetcher : ChannelFeedFetcher {
        override suspend fun fetchLatest(
            channelUrl: String,
            priorEtag: String?,
            priorLastModified: String?,
        ): ChannelFeedFetcher.FetchResult = ChannelFeedFetcher.FetchResult.Items(
            items = emptyList(),
            etag = null,
            lastModified = null,
        )
    }

    private class FakeAccountRepository : AccountRepository {
        override val accountState: StateFlow<AccountState> =
            kotlinx.coroutines.flow.MutableStateFlow(AccountState.NotSignedIn)

        override suspend fun fetchMe() =
            Result.failure<AccountState.Loaded>(RuntimeException("stub"))

        override suspend fun completeProfile(
            displayName: String,
            dateOfBirth: LocalDate,
            phoneNumber: String,
        ) = Result.failure<AccountState.Loaded>(RuntimeException("stub"))

        override fun signOut() {}

        override fun applyProfileUpdate(
            response: com.albunyaan.tube.data.account.AccountMeResponseDto,
        ) {}
    }
}
