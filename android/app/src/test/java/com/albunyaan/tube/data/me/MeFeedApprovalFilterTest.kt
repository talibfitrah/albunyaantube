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
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.mockito.kotlin.doSuspendableAnswer
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

    // ── Owner bug 2026-11-03: dashboard exclusions ───────────────────────────

    @Test
    fun `the Me feed drops what the dashboard excluded from a subscribed channel or saved playlist`() = runBlocking {
        val channel = "UCaaaaaaaaaaaaaaaaaaaaaa" // subscribed
        val other = "UCbbbbbbbbbbbbbbbbbbbbbb" // not subscribed: its videos come only through the saved playlist
        insertChannel(channel, "APPROVED")
        insertPlaylist("PLsaved", "APPROVED")
        val now = System.currentTimeMillis()
        fun row(id: String, of: String = channel) = com.albunyaan.tube.data.local.ChannelVideoCache(
            videoId = id, channelId = of, channelName = "Shaykh", title = "Lesson $id", thumbnailUrl = null,
            durationSeconds = 600, viewCount = null, uploadedAt = now - 60_000L, isShort = false, fetchedAt = now,
        )
        db.channelVideoCacheDao().upsertAll(
            listOf(row("lesson00001"), row("lesson00002"), row("lesson00003"), row("lesson00004", other), row("lesson00005", other))
        )
        db.playlistVideoLinkDao().upsertAll(
            listOf("lesson00002", "lesson00004", "lesson00005").map { com.albunyaan.tube.data.local.PlaylistVideoLink("PLsaved", it) }
        )
        val api: com.albunyaan.tube.data.source.api.ContentApi = mock()
        fun entry(vararg ids: String) =
            retrofit2.Response.success(com.albunyaan.tube.data.source.api.RegistryEntry(excludedVideoIds = ids.toList()))
        org.mockito.kotlin.whenever(api.getChannel(channel)).thenReturn(entry("lesson00001"))
        org.mockito.kotlin.whenever(api.getChannel(other)).thenReturn(entry())
        // lesson00002: excluded from the playlist, still approved in the subscribed channel, so it stays.
        org.mockito.kotlin.whenever(api.getPlaylist("PLsaved")).thenReturn(entry("lesson00002", "lesson00004"))
        val feed = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
            favoritesRepository = favs,
            exclusions = com.albunyaan.tube.data.source.Exclusions(
                api, { 0L }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            ),
        )

        assertEquals(
            setOf("lesson00002", "lesson00003", "lesson00005"),
            feed.observeWeek(0).first()!!.videos.map { it.videoId }.toSet(),
        )
        assertEquals(setOf("lesson00002", "lesson00003"), feed.observeFeed().first().map { it.videoId }.toSet())
    }

    @Test
    fun `an exclusion answer that lands after the feed is shown filters the feed on screen`() = runBlocking {
        val channel = "UCaaaaaaaaaaaaaaaaaaaaaa"
        insertChannel(channel, "APPROVED")
        val now = System.currentTimeMillis()
        db.channelVideoCacheDao().upsertAll(
            listOf("lesson00001", "lesson00002").map {
                com.albunyaan.tube.data.local.ChannelVideoCache(
                    videoId = it, channelId = channel, channelName = "Shaykh", title = "Lesson", thumbnailUrl = null,
                    durationSeconds = 600, viewCount = null, uploadedAt = now - 60_000L, isShort = false, fetchedAt = now,
                )
            }
        )
        val api: com.albunyaan.tube.data.source.api.ContentApi = mock()
        val backendAnswers = kotlinx.coroutines.CompletableDeferred<Unit>()
        org.mockito.kotlin.whenever(api.getChannel(channel)).doSuspendableAnswer {
            backendAnswers.await() // slower than the list's 2 s wait: the feed first shows fail-open
            retrofit2.Response.success(com.albunyaan.tube.data.source.api.RegistryEntry(excludedVideoIds = listOf("lesson00001")))
        }
        val feed = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
            favoritesRepository = favs,
            exclusions = com.albunyaan.tube.data.source.Exclusions(
                api, { 0L }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            ),
        )
        val shown = kotlinx.coroutines.flow.MutableStateFlow<Set<String>?>(null)
        val screen = launch { feed.observeFeed().collect { rows -> shown.value = rows.map { it.videoId }.toSet() } }

        kotlinx.coroutines.withTimeout(10_000) { shown.first { it == setOf("lesson00001", "lesson00002") } }
        backendAnswers.complete(Unit)
        kotlinx.coroutines.withTimeout(10_000) { shown.first { it == setOf("lesson00002") } }
        screen.cancel()
    }

    @Test
    fun `a week holding only excluded videos is not a week with content`() = runBlocking {
        val channel = "UCaaaaaaaaaaaaaaaaaaaaaa"
        insertChannel(channel, "APPROVED")
        val now = System.currentTimeMillis()
        db.channelVideoCacheDao().upsertAll(
            listOf(
                com.albunyaan.tube.data.local.ChannelVideoCache(
                    videoId = "lesson00001", channelId = channel, channelName = "Shaykh", title = "Lesson", thumbnailUrl = null,
                    durationSeconds = 600, viewCount = null, uploadedAt = now - 60_000L, isShort = false, fetchedAt = now,
                )
            )
        )
        val api: com.albunyaan.tube.data.source.api.ContentApi = mock()
        org.mockito.kotlin.whenever(api.getChannel(channel)).thenReturn(
            retrofit2.Response.success(com.albunyaan.tube.data.source.api.RegistryEntry(excludedVideoIds = listOf("lesson00001")))
        )
        val feed = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
            favoritesRepository = favs,
            exclusions = com.albunyaan.tube.data.source.Exclusions(
                api, { 0L }, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.SupervisorJob() + Dispatchers.Unconfined),
            ),
        )

        assertEquals(null, feed.observeWeek(0).first())
        assertEquals(null, feed.findNextNonEmptyWeekIndex(fromIndex = 0))
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
            phoneNumber: String?,
        ) = Result.failure<AccountState.Loaded>(RuntimeException("stub"))

        override fun signOut() {}

        override fun applyProfileUpdate(
            response: com.albunyaan.tube.data.account.AccountMeResponseDto,
        ) {}
    }
}
