package com.albunyaan.tube.data.playlist

import android.util.Log
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.download.DownloadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Repository implementation that fetches playlist details directly from NewPipeExtractor.
 * No backend API calls - all data comes from YouTube via NewPipe scraping.
 *
 * Note: This repository depends on [NewPipeExtractorClient] to ensure NewPipe is properly
 * initialized with the shared downloader, localization, and metrics before any extraction.
 */
@Singleton
class NewPipePlaylistDetailRepository @Inject constructor(
    // Inject NewPipeExtractorClient to ensure NewPipe is initialized with shared
    // downloader, localization (US), and metrics. The client initializes NewPipe
    // in its constructor via initializeNewPipe().
    @Suppress("unused") private val extractorClient: NewPipeExtractorClient
) : PlaylistDetailRepository {

    private val youtubeService = ServiceList.YouTube
    private val playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance()

    // In-memory cache for playlist info
    private val playlistInfoCache = ConcurrentHashMap<String, CacheEntry<PlaylistInfo>>()

    override suspend fun getHeader(
        playlistId: String,
        forceRefresh: Boolean,
        category: String?,
        excluded: Boolean,
        downloadPolicy: DownloadPolicy
    ): PlaylistHeader {
        return withContext(Dispatchers.IO) {
            val info = getPlaylistInfo(playlistId, forceRefresh)
            info.toPlaylistHeader(category, excluded, downloadPolicy)
        }
    }

    override suspend fun getItems(
        playlistId: String,
        page: Page?,
        itemOffset: Int
    ): PlaylistPage<PlaylistItem> {
        return withContext(Dispatchers.IO) {
            try {
                val items: List<PlaylistItem>
                val nextPage: Page?

                if (page == null) {
                    // Initial page - get info which includes first page of items
                    val info = getPlaylistInfo(playlistId, forceRefresh = false)
                    items = info.relatedItems
                        .filterIsInstance<StreamInfoItem>()
                        .mapIndexedNotNull { index, item ->
                            item.toPlaylistItem(itemOffset + index)
                        }
                    // Preserve NewPipe's page token exactly as-is
                    nextPage = Page.fromNewPipePage(info.nextPage)
                    Log.d(TAG, "Fetched initial page: ${items.size} items starting at $itemOffset, hasMore=${nextPage != null}")
                } else {
                    // Subsequent pages - getMoreItems expects a URL string
                    val url = "https://www.youtube.com/playlist?list=$playlistId"
                    val morePage = PlaylistInfo.getMoreItems(youtubeService, url, page.toNewPipePage())

                    items = morePage.items
                        .filterIsInstance<StreamInfoItem>()
                        .mapIndexedNotNull { index, item ->
                            item.toPlaylistItem(itemOffset + index)
                        }

                    // Preserve NewPipe's page token exactly as-is (don't mutate id field)
                    nextPage = Page.fromNewPipePage(morePage.nextPage)

                    Log.d(TAG, "Fetched more: ${items.size} items starting at $itemOffset, hasMore=${nextPage != null}")
                }

                // Return the next item offset for the caller to use on subsequent calls
                val nextItemOffset = itemOffset + items.size
                PlaylistPage(items = items, nextPage = nextPage, nextItemOffset = nextItemOffset)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch items for $playlistId", e)
                when (e) {
                    is IOException, is ExtractionException -> throw e
                    else -> throw ExtractionException("Failed to fetch playlist items", e)
                }
            }
        }
    }

    /**
     * Fetches playlist info with caching support.
     */
    private suspend fun getPlaylistInfo(playlistId: String, forceRefresh: Boolean): PlaylistInfo {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // Check cache unless force refresh
            if (!forceRefresh) {
                playlistInfoCache[playlistId]?.let { entry ->
                    if (now - entry.timestamp <= CACHE_TTL_MILLIS) {
                        Log.d(TAG, "Cache hit for playlist: $playlistId")
                        return@withContext entry.value
                    }
                }
            }

            Log.d(TAG, "Fetching playlist info for: $playlistId")
            try {
                val handler = createPlaylistLinkHandler(playlistId)
                    ?: throw ExtractionException("Invalid playlist ID: $playlistId")

                val extractor = youtubeService.getPlaylistExtractor(handler)
                extractor.fetchPage()
                val info = PlaylistInfo.getInfo(extractor)

                // Cache the result
                playlistInfoCache[playlistId] = CacheEntry(info, now)
                Log.d(TAG, "Cached playlist info for: $playlistId with ${info.streamCount} items")

                info
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e(TAG, "Failed to fetch playlist info for $playlistId", e)
                when (e) {
                    is IOException, is ExtractionException -> throw e
                    else -> throw ExtractionException("Failed to fetch playlist", e)
                }
            }
        }
    }

    /**
     * Creates a link handler from various playlist ID formats.
     */
    private fun createPlaylistLinkHandler(rawId: String): org.schabi.newpipe.extractor.linkhandler.ListLinkHandler? {
        val candidates = buildList {
            add(rawId)
            // Try different ID formats
            if (!rawId.startsWith("playlist?list=") && !rawId.contains("youtube.com")) {
                if (rawId.startsWith("PL", ignoreCase = true) ||
                    rawId.startsWith("UU", ignoreCase = true) ||
                    rawId.startsWith("OL", ignoreCase = true) ||
                    rawId.startsWith("RD", ignoreCase = true)
                ) {
                    add("playlist?list=$rawId")
                }
            }
        }.distinct()

        for (candidate in candidates) {
            try {
                return playlistLinkHandlerFactory.fromId(candidate)
            } catch (_: Exception) {
                // Try next candidate
            }
        }
        return null
    }

    // Extension functions to map NewPipe types to domain models

    private fun PlaylistInfo.toPlaylistHeader(
        category: String?,
        excluded: Boolean,
        downloadPolicy: DownloadPolicy
    ): PlaylistHeader {
        return PlaylistHeader(
            id = id,
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            bannerUrl = banners.chooseBestUrl(),
            channelId = uploaderUrl?.let { extractChannelId(it) },
            channelName = uploaderName,
            itemCount = streamCount.takeIf { it >= 0 },
            totalDurationSeconds = null, // Not directly available from PlaylistInfo
            description = description?.content,
            tags = emptyList(), // PlaylistInfo doesn't expose tags
            category = category,
            excluded = excluded,
            downloadPolicy = downloadPolicy
        )
    }

    private fun StreamInfoItem.toPlaylistItem(position: Int): PlaylistItem? {
        val videoId = extractVideoId(url) ?: return null
        return PlaylistItem(
            position = position,
            videoId = videoId,
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            durationSeconds = duration.takeIf { it in 1..Int.MAX_VALUE }?.toInt(),
            viewCount = viewCount.takeIf { it >= 0 },
            publishedTime = textualUploadDate,
            channelId = uploaderUrl?.let { extractChannelId(it) },
            channelName = uploaderName
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

    private fun extractVideoId(url: String): String? {
        // Use NewPipe's URL parser for robust extraction (handles youtu.be, shorts, etc.)
        return try {
            YoutubeStreamLinkHandlerFactory.getInstance().fromUrl(url).id
        } catch (e: Exception) {
            // Fallback to manual parsing
            url.substringAfterLast("v=")
                .substringBefore("&")
                .takeIf { it.length == 11 }
                ?: url.substringAfterLast("/").substringBefore("?").takeIf { it.length == 11 }
        }
    }

    private fun extractChannelId(url: String): String? {
        return try {
            // Extract from URLs like /channel/UC..., /@handle, /c/name, /user/name
            when {
                url.contains("/channel/") -> url.substringAfter("/channel/").substringBefore("/").substringBefore("?")
                url.contains("/@") -> url.substringAfter("/@").substringBefore("/").substringBefore("?")
                url.contains("/c/") -> url.substringAfter("/c/").substringBefore("/").substringBefore("?")
                url.contains("/user/") -> url.substringAfter("/user/").substringBefore("/").substringBefore("?")
                else -> null
            }
        } catch (e: Exception) {
            null
        }
    }

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    companion object {
        private const val TAG = "PlaylistDetailRepo"
        private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L // 30 minutes
    }
}
