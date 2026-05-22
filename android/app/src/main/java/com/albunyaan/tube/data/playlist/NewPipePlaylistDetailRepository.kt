package com.albunyaan.tube.data.playlist

import android.util.Log
import com.albunyaan.tube.data.channel.Page
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.index.IndexRepository
import com.albunyaan.tube.data.index.StreamIndexItem
import com.albunyaan.tube.download.DownloadPolicy
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import java.io.IOException
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
    @Suppress("unused") private val extractorClient: NewPipeExtractorClient,
    private val indexRepository: IndexRepository
) : PlaylistDetailRepository {

    private val youtubeService = ServiceList.YouTube
    private val playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance()

    /**
     * Mutex to ensure atomic cache operations (TTL check + eviction + insertion).
     * Prevents race conditions when multiple coroutines access the cache concurrently.
     */
    private val cacheMutex = Mutex()

    /**
     * In-memory cache for playlist info using LinkedHashMap with insertion-order tracking.
     * Automatic eviction when size exceeds MAX_CACHE_SIZE via removeEldestEntry.
     * All access must be protected by [cacheMutex] to ensure atomicity.
     */
    private val playlistInfoCache: MutableMap<String, CacheEntry<PlaylistInfo>> =
        object : LinkedHashMap<String, CacheEntry<PlaylistInfo>>(MAX_CACHE_SIZE, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<PlaylistInfo>>): Boolean {
                val shouldRemove = size > MAX_CACHE_SIZE
                if (shouldRemove) {
                    Log.d(TAG, "Evicting oldest cache entry: ${eldest.key}")
                }
                return shouldRemove
            }
        }

    /**
     * Cache for uploader-URL → canonical UC channel ID resolutions. Keyed by the
     * raw uploader URL NewPipe gives us; valued by the canonical "UC..." ID or
     * null when resolution failed. Non-canonical URL forms (/@handle, /c/name,
     * /user/name) require a NewPipe channel-page fetch to discover the
     * underlying UC ID. Positive entries cache for 24h (canonical IDs are
     * effectively immutable); negative entries cache for a short
     * [CHANNEL_ID_NEGATIVE_CACHE_TTL_MILLIS] window so a permanently broken
     * channel (deleted / handle-renamed / persistent parse failure) does not
     * re-trigger a NewPipe fetch on every header load, while a transient
     * failure recovers within minutes.
     * All access must be protected by [cacheMutex].
     */
    private val channelIdCache: MutableMap<String, CacheEntry<String?>> =
        object : LinkedHashMap<String, CacheEntry<String?>>(MAX_CHANNEL_ID_CACHE_SIZE, 0.75f, false) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<String?>>): Boolean {
                return size > MAX_CHANNEL_ID_CACHE_SIZE
            }
        }

    /**
     * In-flight resolutions keyed by uploader URL. Single-flight pattern: when
     * two callers ask for the same URL on a cold cache, the second caller awaits
     * the first's [Deferred] instead of firing a duplicate NewPipe channel-page
     * fetch. The deferred is started on [resolutionScope] (independent of any
     * caller's scope) so that a caller cancellation cannot kill an in-flight
     * fetch that another caller is awaiting — OkHttp wouldn't honour the
     * interrupt anyway, so the fetch would continue but its result would be
     * discarded without single-flight.
     * All access must be protected by [cacheMutex].
     */
    private val inflightResolutions: MutableMap<String, Deferred<String?>> = mutableMapOf()

    /**
     * Long-lived supervisor scope for in-flight resolution fetches. Singleton
     * lifetime — the repository itself is `@Singleton`, so this scope outlives
     * any ViewModel. Independent failures on individual fetches do not cancel
     * sibling fetches (SupervisorJob).
     */
    private val resolutionScope: CoroutineScope =
        CoroutineScope(SupervisorJob() + Dispatchers.IO)

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
                    // [getPlaylistInfo] sets its own USER_FOREGROUND priority.
                    val info = getPlaylistInfo(playlistId, forceRefresh = false)
                    var rawItems: List<InfoItem> = info.relatedItems
                    var rawNextPage = info.nextPage

                    // NewPipe's PlaylistInfo.getInfo() pipes the initial page
                    // through ExtractorHelper.getItemsPageOrLogError(), which
                    // SWALLOWS extraction errors and returns an empty list.
                    // Result: header metadata says "42 items" (streamCount) but
                    // relatedItems is empty, and we render the misleading
                    // "Clear filters" empty state. Detect that case and
                    // re-extract directly so any real failure surfaces and
                    // any successful re-parse populates the list.
                    if (rawItems.isEmpty() && info.streamCount > 0) {
                        Log.w(
                            TAG,
                            "Empty relatedItems despite streamCount=${info.streamCount} for $playlistId — " +
                                    "PlaylistInfo silent-swallow; re-extracting initial page directly"
                        )
                        val handler = createPlaylistLinkHandler(playlistId)
                            ?: throw ExtractionException("Invalid playlist ID: $playlistId")
                        val rescued = NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
                            val extractor = youtubeService.getPlaylistExtractor(handler)
                            extractor.fetchPage()
                            extractor.initialPage
                        }
                        rawItems = rescued.items
                        rawNextPage = rescued.nextPage
                        Log.d(TAG, "Direct re-extraction yielded ${rawItems.size} items for $playlistId")
                    }

                    items = rawItems
                        .filterIsInstance<StreamInfoItem>()
                        .mapIndexedNotNull { index, item ->
                            item.toPlaylistItem(itemOffset + index)
                        }
                    // Preserve NewPipe's page token exactly as-is
                    nextPage = Page.fromNewPipePage(rawNextPage)
                    Log.d(TAG, "Fetched initial page: ${items.size} items starting at $itemOffset, hasMore=${nextPage != null}")
                } else {
                    // Subsequent pages - getMoreItems expects a URL string.
                    // Mark this NewPipe path as USER_FOREGROUND so the rate-limit
                    // / cooldown gates apply (spec §4.4 / §4.5).
                    val url = "https://www.youtube.com/playlist?list=$playlistId"
                    val morePage = NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
                        PlaylistInfo.getMoreItems(youtubeService, url, page.toNewPipePage())
                    }

                    items = morePage.items
                        .filterIsInstance<StreamInfoItem>()
                        .mapIndexedNotNull { index, item ->
                            item.toPlaylistItem(itemOffset + index)
                        }

                    // Preserve NewPipe's page token exactly as-is (don't mutate id field)
                    nextPage = Page.fromNewPipePage(morePage.nextPage)

                    Log.d(TAG, "Fetched more: ${items.size} items starting at $itemOffset, hasMore=${nextPage != null}")
                }

                // Piggyback index loaded items (fire-and-forget, never blocks UI)
                // Graceful indexing: don't block playlist loading on index errors (429, etc)
                try {
                    indexRepository.indexPlaylistStreams(playlistId, items.map { it.toIndexItem() })
                } catch (e: Exception) {
                    Log.w(TAG, "Indexing failed for playlist $playlistId (continuing anyway): ${e.message}")
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
     * Uses [cacheMutex] to ensure atomic cache read/write operations.
     */
    private suspend fun getPlaylistInfo(playlistId: String, forceRefresh: Boolean): PlaylistInfo {
        return withContext(Dispatchers.IO) {
            val now = System.currentTimeMillis()

            // Check cache atomically unless force refresh
            if (!forceRefresh) {
                cacheMutex.withLock {
                    playlistInfoCache[playlistId]?.let { entry ->
                        if (now - entry.timestamp <= CACHE_TTL_MILLIS) {
                            Log.d(TAG, "Cache hit for playlist: $playlistId")
                            return@withContext entry.value
                        }
                    }
                }
            }

            Log.d(TAG, "Fetching playlist info for: $playlistId")
            try {
                // Mark this NewPipe path as USER_FOREGROUND so
                // [com.albunyaan.tube.data.extractor.RateLimitedDownloader]
                // routes the HTTP call through the foreground rate-limit lane
                // (spec §4.4 / §4.5). Set inside withContext(Dispatchers.IO)
                // so the ThreadLocal is observed on the actual NewPipe call thread.
                NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
                    val handler = createPlaylistLinkHandler(playlistId)
                        ?: throw ExtractionException("Invalid playlist ID: $playlistId")

                    val extractor = youtubeService.getPlaylistExtractor(handler)
                    extractor.fetchPage()
                    val info = PlaylistInfo.getInfo(extractor)

                    // Cache the result atomically (LinkedHashMap eviction + insertion as one operation)
                    cacheMutex.withLock {
                        playlistInfoCache[playlistId] = CacheEntry(info, now)
                    }
                    Log.d(TAG, "Cached playlist info for: $playlistId with ${info.streamCount} items")

                    info
                }
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
            // channelId may be a handle/name string for /@handle, /c/name, or
            // /user/name uploader URLs — see [resolveCanonicalChannelId] for
            // the canonicalization. We keep the raw extraction here so the
            // header renders without waiting on a NewPipe channel-page fetch;
            // the ViewModel canonicalizes asynchronously before the registry
            // gate runs (and updates this field once the UC id is known).
            channelId = uploaderUrl?.let { extractChannelId(it) },
            channelName = uploaderName,
            itemCount = streamCount.takeIf { it >= 0 },
            totalDurationSeconds = null, // Not directly available from PlaylistInfo
            description = description?.content,
            tags = emptyList(), // PlaylistInfo doesn't expose tags
            category = category,
            excluded = excluded,
            downloadPolicy = downloadPolicy,
            // Carry the raw uploader URL forward so the async channel-link
            // resolver in PlaylistDetailViewModel can canonicalize it without
            // re-querying NewPipe for the whole playlist info.
            parentChannelUrl = uploaderUrl
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
            channelName = uploaderName,
            uploadedAtMillis = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
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
            // Extract from URLs like /channel/UC..., /@handle, /c/name, /user/name.
            // Strip path, query, and fragment to keep just the id segment — a
            // trailing "#section" or "?si=..." would otherwise leak into the id
            // string and bypass the canonical-id regex on the slow-path retry.
            when {
                url.contains("/channel/") -> url.substringAfter("/channel/")
                url.contains("/@") -> url.substringAfter("/@")
                url.contains("/c/") -> url.substringAfter("/c/")
                url.contains("/user/") -> url.substringAfter("/user/")
                else -> return null
            }
                .substringBefore("/")
                .substringBefore("?")
                .substringBefore("#")
                .takeIf { it.isNotEmpty() }
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Resolve an uploader URL to a canonical UC... channel ID.
     *
     * `/channel/UC...` URLs already yield a canonical ID and short-circuit
     * without network. `/@handle`, `/c/name`, `/user/name` URLs cannot be
     * mapped to a UC id offline — YouTube performs the mapping server-side —
     * so we fetch the channel page via NewPipe and read the canonical id off
     * [ChannelInfo.getId]. After `fetchPage()`, NewPipe normalises `info.id`
     * to the canonical UC string for the YouTube service; no fallback parse
     * of `info.url` is needed (and the URL is rendered as
     * `https://www.youtube.com/UCxxx...`, which `extractChannelId` rejects
     * anyway — see code-review finding F2 on commit f2ae62de).
     *
     * Caching:
     * - Successful resolutions cache for 24h. Canonical ids are effectively
     *   immutable per channel; long TTL is safe.
     * - Null results cache for a short negative TTL (5 min). Without negative
     *   caching, a permanently-broken channel (deleted, handle-renamed,
     *   persistent NewPipe parse failure) would re-trigger a NewPipe fetch
     *   on every header load. The short TTL means a transient failure
     *   recovers within minutes when the user navigates back.
     *
     * Returns null when the URL is null/blank, when no candidate id can be
     * extracted, or when the channel fetch fails. The caller treats null as
     * "no canonical id available" which keeps the registry gate fail-closed.
     */
    override suspend fun resolveCanonicalChannelId(uploaderUrl: String?): String? {
        // Block the entire resolver on Dispatchers.IO. ChannelInfo.getInfo is a
        // synchronous NewPipe call (OkHttp under the hood) — without this, a
        // caller on the main thread (e.g. viewModelScope.launch which defaults
        // to Main.immediate) would block the UI on a network round-trip.
        // NewPipePriorityContext.with is a non-suspending try/finally over a
        // ThreadLocal, so it does NOT switch dispatcher itself.
        return withContext(Dispatchers.IO) {
            if (uploaderUrl.isNullOrBlank()) return@withContext null

            val rawExtracted = extractChannelId(uploaderUrl) ?: return@withContext null

            // Fast path: /channel/UC... already canonical.
            if (CANONICAL_CHANNEL_ID_REGEX.matches(rawExtracted)) {
                return@withContext rawExtracted
            }

            // Slow path: need a NewPipe fetch to resolve handle/name → UC id.
            // Check cache (positive and negative) first; if cache miss, join
            // any in-flight resolution for the same URL or start a new one.
            val checkedAt = System.currentTimeMillis()
            val deferred: Deferred<String?> = cacheMutex.withLock {
                channelIdCache[uploaderUrl]?.let { entry ->
                    val ttl = if (entry.value != null) CHANNEL_ID_CACHE_TTL_MILLIS
                    else CHANNEL_ID_NEGATIVE_CACHE_TTL_MILLIS
                    if (checkedAt - entry.timestamp <= ttl) {
                        return@withContext entry.value
                    }
                }
                // Cache miss. If another caller is already resolving this URL,
                // ride on their deferred (single-flight). Otherwise schedule a
                // fresh fetch on the resolution scope so it survives caller
                // cancellation — and remember the deferred so concurrent
                // callers can find it.
                val existing = inflightResolutions[uploaderUrl]
                if (existing != null && !existing.isCompleted) {
                    existing
                } else {
                    resolutionScope.async {
                        // Capture the Deferred-as-Job for our own coroutine so
                        // the finally block can remove our own entry from the
                        // in-flight map without stomping on a newer Deferred
                        // that a concurrent caller might have registered.
                        // (Checking `isCompleted` would not work: at the time
                        // `finally` runs, the Deferred's body has not yet
                        // returned, so its `isCompleted` is still false — the
                        // entry would leak forever.)
                        val self = currentCoroutineContext()[Job]
                        try {
                            fetchAndCacheCanonicalChannelId(uploaderUrl)
                        } finally {
                            cacheMutex.withLock {
                                if (inflightResolutions[uploaderUrl] === self) {
                                    inflightResolutions.remove(uploaderUrl)
                                }
                            }
                        }
                    }.also { inflightResolutions[uploaderUrl] = it }
                }
            }
            deferred.await()
        }
    }

    /**
     * Performs the actual NewPipe channel-page fetch and writes the result to
     * [channelIdCache]. Runs on the [resolutionScope] (Dispatchers.IO) inside
     * [resolveCanonicalChannelId]'s single-flight `async`. Only called when a
     * cache lookup missed and no in-flight resolution exists yet.
     *
     * CRITICAL: on transient NewPipe failure (resolved=null), preserve any
     * existing positive entry even if it's past the positive TTL. Canonical UC
     * ids are immutable per channel — once we resolved one, it stays valid
     * indefinitely. Overwriting an expired-but-still-semantically-valid
     * positive entry with null would hide a working channel link for the
     * entire 5-min negative TTL on every transient YouTube outage. Negative
     * caching exists to throttle re-fetches on PERMANENT failures, not to
     * discard prior successful resolutions.
     */
    private suspend fun fetchAndCacheCanonicalChannelId(uploaderUrl: String): String? {
        val resolved = try {
            NewPipePriorityContext.with(Priority.USER_FOREGROUND) {
                val info = ChannelInfo.getInfo(youtubeService, uploaderUrl)
                info.id?.takeIf { CANONICAL_CHANNEL_ID_REGEX.matches(it) }
            }
        } catch (ce: CancellationException) {
            throw ce
        } catch (e: Exception) {
            Log.w(TAG, "Failed to resolve canonical channel id for $uploaderUrl", e)
            null
        }
        // Cache result with fresh wall-clock captured AFTER the network.
        return cacheMutex.withLock {
            val toCache = resolved ?: channelIdCache[uploaderUrl]?.value
            channelIdCache[uploaderUrl] = CacheEntry(toCache, System.currentTimeMillis())
            toCache
        }
    }

    private fun PlaylistItem.toIndexItem() = StreamIndexItem(
        id = videoId, name = title, thumbnailUrl = thumbnailUrl,
        uploaderName = channelName, channelId = channelId,
        duration = durationSeconds?.toLong(), viewCount = viewCount, streamType = "VIDEO"
    )

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    companion object {
        private const val TAG = "PlaylistDetailRepo"
        private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L // 30 minutes
        private const val MAX_CACHE_SIZE = 100 // Maximum cached playlists
        // Canonical YouTube channel ids are 24 characters: "UC" + 22 of
        // [A-Za-z0-9_-]. We reject anything else so handles/names cannot
        // accidentally satisfy the registry HEAD lookup.
        private val CANONICAL_CHANNEL_ID_REGEX = Regex("^UC[A-Za-z0-9_-]{22}$")
        private const val CHANNEL_ID_CACHE_TTL_MILLIS = 24 * 60 * 60 * 1000L // 24 hours
        // Short TTL for cached negative results — see [resolveCanonicalChannelId].
        // Long enough to prevent re-fetch storms on persistent failures,
        // short enough that a transient NewPipe blip recovers on the next
        // header load attempt.
        private const val CHANNEL_ID_NEGATIVE_CACHE_TTL_MILLIS = 5 * 60 * 1000L // 5 minutes
        private const val MAX_CHANNEL_ID_CACHE_SIZE = 200
    }
}
