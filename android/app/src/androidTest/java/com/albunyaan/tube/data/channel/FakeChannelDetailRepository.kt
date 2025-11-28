package com.albunyaan.tube.data.channel

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay
import java.time.Instant

/**
 * Fake ChannelDetailRepository for UI testing.
 *
 * Tests can configure responses by setting the headerToReturn property
 * before launching the fragment.
 *
 * ## Controllable Loading
 *
 * For testing loading states without flakiness, use the deferred loading mode:
 * ```kotlin
 * repository.useDeferredLoading = true
 * launchFragment()
 * // Assert loading state is visible
 * onView(withId(R.id.headerSkeleton)).check(matches(isDisplayed()))
 * // Release the loading
 * repository.completeHeaderLoading()
 * // Assert loaded state
 * onView(withId(R.id.channelNameText)).check(matches(isDisplayed()))
 * ```
 */
class FakeChannelDetailRepository : ChannelDetailRepository {

    var headerToReturn: ChannelHeader = createDefaultHeader()
    var errorToThrow: Exception? = null
    var videosToReturn: List<ChannelVideo> = emptyList()
    var liveStreamsToReturn: List<ChannelLiveStream> = emptyList()
    var shortsToReturn: List<ChannelShort> = emptyList()
    var playlistsToReturn: List<ChannelPlaylist> = emptyList()

    /**
     * When true, header loading will wait until [completeHeaderLoading] is called.
     * This allows tests to reliably assert on loading states.
     */
    var useDeferredLoading: Boolean = false

    /**
     * Optional delay to simulate network latency (in milliseconds).
     * Set to 0 for immediate responses (default).
     * This is different from [useDeferredLoading] - this adds a fixed delay,
     * while deferred loading waits for explicit completion.
     */
    var simulatedDelayMs: Long = 0

    private var headerLoadingDeferred: CompletableDeferred<Unit>? = null
    private var videosLoadingDeferred: CompletableDeferred<Unit>? = null

    /**
     * Called to track if header loading has started (for test verification).
     */
    var headerLoadingStarted: Boolean = false
        private set

    fun reset() {
        headerToReturn = createDefaultHeader()
        errorToThrow = null
        videosToReturn = emptyList()
        liveStreamsToReturn = emptyList()
        shortsToReturn = emptyList()
        playlistsToReturn = emptyList()
        useDeferredLoading = false
        simulatedDelayMs = 0
        headerLoadingDeferred = null
        videosLoadingDeferred = null
        headerLoadingStarted = false
    }

    /**
     * Call this to release a pending header load when [useDeferredLoading] is true.
     */
    fun completeHeaderLoading() {
        headerLoadingDeferred?.complete(Unit)
    }

    /**
     * Call this to release a pending videos load when [useDeferredLoading] is true.
     */
    fun completeVideosLoading() {
        videosLoadingDeferred?.complete(Unit)
    }

    override suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean): ChannelHeader {
        headerLoadingStarted = true

        if (useDeferredLoading) {
            headerLoadingDeferred = CompletableDeferred()
            headerLoadingDeferred?.await()
        } else if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        errorToThrow?.let { throw it }
        return headerToReturn
    }

    override suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo> {
        if (useDeferredLoading) {
            videosLoadingDeferred = CompletableDeferred()
            videosLoadingDeferred?.await()
        } else if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        errorToThrow?.let { throw it }
        return ChannelPage(items = videosToReturn, nextPage = null)
    }

    override suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream> {
        errorToThrow?.let { throw it }
        return ChannelPage(items = liveStreamsToReturn, nextPage = null)
    }

    override suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort> {
        errorToThrow?.let { throw it }
        return ChannelPage(items = shortsToReturn, nextPage = null)
    }

    override suspend fun getPlaylists(channelId: String, page: Page?): ChannelPage<ChannelPlaylist> {
        errorToThrow?.let { throw it }
        return ChannelPage(items = playlistsToReturn, nextPage = null)
    }

    override suspend fun getAbout(channelId: String, forceRefresh: Boolean): ChannelHeader {
        errorToThrow?.let { throw it }
        return headerToReturn
    }

    companion object {
        fun createDefaultHeader(
            id: String = "UC_test_channel",
            title: String = "Test Channel",
            isVerified: Boolean = false,
            subscriberCount: Long? = 1000000L,
            avatarUrl: String? = null,
            bannerUrl: String? = null,
            shortDescription: String? = "A test channel for UI testing",
            summaryLine: String? = null,
            fullDescription: String? = "Full description of the test channel",
            links: List<ChannelLink> = emptyList(),
            location: String? = null,
            joinedDate: Instant? = null,
            totalViews: Long? = null,
            tags: List<String> = emptyList()
        ) = ChannelHeader(
            id = id,
            title = title,
            avatarUrl = avatarUrl,
            bannerUrl = bannerUrl,
            subscriberCount = subscriberCount,
            shortDescription = shortDescription,
            summaryLine = summaryLine,
            fullDescription = fullDescription,
            links = links,
            location = location,
            joinedDate = joinedDate,
            totalViews = totalViews,
            isVerified = isVerified,
            tags = tags
        )
    }
}
