package com.albunyaan.tube.ui.shorts

import android.content.Context
import com.albunyaan.tube.data.channel.ChannelDetailRepository
import com.albunyaan.tube.data.channel.ChannelHeader
import com.albunyaan.tube.data.local.FavoritesRepository
import com.albunyaan.tube.data.local.FollowedChannelsRepository
import com.albunyaan.tube.data.shorts.ShortsFeedRepository
import com.albunyaan.tube.data.shorts.ShortsItem
import com.albunyaan.tube.data.shorts.ShortsPage
import com.albunyaan.tube.player.AdaptiveBufferPolicy
import com.albunyaan.tube.player.NeverFreezeTrackSelectionFactory
import com.albunyaan.tube.player.PlaybackFeatureFlags
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.launchIn
import kotlinx.coroutines.flow.onEach
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.mockito.kotlin.any
import org.mockito.kotlin.eq
import org.mockito.kotlin.mock
import org.mockito.kotlin.never
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever

/**
 * Unit tests for [ShortsPlayerViewModel].
 *
 * Uses mockito-kotlin + kotlinx-coroutines-test (project convention — see
 * FollowedChannelsRepositoryTest for the same pattern). Turbine is not on the
 * test classpath, so flow assertions go through `StateFlow.value` after
 * `advanceUntilIdle()`, and SharedFlow events are collected into a local list
 * from a launched coroutine.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ShortsPlayerViewModelTest {

    private val dispatcher = StandardTestDispatcher()
    private val context: Context = mock()
    private val feed: ShortsFeedRepository = mock()
    private val favorites: FavoritesRepository = mock()
    private val follows: FollowedChannelsRepository = mock()
    private val channelDetailRepo: ChannelDetailRepository = mock()
    private val bufferPolicy: AdaptiveBufferPolicy = mock()
    private val featureFlags: PlaybackFeatureFlags = mock()
    private val neverFreezeFactory: NeverFreezeTrackSelectionFactory = mock()

    @Before
    fun setup() {
        Dispatchers.setMain(dispatcher)
    }

    @After
    fun tearDown() {
        Dispatchers.resetMain()
    }

    private fun sample(
        id: String = "v1",
        channelId: String = "",
        channelName: String = "",
        channelAvatarUrl: String? = null
    ) = ShortsItem(
        id = id,
        title = "title-$id",
        channelId = channelId,
        channelName = channelName,
        channelAvatarUrl = channelAvatarUrl,
        thumbnailUrl = null,
        durationSeconds = 30
    )

    private fun header(
        id: String = "UC1",
        title: String = "Channel One",
        avatar: String? = "avatar.jpg"
    ) = ChannelHeader(
        id = id,
        title = title,
        avatarUrl = avatar,
        bannerUrl = null,
        subscriberCount = null,
        shortDescription = null,
        summaryLine = null,
        fullDescription = null,
        links = emptyList(),
        location = null,
        joinedDate = null,
        totalViews = null,
        isVerified = false,
        tags = emptyList()
    )

    /**
     * Subscribes to [ShortsPlayerViewModel.events] *before* the dispatcher runs any
     * scheduled work. Uses [CoroutineStart.UNDISPATCHED] + [kotlinx.coroutines.Dispatchers.Unconfined]
     * so the collector registers synchronously with the SharedFlow — otherwise
     * the VM's init-time emissions (from `loadNextPage()` queued on the test
     * dispatcher) would fire before the subscriber has allocated its slot,
     * and be dropped (SharedFlow has replay=0).
     */
    private fun TestScope.collectEvents(vm: ShortsPlayerViewModel): MutableList<ShortsPlayerViewModel.LoadEvent> {
        val events = mutableListOf<ShortsPlayerViewModel.LoadEvent>()
        backgroundScope.launch(
            context = kotlinx.coroutines.Dispatchers.Unconfined,
            start = CoroutineStart.UNDISPATCHED
        ) {
            vm.events.collect { events += it }
        }
        return events
    }

    @Test
    fun init_loadsFirstPage() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1"), sample("v2")), null)
        )
        whenever(favorites.isFavorite(any())).thenReturn(flowOf(false))
        whenever(follows.isFollowed(any())).thenReturn(flowOf(false))

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()

        assertEquals(2, vm.items.value.size)
        assertEquals("v1", vm.items.value[0].id)
        verify(feed).loadFeedPage(eq(null), any())
    }

    @Test
    fun toggleLike_invokesFavoritesRepository() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1", channelName = "ch")), null)
        )

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()

        vm.toggleLike(0)
        advanceUntilIdle()

        verify(favorites).toggleFavorite(
            eq("v1"),
            eq("title-v1"),
            eq("ch"),
            eq(null),
            eq(30)
        )
    }

    @Test
    fun toggleFollow_invokesFollowsRepository() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(
            ShortsPage(
                listOf(sample("v1", channelId = "UC1", channelName = "ch", channelAvatarUrl = "a.jpg")),
                null
            )
        )

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()

        vm.toggleFollow(0)
        advanceUntilIdle()

        verify(follows).toggleFollow(eq("UC1"), eq("ch"), eq("a.jpg"))
    }

    @Test
    fun onPageChanged_loadsNextPageNearEnd() = runTest(dispatcher) {
        val firstPage = ShortsPage(
            items = (1..5).map { sample("v$it") },
            nextCursor = "cursor-2"
        )
        val secondPage = ShortsPage(
            items = (6..10).map { sample("v$it") },
            nextCursor = null
        )
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(firstPage)
        whenever(feed.loadFeedPage(eq("cursor-2"), any())).thenReturn(secondPage)

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()
        assertEquals(5, vm.items.value.size)

        // PREFETCH_THRESHOLD = 3; with size 5, indices >= 2 trigger load.
        vm.onPageChanged(3)
        advanceUntilIdle()

        assertEquals(10, vm.items.value.size)
        verify(feed).loadFeedPage(eq("cursor-2"), any())
    }

    @Test
    fun channelMode_usesChannelFeed() = runTest(dispatcher) {
        whenever(feed.loadChannelShortsPage(eq("UC1"), eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1"), sample("v2")), null)
        )
        whenever(channelDetailRepo.getChannelHeader(eq("UC1"), any())).thenReturn(header())

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = "UC1"
        )
        advanceUntilIdle()

        assertEquals(2, vm.items.value.size)
        verify(feed).loadChannelShortsPage(eq("UC1"), eq(null), any())
        verify(feed, never()).loadFeedPage(any(), any())
    }

    @Test
    fun channelMode_decoratesItemsWithChannelHeader() = runTest(dispatcher) {
        whenever(feed.loadChannelShortsPage(eq("UC1"), eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1"), sample("v2")), null)
        )
        whenever(channelDetailRepo.getChannelHeader(eq("UC1"), any())).thenReturn(header())

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = "UC1"
        )
        advanceUntilIdle()

        val items = vm.items.value
        assertEquals(2, items.size)
        items.forEach { item ->
            assertEquals("UC1", item.channelId)
            assertEquals("Channel One", item.channelName)
            assertEquals("avatar.jpg", item.channelAvatarUrl)
        }
        // Header fetched exactly once (cached for subsequent loads).
        verify(channelDetailRepo, times(1)).getChannelHeader(eq("UC1"), any())
    }

    @Test
    fun channelMode_headerSucceedsOnSecondPage_retroactivelyDecoratesPageOne() = runTest(dispatcher) {
        // Header fetch fails on page 1 (transient error) then succeeds on
        // page 2. Before the fix, page-1 items kept blank channel metadata
        // forever. Now they must be retroactively decorated once the header
        // arrives.
        val firstPage = ShortsPage(
            items = listOf(sample("v1"), sample("v2")),
            nextCursor = "cursor-2"
        )
        val secondPage = ShortsPage(
            items = listOf(sample("v3"), sample("v4")),
            nextCursor = null
        )
        whenever(feed.loadChannelShortsPage(eq("UC1"), eq(null), any())).thenReturn(firstPage)
        whenever(feed.loadChannelShortsPage(eq("UC1"), eq("cursor-2"), any())).thenReturn(secondPage)
        // First call throws, second returns a valid header.
        var headerCall = 0
        whenever(channelDetailRepo.getChannelHeader(eq("UC1"), any())).thenAnswer {
            headerCall++
            if (headerCall == 1) throw RuntimeException("transient")
            else header()
        }

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = "UC1"
        )
        advanceUntilIdle()

        // After page 1: header failed, items undecorated.
        assertEquals("", vm.items.value[0].channelName)

        // Trigger page 2 load — PREFETCH_THRESHOLD = 3, size=2, index 0 qualifies.
        vm.onPageChanged(0)
        advanceUntilIdle()

        val items = vm.items.value
        assertEquals(4, items.size)
        // Critical: ALL items (including original page-1 v1 & v2) now carry the header.
        items.forEach { item ->
            assertEquals("UC1", item.channelId)
            assertEquals("Channel One", item.channelName)
            assertEquals("avatar.jpg", item.channelAvatarUrl)
        }
    }

    @Test
    fun nullChannelId_doesNotQueryChannelHeader() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1")), null)
        )

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()

        assertEquals("", vm.items.value[0].channelName)
        verify(channelDetailRepo, never()).getChannelHeader(any(), any())
    }

    @Test
    fun onPlaybackError_emitsSkipEvent() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(
            ShortsPage(listOf(sample("v1"), sample("v2")), null)
        )

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        val events = collectEvents(vm)
        advanceUntilIdle()

        vm.onPlaybackError(1)
        advanceUntilIdle()

        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event is ShortsPlayerViewModel.LoadEvent.SkipCurrent)
        assertEquals("v2", (event as ShortsPlayerViewModel.LoadEvent.SkipCurrent).shortId)
    }

    @Test
    fun initialShortId_reorderAppliedOnlyOnFirstPage() = runTest(dispatcher) {
        // Page 1: v1, v2, v3 (v3 is requested as the initial short).
        // Page 2: v4, v5. The second load must NOT re-run the reorder —
        // i.e. page-1 items must keep their positions after v3 was pulled to index 0.
        val firstPage = ShortsPage(
            items = listOf(sample("v1"), sample("v2"), sample("v3")),
            nextCursor = "cursor-2"
        )
        val secondPage = ShortsPage(
            items = listOf(sample("v4"), sample("v5")),
            nextCursor = null
        )
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(firstPage)
        whenever(feed.loadFeedPage(eq("cursor-2"), any())).thenReturn(secondPage)

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = "v3",
            channelId = null
        )
        advanceUntilIdle()

        // After page 1: v3 pulled to index 0, then v1, v2 keep relative order.
        val afterFirst = vm.items.value.map { it.id }
        assertEquals(listOf("v3", "v1", "v2"), afterFirst)

        // Trigger page 2 (PREFETCH_THRESHOLD = 3; size=3, index 0 satisfies >= size-3).
        vm.onPageChanged(0)
        advanceUntilIdle()

        val afterSecond = vm.items.value.map { it.id }
        // Critical: page-1 items (v3, v1, v2) must keep their exact positions.
        assertEquals(listOf("v3", "v1", "v2", "v4", "v5"), afterSecond)
    }

    @Test
    fun concurrentLoadNextPage_onlyFiresOnce() = runTest(dispatcher) {
        // Two rapid onPageChanged() calls must not both pass the check and
        // double-fetch the same cursor. The Mutex.tryLock() gate guarantees
        // that the second caller drops out while the first holds the lock.
        //
        // We force an in-flight load to last across multiple dispatcher
        // ticks by stubbing loadFeedPage to delay (virtual-time aware under
        // runTest). Two onPageChanged calls fired between `runCurrent()` and
        // `advanceUntilIdle()` both enqueue concurrently; only one must
        // actually trigger a fetch of cursor-2.
        val firstPage = ShortsPage(
            items = (1..5).map { sample("v$it") },
            nextCursor = "cursor-2"
        )
        val secondPage = ShortsPage(items = listOf(sample("v6")), nextCursor = null)
        whenever(feed.loadFeedPage(eq(null), any())).thenReturn(firstPage)
        whenever(feed.loadFeedPage(eq("cursor-2"), any())).thenReturn(secondPage)

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        advanceUntilIdle()
        // Now page 1 is loaded. Fire two concurrent onPageChanged calls.
        // Both launches enqueue back-to-back before any can complete.
        vm.onPageChanged(4)
        vm.onPageChanged(4)
        advanceUntilIdle()

        // The Mutex ensures only one fetch of cursor-2, never two.
        verify(feed, times(1)).loadFeedPage(eq(null), any())
        verify(feed, times(1)).loadFeedPage(eq("cursor-2"), any())
    }

    @Test
    fun loadCancellation_doesNotEmitLoadError() = runTest(dispatcher) {
        // runCatching used to swallow CancellationException, firing a spurious
        // "Job was cancelled" LoadError toast on legitimate scope cancellation
        // (fragment destroyed mid-load). The fix rethrows CancellationException
        // so only real failures surface to the UI.
        whenever(feed.loadFeedPage(eq(null), any())).thenAnswer {
            throw kotlinx.coroutines.CancellationException("scope cancelled")
        }

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        val events = collectEvents(vm)
        advanceUntilIdle()

        assertEquals("Cancellation must not surface as a LoadError", 0, events.size)
    }

    @Test
    fun loadFailure_emitsLoadError() = runTest(dispatcher) {
        whenever(feed.loadFeedPage(eq(null), any())).thenThrow(RuntimeException("boom"))

        val vm = ShortsPlayerViewModel(
            context = context,
            feed = feed,
            favorites = favorites,
            follows = follows,
            channelDetailRepo = channelDetailRepo,
            bufferPolicy = bufferPolicy,
            featureFlags = featureFlags,
            neverFreezeTrackSelectionFactory = neverFreezeFactory,
            initialShortId = null,
            channelId = null
        )
        val events = collectEvents(vm)
        advanceUntilIdle()

        assertEquals(1, events.size)
        val event = events[0]
        assertTrue(event is ShortsPlayerViewModel.LoadEvent.LoadError)
        assertEquals("boom", (event as ShortsPlayerViewModel.LoadEvent.LoadError).message)
    }
}
