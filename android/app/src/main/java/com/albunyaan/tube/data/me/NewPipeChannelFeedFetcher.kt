package com.albunyaan.tube.data.me

import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
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

    override suspend fun fetchLatest(channelUrl: String): List<ChannelFeedFetcher.ChannelFeedItem> =
        withContext(Dispatchers.IO) {
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
            items
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

    private fun extractVideoId(url: String): String =
        VIDEO_ID_REGEX.find(url)?.groupValues?.get(1).orEmpty()

    companion object {
        private val VIDEO_ID_REGEX =
            Regex("""(?:v=|youtu\.be/|shorts/|embed/)([A-Za-z0-9_-]{11})""")
    }
}
