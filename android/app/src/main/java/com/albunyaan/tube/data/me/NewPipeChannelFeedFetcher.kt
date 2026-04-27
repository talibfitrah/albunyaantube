package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import javax.inject.Inject
import javax.inject.Singleton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.stream.StreamInfoItem

/**
 * Default [ChannelFeedFetcher] — uses NewPipeExtractor on-device.
 *
 * Strategy: resolve the channel, then pull the Videos tab (and Shorts tab if
 * present). Items from the Shorts tab are marked isShort regardless of
 * StreamInfoItem.isShortFormContent. Items from the Videos tab use
 * isShortFormContent so that any short that bubbled into the main feed is
 * still bucketed correctly.
 *
 * The injected [NewPipeExtractorClient] ensures NewPipe.init() has already
 * been called before first use.
 */
@Singleton
class NewPipeChannelFeedFetcher @Inject constructor(
    // Held only to force Hilt to construct NewPipeExtractorClient first — its
    // init {} block calls NewPipe.init(). Do not remove even though the field
    // is never read: without it, a cold Me-tab open could hit NewPipeExtractor
    // before init.
    private val newPipeInit: NewPipeExtractorClient,
) : ChannelFeedFetcher {

    override suspend fun fetchLatest(
        channelUrl: String,
        priorEtag: String?,
        priorLastModified: String?,
    ): ChannelFeedFetcher.FetchResult = withContext(Dispatchers.IO) {
        // NewPipe scrapes HTML — there is no ETag / Last-Modified to honour,
        // so the conditional-GET inputs are accepted for interface parity
        // and ignored. This fetcher remains in the codebase as the rollback
        // path per spec §10; the default binding is AtomChannelFeedFetcher.
        //
        // Mark this NewPipe path as BACKGROUND_REFRESH so
        // [com.albunyaan.tube.data.extractor.RateLimitedDownloader] subjects
        // it to the rate-limit + cooldown gates (spec §4.4 / §4.5). Even
        // though this is the rollback path, the priority context still
        // applies — see spec §4.5 (background lane).
        NewPipePriorityContext.with(Priority.BACKGROUND_REFRESH) {
            val info = ChannelInfo.getInfo(ServiceList.YouTube, channelUrl)
            val tabs: List<ListLinkHandler> = info.tabs ?: emptyList()

            val videosTab = tabs.firstOrNull { it.hasFilter(ChannelTabs.VIDEOS) }
            val shortsTab = tabs.firstOrNull { it.hasFilter(ChannelTabs.SHORTS) }

            val items = ArrayList<ChannelFeedFetcher.ChannelFeedItem>()
            if (videosTab != null) {
                items += loadTab(videosTab, forceIsShort = false)
            }
            if (shortsTab != null) {
                items += loadTab(shortsTab, forceIsShort = true)
            }
            // Drop any items whose URL could not be parsed into an 11-char
            // YouTube video ID — an empty videoId collides on the Room primary
            // key (first insert wins, second REPLACEs it, cross-channel
            // contamination) and downstream the player fails to extract on an
            // empty ID. Filter at source so no empty-id row ever leaves the
            // fetcher. [F5 from review.]
            val filtered = items.filter { it.videoId.isNotEmpty() }
            ChannelFeedFetcher.FetchResult.Items(
                items = filtered,
                etag = null,
                lastModified = null,
            )
        }
    }

    private fun loadTab(
        tab: ListLinkHandler,
        forceIsShort: Boolean,
    ): List<ChannelFeedFetcher.ChannelFeedItem> {
        val tabInfo = ChannelTabInfo.getInfo(ServiceList.YouTube, tab)
        val related = tabInfo.relatedItems ?: emptyList()
        val out = ArrayList<ChannelFeedFetcher.ChannelFeedItem>(related.size)
        for (item in related) {
            if (item is StreamInfoItem) {
                out.add(item.toFeedItem(forceIsShort = forceIsShort))
            }
        }
        return out
    }

    private fun ListLinkHandler.hasFilter(filter: String): Boolean =
        contentFilters?.any { it == filter } == true

    private fun StreamInfoItem.toFeedItem(forceIsShort: Boolean): ChannelFeedFetcher.ChannelFeedItem {
        val uploadedAt: Long? = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli()
        val durationSeconds: Long? = if (duration > 0) duration else null
        val views: Long? = if (viewCount >= 0) viewCount else null
        val shortFlag = forceIsShort || isShortFormContent
        return ChannelFeedFetcher.ChannelFeedItem(
            videoId = extractVideoId(url.orEmpty()),
            title = name.orEmpty(),
            thumbnailUrl = thumbnails?.firstOrNull()?.url,
            durationSeconds = durationSeconds,
            viewCount = views,
            uploadedAt = uploadedAt,
            isShort = shortFlag,
        )
    }

    /**
     * YouTube URL → 11-char video ID. Accepts watch URLs, youtu.be short URLs,
     * shorts URLs, embed URLs, the old `watch/<id>` path form, and any URL
     * where the 11-char id appears at the path tail. Returns empty string if
     * no id matches — caller [fetchLatest] drops those rows.
     */
    private fun extractVideoId(url: String): String {
        if (url.isEmpty()) return ""
        return VIDEO_ID_REGEX.find(url)?.groupValues?.getOrNull(1).orEmpty()
    }

    companion object {
        /**
         * Matches the 11-char YouTube video id in any of these shapes:
         *   https://www.youtube.com/watch?v=<id>
         *   https://www.youtube.com/watch?list=PL...&v=<id>    (v= after other params)
         *   https://youtu.be/<id>
         *   https://www.youtube.com/shorts/<id>
         *   https://www.youtube.com/embed/<id>
         *   https://www.youtube.com/watch/<id>                 (older path form)
         *   https://music.youtube.com/watch?v=<id>
         *   //www.youtube.com/watch?v=<id>                     (protocol-relative)
         */
        internal val VIDEO_ID_REGEX =
            Regex("""(?:[?&]v=|youtu\.be/|/shorts/|/embed/|/watch/)([A-Za-z0-9_-]{11})""")
    }
}
