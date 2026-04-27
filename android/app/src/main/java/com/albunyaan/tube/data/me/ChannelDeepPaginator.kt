package com.albunyaan.tube.data.me

import androidx.annotation.VisibleForTesting
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.Page
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * ANDROID-PERSONAL-03 / T1: NewPipe channel deep paginator.
 *
 * The Me-tab feed is primarily fed by ATOM (~15 newest items per channel).
 * When the user scrolls back into history beyond what ATOM has, this
 * paginator pulls older pages from the channel's Videos tab via NewPipe.
 *
 * Every call goes through [NewPipePriorityContext.with(Priority.USER_FOREGROUND)]
 * so [com.albunyaan.tube.data.extractor.RateLimitedDownloader] subjects it to
 * the rate-limit + cooldown gates. NEVER use [Priority.PLAYER] here — that
 * priority bypasses the gates and is reserved for live playback only.
 *
 * The injected [NewPipeExtractorClient] ensures `NewPipe.init()` has run
 * before first use (same pattern as [NewPipeChannelFeedFetcher]).
 *
 * Test seam: [pageProvider] is `internal` and replaceable by test code via
 * the @VisibleForTesting secondary constructor. Production callers use the
 * primary @Inject constructor.
 */
@Singleton
class ChannelDeepPaginator @VisibleForTesting internal constructor(
    // Held only to force Hilt to construct NewPipeExtractorClient first — its
    // init {} block calls NewPipe.init(). Do not remove even though the field
    // is never read directly: without it, a cold paging open could hit
    // NewPipeExtractor before init.
    @Suppress("unused")
    private val newPipeInit: NewPipeExtractorClient?,
    // Test seam. Defaults to the real NewPipe-backed implementation.
    private val pageProvider: PageProvider,
) {

    @Inject constructor(newPipeInit: NewPipeExtractorClient) : this(
        newPipeInit = newPipeInit,
        pageProvider = RealPageProvider,
    )

    /**
     * Fetch the next page of long-form videos for [channelUrl].
     *
     * - First call: pass `nextPageToken = null`. The paginator resolves the
     *   channel's Videos tab and returns the initial page.
     * - Subsequent calls: pass the [SerializedPage] returned in the previous
     *   [DeepPageResult.Page] response.
     *
     * Returns:
     *  - [DeepPageResult.Page] when items + an optional next page were extracted.
     *  - [DeepPageResult.EndOfChannel] when the channel has no more videos.
     *  - [DeepPageResult.Error] on any extraction or network failure (the
     *    error message is preserved for diagnostics; the caller should treat
     *    it as a transient signal and try again later).
     */
    suspend fun fetchNextPage(
        channelUrl: String,
        nextPageToken: SerializedPage?,
    ): DeepPageResult = NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
        withContext(Dispatchers.IO) {
            try {
                val raw = pageProvider.fetch(channelUrl, nextPageToken?.toPage())
                val items = raw.items
                    .mapNotNull { it.toFeedItem() }
                    .filter { it.videoId.isNotEmpty() }
                if (items.isEmpty() && raw.nextPage == null) {
                    DeepPageResult.EndOfChannel
                } else {
                    DeepPageResult.Page(
                        items = items,
                        nextPage = raw.nextPage?.toSerialized(),
                    )
                }
            } catch (ce: CancellationException) {
                throw ce
            } catch (t: Throwable) {
                DeepPageResult.Error(reason = t.message ?: t::class.java.simpleName)
            }
        }
    }

    /**
     * Serializable form of NewPipe's [Page]. Persistable across process
     * restarts. NewPipe's own [Page] is `Serializable` but its `body` field
     * is opaque bytes — we drop it because YouTube's pagination doesn't use
     * it and it would bloat the Room column. `id` and `ids` are likewise
     * not used by YouTube's continuation tokens.
     */
    data class SerializedPage(
        val url: String,
        val cookies: Map<String, String>?,
    ) {
        internal fun toPage(): Page = if (cookies.isNullOrEmpty()) {
            Page(url)
        } else {
            Page(url, cookies)
        }

        companion object {
            internal fun fromPage(page: Page): SerializedPage = SerializedPage(
                url = page.url ?: "",
                cookies = page.cookies?.takeIf { it.isNotEmpty() },
            )
        }
    }

    sealed class DeepPageResult {
        data class Page(
            val items: List<ChannelFeedFetcher.ChannelFeedItem>,
            val nextPage: SerializedPage?,
        ) : DeepPageResult()

        object EndOfChannel : DeepPageResult()

        data class Error(val reason: String) : DeepPageResult()
    }

    /**
     * Test seam over NewPipe. Production binding is [RealPageProvider].
     * Tests pass an in-memory implementation that returns canned pages.
     */
    internal interface PageProvider {
        /** A raw page result, before videoId normalisation/filtering. */
        data class Raw(
            val items: List<StreamInfoItem>,
            val nextPage: Page?,
        )

        @Throws(Exception::class)
        suspend fun fetch(channelUrl: String, page: Page?): Raw
    }

    /**
     * NewPipe-backed implementation. Synchronous; called from Dispatchers.IO.
     *
     * ANDROID-PERSONAL-03 round 8 [field-bug]: previously used
     * [ChannelInfo.getInfo] + [ChannelTabInfo.getInfo] (the Videos tab path).
     * NewPipe v0.26.0's [org.schabi.newpipe.extractor.services.youtube
     * .extractors.YoutubeChannelTabExtractor.collectItemsFrom] throws NPE
     * (`Attempt to invoke interface method 'int java.util.List.size()' on a
     * null object reference`) for some channels — a known YouTube response
     * shape NewPipe doesn't yet handle.
     *
     * Workaround: every YouTube channel has an auto-generated "uploads"
     * playlist. The playlist ID is derived by replacing the `UC` prefix of
     * the channel ID with `UU`. The uploads playlist exposes ALL uploads
     * paginated and uses [org.schabi.newpipe.extractor.services.youtube
     * .extractors.YoutubePlaylistExtractor], a different code path not
     * affected by the channel-tab NPE.
     *
     * Side effect: the uploads playlist intermixes Shorts and long-form,
     * but our downstream filter on `isShortFormContent` already handles
     * that — no behaviour change for the cache.
     */
    internal object RealPageProvider : PageProvider {
        private val UCID_REGEX = Regex("/channel/(UC[A-Za-z0-9_-]+)")

        private fun uploadsPlaylistUrl(channelUrl: String): String? {
            val ucid = UCID_REGEX.find(channelUrl)?.groupValues?.getOrNull(1) ?: return null
            // Convert UCxxx... → UUxxx... (uploads playlist convention).
            val uploadsId = "UU" + ucid.removePrefix("UC")
            return "https://www.youtube.com/playlist?list=$uploadsId"
        }

        override suspend fun fetch(channelUrl: String, page: Page?): PageProvider.Raw {
            val service = ServiceList.YouTube
            val playlistUrl = uploadsPlaylistUrl(channelUrl)
                ?: return PageProvider.Raw(items = emptyList(), nextPage = null)
            return if (page == null) {
                val info = PlaylistInfo.getInfo(service, playlistUrl)
                PageProvider.Raw(
                    items = (info.relatedItems ?: emptyList()).filterIsInstance<StreamInfoItem>(),
                    nextPage = info.nextPage,
                )
            } else {
                val more = PlaylistInfo.getMoreItems(service, playlistUrl, page)
                PageProvider.Raw(
                    items = (more.items ?: emptyList()).filterIsInstance<StreamInfoItem>(),
                    nextPage = more.nextPage,
                )
            }
        }
    }
}

/**
 * Top-level extension because NewPipe's [Page] is Java; instance-shadowed
 * cookies have a default-empty map which would round-trip into a
 * non-null-but-empty cookies field.
 */
private fun Page.toSerialized(): ChannelDeepPaginator.SerializedPage =
    ChannelDeepPaginator.SerializedPage.fromPage(this)

/**
 * Convert a [StreamInfoItem] to our internal [ChannelFeedFetcher.ChannelFeedItem]
 * shape. Returns null if the item is missing essential fields.
 */
private fun StreamInfoItem.toFeedItem(): ChannelFeedFetcher.ChannelFeedItem? {
    val videoId = NewPipeChannelFeedFetcher.VIDEO_ID_REGEX
        .find(url.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    if (videoId.isEmpty()) return null
    val uploadedAt: Long? = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()
    val durationSeconds: Long? = if (duration > 0) duration else null
    val views: Long? = if (viewCount >= 0) viewCount else null
    return ChannelFeedFetcher.ChannelFeedItem(
        videoId = videoId,
        title = name.orEmpty(),
        thumbnailUrl = thumbnails?.firstOrNull()?.url,
        durationSeconds = durationSeconds,
        viewCount = views,
        uploadedAt = uploadedAt,
        isShort = isShortFormContent,
    )
}
