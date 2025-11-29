package com.albunyaan.tube.data.playlist

import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.download.DownloadPolicy
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.delay

/**
 * Fake PlaylistDetailRepository for UI testing.
 *
 * Tests can configure responses by setting the headerToReturn and itemsToReturn properties
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
 * onView(withId(R.id.playlistTitle)).check(matches(isDisplayed()))
 * ```
 */
class FakePlaylistDetailRepository : PlaylistDetailRepository {

    var headerToReturn: PlaylistHeader = createDefaultHeader()
    var itemsToReturn: List<PlaylistItem> = createDefaultItems(5)
    var nextPageToReturn: Page? = null
    var errorToThrow: Exception? = null

    /**
     * When true, loading will wait until [completeHeaderLoading] or [completeItemsLoading] is called.
     * This allows tests to reliably assert on loading states.
     */
    var useDeferredLoading: Boolean = false

    /**
     * Optional delay to simulate network latency (in milliseconds).
     * Set to 0 for immediate responses (default).
     */
    var simulatedDelayMs: Long = 0

    private var headerLoadingDeferred: CompletableDeferred<Unit>? = null
    private var itemsLoadingDeferred: CompletableDeferred<Unit>? = null

    /**
     * Called to track if header loading has started (for test verification).
     */
    var headerLoadingStarted: Boolean = false
        private set

    /**
     * Called to track if items loading has started (for test verification).
     */
    var itemsLoadingStarted: Boolean = false
        private set

    fun reset() {
        headerToReturn = createDefaultHeader()
        itemsToReturn = createDefaultItems(5)
        nextPageToReturn = null
        errorToThrow = null
        useDeferredLoading = false
        simulatedDelayMs = 0
        headerLoadingDeferred = null
        itemsLoadingDeferred = null
        headerLoadingStarted = false
        itemsLoadingStarted = false
    }

    /**
     * Call this to release a pending header load when [useDeferredLoading] is true.
     */
    fun completeHeaderLoading() {
        headerLoadingDeferred?.complete(Unit)
    }

    /**
     * Call this to release a pending items load when [useDeferredLoading] is true.
     */
    fun completeItemsLoading() {
        itemsLoadingDeferred?.complete(Unit)
    }

    override suspend fun getHeader(
        playlistId: String,
        forceRefresh: Boolean,
        category: String?,
        excluded: Boolean,
        downloadPolicy: DownloadPolicy
    ): PlaylistHeader {
        headerLoadingStarted = true

        if (useDeferredLoading) {
            // Complete any pending deferred to avoid hanging coroutines
            headerLoadingDeferred?.complete(Unit)
            headerLoadingDeferred = CompletableDeferred()
            headerLoadingDeferred?.await()
        } else if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        errorToThrow?.let { throw it }
        return headerToReturn.copy(
            category = category,
            excluded = excluded,
            downloadPolicy = downloadPolicy
        )
    }

    override suspend fun getItems(
        playlistId: String,
        page: Page?,
        itemOffset: Int
    ): PlaylistPage<PlaylistItem> {
        itemsLoadingStarted = true

        if (useDeferredLoading) {
            // Complete any pending deferred to avoid hanging coroutines
            itemsLoadingDeferred?.complete(Unit)
            itemsLoadingDeferred = CompletableDeferred()
            itemsLoadingDeferred?.await()
        } else if (simulatedDelayMs > 0) {
            delay(simulatedDelayMs)
        }

        errorToThrow?.let { throw it }
        val nextItemOffset = itemOffset + itemsToReturn.size
        return PlaylistPage(
            items = itemsToReturn,
            nextPage = nextPageToReturn,
            nextItemOffset = nextItemOffset
        )
    }

    companion object {
        fun createDefaultHeader(
            id: String = "PLtest123",
            title: String = "Test Playlist",
            thumbnailUrl: String? = null,
            bannerUrl: String? = null,
            channelId: String? = "UCtest_channel",
            channelName: String? = "Test Channel",
            itemCount: Long? = 10L,
            totalDurationSeconds: Long? = 3600L,
            description: String? = "A test playlist for UI testing",
            tags: List<String> = emptyList(),
            category: String? = null,
            excluded: Boolean = false,
            downloadPolicy: DownloadPolicy = DownloadPolicy.ENABLED
        ) = PlaylistHeader(
            id = id,
            title = title,
            thumbnailUrl = thumbnailUrl,
            bannerUrl = bannerUrl,
            channelId = channelId,
            channelName = channelName,
            itemCount = itemCount,
            totalDurationSeconds = totalDurationSeconds,
            description = description,
            tags = tags,
            category = category,
            excluded = excluded,
            downloadPolicy = downloadPolicy
        )

        fun createDefaultItems(count: Int): List<PlaylistItem> {
            return (1..count).map { index ->
                PlaylistItem(
                    position = index,
                    videoId = "video_$index",
                    title = "Test Video $index",
                    thumbnailUrl = null,
                    durationSeconds = 300 + index * 60,
                    viewCount = (index * 10000).toLong(),
                    publishedTime = "$index days ago",
                    channelId = "UCtest_channel",
                    channelName = "Test Channel"
                )
            }
        }
    }
}
