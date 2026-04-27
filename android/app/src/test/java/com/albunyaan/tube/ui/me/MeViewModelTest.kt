package com.albunyaan.tube.ui.me

import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.albunyaan.tube.data.local.AppDatabase
import com.albunyaan.tube.data.local.FavoriteVideo
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.SubscribedChannel
import com.albunyaan.tube.data.me.ChannelFeedFetcher
import com.albunyaan.tube.data.me.ChipItem
import com.albunyaan.tube.data.me.MeFeedRepository
import com.albunyaan.tube.data.me.MeFeedState
import com.albunyaan.tube.data.subscriptions.SubscriptionRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
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
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

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
        )
        feed = MeFeedRepository(
            subscriptions = subs,
            cache = db.channelVideoCacheDao(),
            refreshStateDao = db.channelFeedRefreshStateDao(),
            fetcher = NoopFetcher,
            ioDispatcher = Dispatchers.Unconfined,
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
        val vm = MeViewModel(subs, feed, favs)
        val state = withTimeout(3_000L) { vm.state.first { it !is MeFeedState.Loading } }
        assertTrue("expected Empty, got $state", state is MeFeedState.Empty)
    }

    @Test
    fun `chips include subscribed channels`() = runBlocking {
        subs.subscribe(SubscribedChannel("UC1", "https://yt/UC1", "A", null, 1_000L))
        val vm = MeViewModel(subs, feed, favs)
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
        )
        subs.subscribe(SubscribedChannel("UC1", "u1", "A", null, 1_000L))
        subs.subscribe(SubscribedChannel("UC2", "u2", "B", null, 2_000L))

        // T9: pre-populate cache (replaces the prior init-triggered refresh).
        feedWithItems.refresh(force = true)

        val vm = MeViewModel(subs, feedWithItems, favs)

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
}
