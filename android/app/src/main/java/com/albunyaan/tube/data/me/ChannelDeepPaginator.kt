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
 * before first use.
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
     * restarts.
     *
     * ANDROID-PERSONAL-03 round 8 [field-bug]: an earlier version dropped
     * NewPipe's `body` field on the assumption YouTube's pagination didn't
     * use it. That was wrong — for the uploads-playlist path the actual
     * continuation token is a JSON POST payload in `body`, and dropping it
     * meant every saved Page on second use sent an empty token, returning
     * an empty page that was misinterpreted as `EndOfChannel`. We now
     * persist all five [Page] fields (`url`, `id`, `ids`, `cookies`, `body`)
     * so a SerializedPage can be reconstructed losslessly.
     */
    data class SerializedPage(
        val url: String,
        val id: String?,
        val ids: List<String>?,
        val cookies: Map<String, String>?,
        val body: ByteArray?,
    ) {
        internal fun toPage(): Page = Page(
            url,
            id,
            ids,
            cookies?.takeIf { it.isNotEmpty() },
            body,
        )

        // ByteArray equality must be content-based for data class equals/hashCode.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is SerializedPage) return false
            if (url != other.url) return false
            if (id != other.id) return false
            if (ids != other.ids) return false
            if (cookies != other.cookies) return false
            if (body == null) return other.body == null
            if (other.body == null) return false
            return body.contentEquals(other.body)
        }

        override fun hashCode(): Int {
            var result = url.hashCode()
            result = 31 * result + (id?.hashCode() ?: 0)
            result = 31 * result + (ids?.hashCode() ?: 0)
            result = 31 * result + (cookies?.hashCode() ?: 0)
            result = 31 * result + (body?.contentHashCode() ?: 0)
            return result
        }

        companion object {
            internal fun fromPage(page: Page): SerializedPage = SerializedPage(
                url = page.url ?: "",
                id = page.id?.takeIf { it.isNotEmpty() },
                ids = page.ids?.takeIf { it.isNotEmpty() },
                cookies = page.cookies?.takeIf { it.isNotEmpty() },
                body = page.body,
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
            // ANDROID-PERSONAL-03 round 8 review [P1]: a stored channel
            // URL that doesn't match `/channel/UC<...>` (e.g. legacy
            // `/c/handle` or `/@handle` rows from older subscriptions)
            // would previously silently no-op into Raw(empty, null),
            // which fetchNextPage maps to DeepPageResult.EndOfChannel —
            // permanently marking the channel exhausted. Throw instead
            // so fetchNextPage's catch-all maps to DeepPageResult.Error
            // and runDeepPageFor leaves the deepPageUrl unchanged
            // (channel stays a candidate for the next round, where a
            // future migration could canonicalise the URL).
            val playlistUrl = uploadsPlaylistUrl(channelUrl)
                ?: throw IllegalStateException("Unsupported channel URL: $channelUrl")
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
    val videoId = YouTubeVideoIdRegex.VIDEO_ID_REGEX
        .find(url.orEmpty())
        ?.groupValues
        ?.getOrNull(1)
        .orEmpty()
    if (videoId.isEmpty()) return null
    val uploadedAt: Long? = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()
    val durationSeconds: Long? = if (duration > 0) duration else null
    val views: Long? = if (viewCount >= 0) viewCount else null
    // ANDROID-PERSONAL-03 round 8 [field-bug]: YouTube's `UU<channelId>`
    // uploads-playlist API does NOT expose `isShortFormContent` for items
    // — NewPipe returns `false` for every item parsed from a playlist
    // page. ATOM RSS does include the flag, so the most-recent 15 items
    // (the ATOM cache) are correctly classified, but deep-paged history
    // would land in the cache as `isShort=false` regardless of whether
    // the upload was actually a Short. Result: shorts only appeared in
    // the latest weeks (ATOM-covered) and disappeared from older weeks.
    //
    // Three-tier detection:
    //  1. Trust NewPipe's flag when set (`isShortFormContent`).
    //  2. If the canonical URL is a `/shorts/` URL, it's definitively a
    //     Short — YouTube returns this URL form for any video uploaded
    //     as a Short, regardless of duration.
    //  3. Heuristic fallback: anything <= 180 seconds (the new 3-minute
    //     Short cap) is treated as a Short. May misclassify a few
    //     short-form long-form videos but preserves Shorts in older weeks.
    val urlIsShortForm = url?.contains("/shorts/") == true
    val isShort = isShortFormContent ||
        urlIsShortForm ||
        (durationSeconds != null && durationSeconds in 1L..180L)
    return ChannelFeedFetcher.ChannelFeedItem(
        videoId = videoId,
        title = name.orEmpty(),
        thumbnailUrl = thumbnails?.firstOrNull()?.url,
        durationSeconds = durationSeconds,
        viewCount = views,
        uploadedAt = uploadedAt,
        isShort = isShort,
    )
}
