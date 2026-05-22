package com.albunyaan.tube.ui.me

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.auth.AccountRepository
import com.albunyaan.tube.auth.AccountState
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChannelFeedFetcher
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.me.MeRefreshTelemetry
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import kotlinx.coroutines.withTimeout
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
 * Tests MeViewModel via real-time Room so the Room InvalidationTracker's
 * internal executor can actually deliver emissions (virtual-time / TestDispatcher
 * causes Room flows to hang because Room doesn't dispatch on the test scheduler).
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [31])
class MeViewModelTest {

    private lateinit var db: AppDatabase
    private lateinit var subs: SubscriptionRepository
    private lateinit var feed: MeFeedRepository
    private lateinit var favs: NoopFavoritesRepository

    @Before
    fun setUp() {
        Dispatchers.setMain(Dispatchers.Unconfined)
        db = Room.inMemoryDatabaseBuilder(
            ApplicationProvider.getApplicationContext(),
            AppDatabase::class.java
        ).allowMainThreadQueries().build()
        subs = SubscriptionRepository(
            db = db,
            channels = db.subscribedChannelDao(),
            playlists = db.savedPlaylistDao(),
            cache = db.channelVideoCacheDao(),
            refreshState = db.channelFeedRefreshStateDao(),
            accountRepository = FakeAccountRepository(),
            syncManager = mock(),
            playlistLinks = db.playlistVideoLinkDao(),
        )
        feed = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        favs = NoopFavoritesRepository()
    }

    @After
    fun tearDown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `empty subscriptions yields Empty state`() = runBlocking {
        val vm = MeViewModel(subs, feed, favs, FakeAccountRepository())
        val state = withTimeout(3_000L) { vm.state.first { it !is MeFeedState.Loading } }
        assertTrue("expected Empty, got $state", state is MeFeedState.Empty)
    }

    @Test
    fun `chips include subscribed channels`() = runBlocking {
        subs.subscribe(SubscribedChannel("UC1", "https://yt/UC1", "A", null, 1_000L))
        val vm = MeViewModel(subs, feed, favs, FakeAccountRepository())
        val content = withTimeout(3_000L) {
            vm.state.first { it is MeFeedState.Content } as MeFeedState.Content
        }
        assertEquals(1, content.chips.size)
        assertTrue(content.chips.first() is ChipItem.Channel)
        assertEquals("UC1", content.chips.first().id)
    }

    @Test
    fun `setFilter scopes videos to selected channel`() = runBlocking {
        val now = System.currentTimeMillis()
        val recent = now - (2L * 24L * 60L * 60L * 1_000L)
        // T9: the view-model is now cache-only — no init-triggered refresh.
        // The test pre-populates the cache via an explicit feed.refresh()
        // call before constructing the VM, matching the real-world
        // worker-driven flow.
        val feedWithItems = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = when (channelUrl) {
                        "u1" -> listOf(item("v1", recent))
                        "u2" -> listOf(item("v2", recent))
                        else -> emptyList()
                    }
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))

        // T9: pre-populate cache (replaces the prior init-triggered refresh).
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        val unfiltered = withTimeout(3_000L) {
            vm.state.first { it is MeFeedState.Content && it.videos.size == 2 }
        } as MeFeedState.Content
        assertEquals(2, unfiltered.videos.size)

        vm.setFilter("UC1")
        val filtered = withTimeout(3_000L) {
            vm.state.first { it is MeFeedState.Content && it.filterChannelId == "UC1" }
        } as MeFeedState.Content
        assertEquals(1, filtered.videos.size)
        assertEquals("v1", filtered.videos.first().videoId)
    }

    // ANDROID-PERSONAL-03 / T5: weeks state.

    @Test
    fun `T5 init triggers first week load`() = runBlocking {
        val now = System.currentTimeMillis()
        val recent = now - (2L * 24L * 60L * 60L * 1_000L)
        val feedWithItems = repoWithFetcher(::singleItem to "u1", recent)
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        val first = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() }
        }
        assertEquals(1, first.size)
        assertEquals(0, first[0].weekIndex)
        assertEquals(listOf("v1"), first[0].videos.map { it.videoId })
    }

    @Test
    fun `T5 loadNextWeek skips empty weeks and appends next non-empty`() = runBlocking {
        val now = System.currentTimeMillis()
        // Week 0 (now-7d to now): empty.
        // Week 1: empty.
        // Week 2: one item at ~17 days ago.
        val sixteenDaysAgo = now - 16L * 24L * 60L * 60L * 1_000L
        val feedWithItems = repoWithFetcher(::singleItem to "u1", sixteenDaysAgo)
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        // First load: should jump to week 2 (skipping empty 0 and 1).
        val first = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() }
        }
        assertEquals(1, first.size)
        assertEquals(2, first[0].weekIndex)
        assertEquals(listOf("v1"), first[0].videos.map { it.videoId })
    }

    @Test
    fun `T5 loadNextWeek appends weeks one at a time`() = runBlocking {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1_000L
        val nineDaysAgo = now - 9L * 24L * 60L * 60L * 1_000L
        // One channel; two items in different weeks.
        val feedWithItems = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = if (channelUrl == "u1") listOf(
                        item("v0", twoDaysAgo),
                        item("v1", nineDaysAgo),
                    ) else emptyList()
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        val firstLoad = withTimeout(3_000L) { vm.weeks.first { it.isNotEmpty() } }
        assertEquals(1, firstLoad.size)
        assertEquals(0, firstLoad[0].weekIndex)
        assertEquals(listOf("v0"), firstLoad[0].videos.map { it.videoId })

        // Wait for the init's loadNextWeek() to settle, then trigger the
        // second load explicitly.
        withTimeout(3_000L) { vm.isLoadingMoreWeeks.first { !it } }
        vm.loadNextWeek()

        val secondLoad = withTimeout(3_000L) { vm.weeks.first { it.size == 2 } }
        assertEquals(0, secondLoad[0].weekIndex)
        assertEquals(1, secondLoad[1].weekIndex)
        assertEquals(listOf("v1"), secondLoad[1].videos.map { it.videoId })
    }

    // ANDROID-PERSONAL-03 / Bug 1: filter scopes weeks to a single channel.

    @Test
    fun `Bug 1 setFilter restricts each rendered week to that channel`() = runBlocking {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1_000L
        // Two channels, both with one item in the same week (week 0).
        val feedWithItems = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = when (channelUrl) {
                        "u1" -> listOf(item("v1", twoDaysAgo))
                        "u2" -> listOf(item("v2", twoDaysAgo))
                        else -> emptyList()
                    }
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        // Initial load: both videos visible in week 0.
        val unfiltered = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() && it[0].videos.size == 2 }
        }
        assertEquals(0, unfiltered[0].weekIndex)
        assertEquals(setOf("v1", "v2"), unfiltered[0].videos.map { it.videoId }.toSet())

        // Apply filter for UC1: only v1 should appear.
        vm.setFilter("UC1")
        val filtered = withTimeout(3_000L) {
            vm.weeks.first { it.size == 1 && it[0].videos.size == 1 }
        }
        assertEquals(0, filtered[0].weekIndex)
        assertEquals(listOf("v1"), filtered[0].videos.map { it.videoId })
    }

    @Test
    fun `Bug 1 clearing filter restores all-channels view`() = runBlocking {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1_000L
        val feedWithItems = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = when (channelUrl) {
                        "u1" -> listOf(item("v1", twoDaysAgo))
                        "u2" -> listOf(item("v2", twoDaysAgo))
                        else -> emptyList()
                    }
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())
        // wait initial load
        withTimeout(3_000L) { vm.weeks.first { it.isNotEmpty() } }

        vm.setFilter("UC1")
        withTimeout(3_000L) { vm.weeks.first { it.isNotEmpty() && it[0].videos.size == 1 } }

        vm.setFilter(null)
        val cleared = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() && it[0].videos.size == 2 }
        }
        assertEquals(setOf("v1", "v2"), cleared[0].videos.map { it.videoId }.toSet())
    }

    @Test
    fun `Bug 1 filter change triggers fresh load from week 0`() = runBlocking {
        val now = System.currentTimeMillis()
        val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1_000L
        val tenDaysAgo = now - 10L * 24L * 60L * 60L * 1_000L
        // UC1 has its only item in week 1 (~10 days ago).
        // UC2 has items in week 0 (~2 days ago).
        val feedWithItems = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = when (channelUrl) {
                        "u1" -> listOf(item("v1_old", tenDaysAgo))
                        "u2" -> listOf(item("v2", twoDaysAgo))
                        else -> emptyList()
                    }
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

        // Initial load (no filter): finds UC2's item in week 0.
        val unfiltered = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() }
        }
        assertEquals(0, unfiltered[0].weekIndex)
        assertEquals(listOf("v2"), unfiltered[0].videos.map { it.videoId })

        // Switch filter to UC1 (no items in week 0). The view should reset
        // and rediscover week 1 (UC1's only week with content). Without the
        // filter-change reset, the previously-loaded weekIndex 0 would
        // still be in loadedWeekIndices and the new load would never find
        // UC1's content.
        vm.setFilter("UC1")
        val filtered = withTimeout(3_000L) {
            vm.weeks.first { it.isNotEmpty() && it[0].videos.firstOrNull()?.videoId == "v1_old" }
        }
        assertEquals(1, filtered[0].weekIndex)
        assertEquals(listOf("v1_old"), filtered[0].videos.map { it.videoId })
    }

    // ANDROID-PERSONAL-03 / Bug 2: cache mutations propagate to rendered weeks.

    @Test
    fun `Bug 2 newly-cached row appears in already-rendered week without re-entry`() =
        runBlocking {
            val now = System.currentTimeMillis()
            val twoDaysAgo = now - 2L * 24L * 60L * 60L * 1_000L
            // Mutable holder so we can mutate the fetcher response after the
            // VM has already rendered week 0.
            val responses = mutableMapOf<String, List<ChannelFeedFetcher.ChannelFeedItem>>(
                "u1" to listOf(item("v1", twoDaysAgo)),
                "u2" to emptyList(),
            )
            val feedWithItems = MeFeedRepository(
                subscriptions = subs,
                cache = db.channelVideoCacheDao(),
                refreshStateDao = db.channelFeedRefreshStateDao(),
                fetcher = object : ChannelFeedFetcher {
                    override suspend fun fetchLatest(
                        channelUrl: String,
                        priorEtag: String?,
                        priorLastModified: String?,
                    ): ChannelFeedFetcher.FetchResult =
                        ChannelFeedFetcher.FetchResult.Items(
                            responses[channelUrl] ?: emptyList(),
                            null,
                            null,
                        )
                },
                ioDispatcher = Dispatchers.Unconfined,
                telemetry = MeRefreshTelemetry(),
            )
            subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
            subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))
            feedWithItems.refresh(force = true)

            val vm = MeViewModel(subs, feedWithItems, favs, FakeAccountRepository())

            // Wait until VM has loaded week 0 with v1.
            val initial = withTimeout(3_000L) {
                vm.weeks.first { it.isNotEmpty() && it[0].videos.size == 1 }
            }
            assertEquals(listOf("v1"), initial[0].videos.map { it.videoId })

            // Simulate a worker upserting a new row into the cache for UC2 —
            // representative of a newly-subscribed channel's items arriving
            // after the user has already opened the Me tab.
            responses["u2"] = listOf(item("v2", twoDaysAgo))
            feedWithItems.refresh(force = true)

            // The already-rendered week 0 must now reflect v2 too, with no
            // explicit loadNextWeek / fragment re-entry.
            val updated = withTimeout(3_000L) {
                vm.weeks.first { it.isNotEmpty() && it[0].videos.size == 2 }
            }
            assertEquals(setOf("v1", "v2"), updated[0].videos.map { it.videoId }.toSet())
        }

    /** Helper: build a real MeFeedRepository whose fetcher returns one
     *  item for a single channel URL. */
    private fun repoWithFetcher(
        urlAndItemFn: Pair<(Long) -> ChannelFeedFetcher.ChannelFeedItem, String>,
        uploadedAt: Long,
    ): MeFeedRepository {
        val (factory, url) = urlAndItemFn
        return MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = object : ChannelFeedFetcher {
                override suspend fun fetchLatest(
                    channelUrl: String,
                    priorEtag: String?,
                    priorLastModified: String?,
                ): ChannelFeedFetcher.FetchResult {
                    val items = if (channelUrl == url) listOf(factory(uploadedAt)) else emptyList()
                    return ChannelFeedFetcher.FetchResult.Items(items, null, null)
                }
            },
            ioDispatcher = Dispatchers.Unconfined,
            telemetry = MeRefreshTelemetry(),
        )
    }

    private fun singleItem(uploadedAt: Long) = ChannelFeedFetcher.ChannelFeedItem(
        videoId = "v1",
        title = "title",
        thumbnailUrl = null,
        durationSeconds = null,
        viewCount = null,
        uploadedAt = uploadedAt,
        isShort = false,
    )

    private fun item(id: String, uploadedAt: Long) = ChannelFeedFetcher.ChannelFeedItem(
        videoId = id,
        title = "title-$id",
        thumbnailUrl = null,
        durationSeconds = null,
        viewCount = null,
        uploadedAt = uploadedAt,
        isShort = false,
    )

    private object NoopFetcher : ChannelFeedFetcher {
        override suspend fun fetchLatest(
            channelUrl: String,
            priorEtag: String?,
            priorLastModified: String?,
        ): ChannelFeedFetcher.FetchResult =
            ChannelFeedFetcher.FetchResult.Items(emptyList(), null, null)
    }

    /**
     * Test double for the favorites repository. Backed by an in-memory
     * MutableStateFlow so tests can stage state via [emit]. The empty
     * default keeps existing tests behaving as before T10.
     */
    private class NoopFavoritesRepository : FavoritesRepository {
        private val state = MutableStateFlow<List<FavoriteVideo>>(emptyList())

        fun emit(list: List<FavoriteVideo>) { state.value = list }

        override fun getAllFavorites(): Flow<List<FavoriteVideo>> = state
        override fun isFavorite(videoId: String): Flow<Boolean> =
            state.map { list -> list.any { it.videoId == videoId } }
        override suspend fun isFavoriteOnce(videoId: String): Boolean =
            state.value.any { it.videoId == videoId }
        override suspend fun addFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ) {
            state.value = state.value.filter { it.videoId != videoId } + FavoriteVideo(
                videoId, title, channelName, thumbnailUrl, durationSeconds,
            )
        }
        override suspend fun removeFavorite(videoId: String) {
            state.value = state.value.filter { it.videoId != videoId }
        }
        override suspend fun toggleFavorite(
            videoId: String, title: String, channelName: String,
            thumbnailUrl: String?, durationSeconds: Int,
        ): Boolean {
            val isFav = isFavoriteOnce(videoId)
            if (isFav) removeFavorite(videoId)
            else addFavorite(videoId, title, channelName, thumbnailUrl, durationSeconds)
            return !isFav
        }
        override fun getFavoriteCount(): Flow<Int> = state.map { it.size }
        override suspend fun clearAll() { state.value = emptyList() }
    }

    private class FakeAccountRepository : AccountRepository {
        override val accountState: StateFlow<AccountState> =
            MutableStateFlow(AccountState.NotSignedIn)
        override suspend fun fetchMe() = Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override suspend fun completeProfile(displayName: String, dateOfBirth: LocalDate) =
            Result.failure<AccountState.Loaded>(RuntimeException("stub"))
        override fun signOut() {}
        override fun applyProfileUpdate(response: com.albunyaan.tube.data.account.AccountMeResponseDto) {}
    }
}
