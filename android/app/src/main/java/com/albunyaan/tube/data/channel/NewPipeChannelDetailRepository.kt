package com.albunyaan.tube.data.channel

import android.util.Log
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation that fetches channel details directly from NewPipeExtractor.
 * No backend API calls - all data comes from YouTube via NewPipe scraping.
 *
 * Note: This repository depends on [NewPipeExtractorClient] to ensure NewPipe is properly
 * initialized with the shared downloader, localization, and metrics before any extraction.
 *
 * Limitations:
 * - Channel links, location, and join date are not available from standard ChannelInfo.
 *   The About tab will only show description, subscriber count, and verification status.
 *
 * Note: Community Posts (Posts tab) is NOT supported because NewPipeExtractor does not
 * support YouTube Community Posts. The ChannelTabs class only supports: VIDEOS, TRACKS,
 * SHORTS, LIVESTREAMS, CHANNELS, PLAYLISTS, ALBUMS, LIKES.
 * See: https://teamnewpipe.github.io/NewPipeExtractor/javadoc/org/schabi/newpipe/extractor/channel/tabs/ChannelTabs.html
 */
@Singleton
class NewPipeChannelDetailRepository @Inject constructor(
    // Inject NewPipeExtractorClient to ensure NewPipe is initialized with shared
    // downloader, localization (US), and metrics. The client initializes NewPipe
    // in its constructor via initializeNewPipe().
    @Suppress("unused") private val extractorClient: NewPipeExtractorClient
) : ChannelDetailRepository {

    private val youtubeService = ServiceList.YouTube
    private val channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance()

    // In-memory cache for channel info (header + tabs)
    private val channelInfoCache = ConcurrentHashMap<String, CacheEntry<ChannelInfo>>()

    override suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean): ChannelHeader {
        return withContext(Dispatchers.IO) {
            val info = getChannelInfo(channelId, forceRefresh)
            info.toChannelHeader()
        }
    }

    override suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo> {
        return fetchTabContent(channelId, ChannelTabs.VIDEOS, page) { item ->
            (item as? StreamInfoItem)?.takeIf { !it.isShortFormContent }?.toChannelVideo()
        }
    }

    override suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream> {
        return fetchTabContent(channelId, ChannelTabs.LIVESTREAMS, page) { item ->
            (item as? StreamInfoItem)?.toChannelLiveStream()
        }
    }

    override suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort> {
        return fetchTabContent(channelId, ChannelTabs.SHORTS, page) { item ->
            (item as? StreamInfoItem)?.toChannelShort()
        }
    }

    override suspend fun getPlaylists(channelId: String, page: Page?): ChannelPage<ChannelPlaylist> {
        return fetchTabContent(channelId, ChannelTabs.PLAYLISTS, page) { item ->
            (item as? PlaylistInfoItem)?.toChannelPlaylist()
        }
    }

    override suspend fun getAbout(channelId: String, forceRefresh: Boolean): ChannelHeader {
        // About uses the same data as header
        return getChannelHeader(channelId, forceRefresh)
    }

    /**
     * Fetches channel info with caching support.
     */
    private suspend fun getChannelInfo(channelId: String, forceRefresh: Boolean): ChannelInfo {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // Check cache unless force refresh
            if (!forceRefresh) {
                channelInfoCache[channelId]?.let { entry ->
                    if (now - entry.timestamp <= CACHE_TTL_MILLIS) {
                        Log.d(TAG, "Cache hit for channel: $channelId")
                        return@withContext entry.value
                    }
                }
            }

            Log.d(TAG, "Fetching channel info for: $channelId")
            try {
                val handler = createChannelLinkHandler(channelId)
                    ?: throw ExtractionException("Invalid channel ID: $channelId")

                val extractor = youtubeService.getChannelExtractor(handler)
                extractor.fetchPage()
                val info = ChannelInfo.getInfo(extractor)

                // Cache the result
                channelInfoCache[channelId] = CacheEntry(info, now)
                Log.d(TAG, "Cached channel info for: $channelId with ${info.tabs.size} tabs")

                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch channel info for $channelId", e)
                when (e) {
                    is IOException, is ExtractionException -> throw e
                    else -> throw ExtractionException("Failed to fetch channel", e)
                }
            }
        }
    }

    /**
     * Generic method to fetch tab content with pagination.
     */
    private suspend fun <T> fetchTabContent(
        channelId: String,
        tabName: String,
        page: Page?,
        mapper: (InfoItem) -> T?
    ): ChannelPage<T> = withContext(Dispatchers.IO) {
        try {
            val channelInfo = getChannelInfo(channelId, forceRefresh = false)

            // Find the tab handler
            val tabHandler = channelInfo.tabs.find { it.contentFilters.contains(tabName) }
            if (tabHandler == null) {
                Log.d(TAG, "Tab $tabName not found for channel $channelId")
                return@withContext ChannelPage(items = emptyList(), nextPage = null)
            }

            val items: List<T>
            val nextPage: Page?

            if (page == null) {
                // Initial page
                val tabInfo = ChannelTabInfo.getInfo(youtubeService, tabHandler)
                items = tabInfo.relatedItems.mapNotNull(mapper)
                nextPage = Page.fromNewPipePage(tabInfo.nextPage)
                Log.d(TAG, "Fetched initial $tabName page: ${items.size} items, hasMore=${nextPage != null}")
            } else {
                // Subsequent page
                val morePage = ChannelTabInfo.getMoreItems(youtubeService, tabHandler, page.toNewPipePage())
                items = morePage.items.mapNotNull(mapper)
                nextPage = Page.fromNewPipePage(morePage.nextPage)
                Log.d(TAG, "Fetched more $tabName: ${items.size} items, hasMore=${nextPage != null}")
            }

            ChannelPage(items = items, nextPage = nextPage)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e(TAG, "Failed to fetch $tabName for $channelId", e)
            when (e) {
                is IOException, is ExtractionException -> throw e
                else -> throw ExtractionException("Failed to fetch $tabName", e)
            }
        }
    }

    /**
     * Creates a link handler from various channel ID formats.
     */
    private fun createChannelLinkHandler(rawId: String): ListLinkHandler? {
        val candidates = buildList {
            add(rawId)
            // Try different ID formats
            if (!rawId.startsWith("channel/") && !rawId.startsWith("user/") &&
                !rawId.startsWith("c/") && !rawId.startsWith("@")
            ) {
                if (rawId.startsWith("UC", ignoreCase = true)) {
                    add("channel/$rawId")
                } else {
                    add("c/$rawId")
                    add("@$rawId")
                }
            }
        }.distinct()

        for (candidate in candidates) {
            try {
                return channelLinkHandlerFactory.fromId(candidate)
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        return null
    }

    // Extension functions to map NewPipe types to domain models

    private fun ChannelInfo.toChannelHeader(): ChannelHeader {
        // Extract donation links as channel links (best available option)
        // NewPipe provides donationLinks which is a String[] of URLs like Patreon, PayPal, etc.
        // that the channel owner has added to their About page.
        val channelLinks = donationLinks?.mapNotNull { url ->
            if (url.isNullOrBlank()) return@mapNotNull null
            // Extract a readable name from the URL (domain name)
            val name = try {
                android.net.Uri.parse(url).host?.removePrefix("www.") ?: url
            } catch (e: Exception) {
                url
            }
            ChannelLink(name = name, url = url)
        } ?: emptyList()

        return ChannelHeader(
            id = id,
            title = name,
            avatarUrl = avatars.chooseBestUrl(),
            bannerUrl = banners.chooseBestUrl(),
            subscriberCount = subscriberCount.takeIf { it >= 0 },
            shortDescription = description?.take(200)?.let { if (it.length < (description?.length ?: 0)) "$it..." else it },
            summaryLine = buildSummaryLine(),
            fullDescription = description,
            links = channelLinks,
            // These fields are NOT available from NewPipe's ChannelInfo:
            // - location: YouTube doesn't expose this in a structured way
            // - joinedDate: Not available in the channel extractor
            // - totalViews: YouTube removed this from public channel pages
            location = null,
            joinedDate = null,
            totalViews = null,
            isVerified = isVerified,
            tags = tags ?: emptyList()
        )
    }

    private fun ChannelInfo.buildSummaryLine(): String? {
        val parts = mutableListOf<String>()
        parentChannelName?.takeIf { it.isNotBlank() }?.let { parts.add(it) }
        // NOTE: Verification badge is rendered in the UI layer using the isVerified flag.
        // Do NOT add hardcoded text like "✓ Verified" here as it cannot be localized.
        return parts.joinToString(" • ").takeIf { it.isNotBlank() }
    }

    private fun StreamInfoItem.toChannelVideo(): ChannelVideo {
        return ChannelVideo(
            id = extractVideoId(url),
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            durationSeconds = duration.takeIf { it > 0 }?.toInt(),
            viewCount = viewCount.takeIf { it >= 0 },
            publishedTime = textualUploadDate,
            uploaderName = uploaderName
        )
    }

    private fun StreamInfoItem.toChannelShort(): ChannelShort {
        return ChannelShort(
            id = extractVideoId(url),
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            viewCount = viewCount.takeIf { it >= 0 },
            durationSeconds = duration.takeIf { it > 0 }?.toInt(),
            publishedTime = textualUploadDate
        )
    }

    private fun StreamInfoItem.toChannelLiveStream(): ChannelLiveStream {
        val isLive = streamType == StreamType.LIVE_STREAM
        val isUpcoming = streamType == StreamType.NONE && duration <= 0 // Heuristic for upcoming

        return ChannelLiveStream(
            id = extractVideoId(url),
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            isLiveNow = isLive,
            isUpcoming = isUpcoming,
            scheduledStartTime = null, // Not easily available
            viewCount = viewCount.takeIf { it >= 0 },
            uploaderName = uploaderName
        )
    }

    private fun PlaylistInfoItem.toChannelPlaylist(): ChannelPlaylist {
        return ChannelPlaylist(
            id = extractPlaylistId(url),
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            itemCount = streamCount.takeIf { it >= 0 },
            description = description?.content,
            uploaderName = uploaderName
        )
    }

    private fun List<org.schabi.newpipe.extractor.Image>.chooseBestUrl(): String? {
        if (isEmpty()) return null
        return maxByOrNull { image ->
            val height = image.height
            val width = image.width
            when {
                height > 0 -> height
                width > 0 -> width
                else -> 0
            }
        }?.url
    }

    private fun extractVideoId(url: String): String {
        // Use NewPipe's URL parser for robust extraction (handles youtu.be, shorts, etc.)
        return try {
            YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url).id
        } catch (e: Exception) {
            // Fallback to manual parsing
            url.substringAfterLast("v=")
                .substringBefore("&")
                .takeIf { it.length == 11 }
                ?: url.substringAfterLast("/").substringBefore("?")
        }
    }

    private fun extractPlaylistId(url: String): String {
        // Use NewPipe's URL parser for robust extraction
        return try {
            YoutubePlaylistLinkHandlerFactory.getInstance().fromUrl(url).id
        } catch (e: Exception) {
            // Fallback to manual parsing
            url.substringAfterLast("list=")
                .substringBefore("&")
                .takeIf { it.isNotBlank() }
                ?: url.substringAfterLast("/").substringBefore("?")
        }
    }

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    companion object {
        private const val TAG = "ChannelDetailRepo"
        private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L // 30 minutes
    }
}
