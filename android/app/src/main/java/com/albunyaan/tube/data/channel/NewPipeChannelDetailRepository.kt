package com.albunyaan.tube.data.channel

import android.util.Log
import com.albunyaan.tube.data.extractor.NewPipeExtractorClient
import com.albunyaan.tube.data.extractor.NewPipePriorityContext
import com.albunyaan.tube.data.extractor.Priority
import com.albunyaan.tube.data.index.IndexRepository
import com.albunyaan.tube.data.index.StreamIndexItem
import com.albunyaan.tube.data.local.ChannelVideoCache
import com.albunyaan.tube.data.local.ChannelVideoCacheDao
import com.albunyaan.tube.player.StreamRequestTelemetry
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.InfoItem
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabs
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfoItem
import org.schabi.newpipe.extractor.stream.StreamType
import java.io.IOException
import java.util.Locale
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
    @Suppress("unused") private val extractorClient: NewPipeExtractorClient,
    private val indexRepository: IndexRepository,
    private val telemetry: StreamRequestTelemetry,
    private val channelVideoCacheDao: ChannelVideoCacheDao,
) : ChannelDetailRepository {

    private val youtubeService = ServiceList.YouTube
    private val channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance()

    /**
     * Mutex guarding [channelInfoCache]. Required because the cache is a
     * LinkedHashMap (not thread-safe) with side-effecting eviction via
     * [LinkedHashMap.removeEldestEntry].
     */
    private val cacheMutex = Mutex()

    /**
     * In-memory cache for channel info (header + tabs). Bounded LRU — without
     * this bound a long-running session that opens many distinct channels
     * (Me-tab subscriptions, "see more" taps, search results) accumulates
     * hundreds of kilobytes of [ChannelInfo] (tabs, banners, avatars,
     * description) per channel, which the user observes as multi-day slowdown
     * even after the 30-min TTL because stale entries are only checked on
     * read, never proactively evicted. All access must be protected by
     * [cacheMutex].
     */
    private val channelInfoCache: MutableMap<String, CacheEntry<ChannelInfo>> =
        object : LinkedHashMap<String, CacheEntry<ChannelInfo>>(MAX_CACHE_SIZE, 0.75f, /* accessOrder = */ true) {
            override fun removeEldestEntry(eldest: MutableMap.MutableEntry<String, CacheEntry<ChannelInfo>>): Boolean {
                return size > MAX_CACHE_SIZE
            }
        }

    override suspend fun getChannelHeader(channelId: String, forceRefresh: Boolean): ChannelHeader {
        return withContext(Dispatchers.IO) {
            val startMs = System.currentTimeMillis()
            try {
                val info = getChannelInfo(channelId, forceRefresh)
                val header = info.toChannelHeader()
                telemetry.recordChannelHeaderLoad(
                    channelId = channelId,
                    durationMs = System.currentTimeMillis() - startMs,
                    success = true,
                )
                header
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Channel page scrape failed (OAuth gate, UNAUTHENTICATED, bot-check).
                // Fall back to UU uploads playlist — doesn't require a full channel page;
                // gives name + avatar so the screen isn't a blank error.
                if (channelId.startsWith("UC")) {
                    try {
                        val playlistUrl = "https://www.youtube.com/playlist?list=UU" +
                            channelId.removePrefix("UC")
                        val pInfo = retryNewPipeRateLimiterTimeout("UU header fallback $channelId") {
                            NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
                                PlaylistInfo.getInfo(youtubeService, playlistUrl)
                            }
                        }
                        Log.w(TAG, "Channel page failed for $channelId; using UU playlist fallback header", e)
                        telemetry.recordChannelHeaderLoad(
                            channelId = channelId,
                            durationMs = System.currentTimeMillis() - startMs,
                            success = true,
                        )
                        return@withContext ChannelHeader(
                            id = channelId,
                            title = pInfo.uploaderName ?: channelId,
                            avatarUrl = pInfo.uploaderAvatars.chooseBestUrl(),
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
                            tags = emptyList(),
                        )
                    } catch (c: CancellationException) {
                        throw c
                    } catch (fallbackEx: Exception) {
                        Log.w(TAG, "UU playlist fallback also failed for $channelId", fallbackEx)
                    }
                }
                telemetry.recordChannelHeaderLoad(
                    channelId = channelId,
                    durationMs = System.currentTimeMillis() - startMs,
                    success = false,
                    error = e::class.simpleName ?: "Unknown",
                )
                throw e
            }
        }
    }

    override suspend fun getVideos(channelId: String, page: Page?): ChannelPage<ChannelVideo> {
        // Page the uploads playlist (UU<channelId>) instead of the channel
        // videos tab. NewPipe v0.26's YoutubeChannelTabExtractor terminates
        // pagination prematurely on some channels (returns nextPage=null after
        // 1-2 batches), capping user scroll at ~20-30 items. The uploads
        // playlist exposes the same long-form content via YoutubePlaylistExtractor
        // with reliable pagination (~100 items per page). Same workaround as
        // ChannelDeepPaginator.RealPageProvider for the Me-tab feed.
        //
        // Side effect: the uploads playlist intermixes Shorts and long-form,
        // and YouTube's UU API does not reliably set isShortFormContent on
        // playlist items. Use 3-tier detection (NewPipe flag OR /shorts/ URL
        // OR <=180s duration) to filter shorts client-side.
        val keptForCache = mutableListOf<StreamInfoItem>()
        var resolvedChannelName: String? = cacheMutex.withLock { channelInfoCache[channelId]?.value?.name }
        val result = withContext(Dispatchers.IO) {
            // Fast path: a UC-prefixed channelId can derive its UU upload
            // playlist URL directly, so we skip the upfront getChannelInfo
            // HTTP. The header fetch in [ChannelDetailViewModel] runs in
            // parallel with this — sequencing them added ~300-600 ms of
            // channel-info HTTP to every cold first-open.
            val canonicalUuUrl = channelId.takeIf { it.startsWith("UC") }?.let {
                "https://www.youtube.com/playlist?list=UU" + it.removePrefix("UC")
            }
            if (canonicalUuUrl != null) {
                try {
                    return@withContext retryNewPipeRateLimiterTimeout("uploads playlist for $channelId") {
                        NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
                            fetchUploadsPlaylistPage(canonicalUuUrl, page, keptForCache)
                        }
                    }
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "UU fast-path failed for $channelId, falling back to channel-info path: ${e.message}")
                    keptForCache.clear()
                    // Fall through to the slow path below.
                }
            }

            // Slow / fallback path: needs getChannelInfo. Used when channelId
            // isn't UC-prefixed, or when the UU fast path failed.
            try {
                val channelInfo = getChannelInfo(channelId, forceRefresh = false)
                resolvedChannelName = channelInfo.name ?: channelId
                val playlistUrl = uploadsPlaylistUrlFor(channelInfo)
                // If the info-derived URL matches what the fast path already
                // tried, skip straight to the channel-tab path — repeating
                // the same HTTP would just produce the same failure.
                if (playlistUrl == null || playlistUrl == canonicalUuUrl) {
                    return@withContext fetchTabContent(channelId, ChannelTabs.VIDEOS, page) { item ->
                        (item as? StreamInfoItem)?.takeIf { !it.isShortFormContent }?.also {
                            keptForCache.add(it)
                        }?.toChannelVideo()
                    }
                }
                retryNewPipeRateLimiterTimeout("uploads playlist for $channelId") {
                    NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
                        fetchUploadsPlaylistPage(playlistUrl, page, keptForCache)
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.w(TAG, "Uploads playlist fetch failed for $channelId, falling back to channel-tab: ${e.message}")
                fetchTabContent(channelId, ChannelTabs.VIDEOS, page) { item ->
                    (item as? StreamInfoItem)?.takeIf { !it.isShortFormContent }?.also {
                        keptForCache.add(it)
                    }?.toChannelVideo()
                }
            }
        }
        // Persist kept videos to local cache so the next channel open can paint
        // from disk before NewPipe responds. Use upsertAll — never
        // delete-then-insert — so deep-paged Me-tab history for this channel
        // is preserved.
        if (keptForCache.isNotEmpty()) {
            val name = resolvedChannelName ?: channelId
            val now = System.currentTimeMillis()
            try {
                channelVideoCacheDao.upsertAll(keptForCache.map { it.toCacheRow(channelId, name, now) })
            } catch (e: Exception) {
                Log.w(TAG, "Cache upsert failed for channel $channelId videos: ${e.message}")
            }
        }
        // Graceful indexing: don't block video loading on index errors (429, etc)
        try {
            indexRepository.indexChannelStreams(channelId, result.items.map { it.toIndexItem("VIDEO") })
        } catch (e: Exception) {
            Log.w(TAG, "Indexing failed for channel $channelId videos (continuing anyway): ${e.message}")
        }
        return result
    }

    /**
     * Single round-trip against the UU uploads playlist. Throws an
     * [IOException] when the initial page comes back with zero raw items
     * and no continuation — NewPipe occasionally returns an empty
     * [PlaylistInfo] for an active channel without raising an exception,
     * and we want the caller's catch to fall back to the channel-tab path
     * in that case.
     */
    private fun fetchUploadsPlaylistPage(
        playlistUrl: String,
        page: Page?,
        keptForCache: MutableList<StreamInfoItem>,
    ): ChannelPage<ChannelVideo> {
        val items: List<ChannelVideo>
        val nextPage: Page?
        if (page == null) {
            val info = PlaylistInfo.getInfo(youtubeService, playlistUrl)
            val raw = (info.relatedItems ?: emptyList()).filterIsInstance<StreamInfoItem>()
            val kept = raw.filter { !it.isLikelyShortByThreeTierDetection() }
            items = kept.map { it.toChannelVideo() }
            keptForCache.addAll(kept)
            nextPage = Page.fromNewPipePage(info.nextPage)
            Log.d(
                TAG,
                "Fetched videos via UU playlist (initial): raw=${raw.size} kept=${items.size} hasMore=${nextPage != null}",
            )
            if (raw.isEmpty() && nextPage == null) {
                throw IOException("UU playlist returned 0 items + no continuation")
            }
        } else {
            val more = PlaylistInfo.getMoreItems(youtubeService, playlistUrl, page.toNewPipePage())
            val raw = (more.items ?: emptyList()).filterIsInstance<StreamInfoItem>()
            val kept = raw.filter { !it.isLikelyShortByThreeTierDetection() }
            items = kept.map { it.toChannelVideo() }
            keptForCache.addAll(kept)
            nextPage = Page.fromNewPipePage(more.nextPage)
            Log.d(
                TAG,
                "Fetched videos via UU playlist (more): raw=${raw.size} kept=${items.size} hasMore=${nextPage != null}",
            )
        }
        return ChannelPage(items = items, nextPage = nextPage)
    }

    /**
     * Derives the uploads playlist URL (UU<id>) from a [ChannelInfo].
     * Returns null if no UC-prefixed ID can be resolved.
     */
    private fun uploadsPlaylistUrlFor(info: ChannelInfo): String? {
        val ucid = UCID_REGEX.find(info.url ?: "")?.groupValues?.getOrNull(1)
            ?: info.id?.takeIf { it.startsWith("UC") }
            ?: return null
        return "https://www.youtube.com/playlist?list=UU" + ucid.removePrefix("UC")
    }

    /**
     * Conservative shorts detection for the channel-detail Videos tab.
     *
     * The Me-tab uses a 3-tier rule (NewPipe flag OR /shorts/ URL OR
     * duration ≤ 180s) and accepts that some short-form long-form videos
     * get misclassified as Shorts — both buckets feed the same user
     * surface, so it's a wash. The Videos tab is different: a false
     * positive HIDES a legitimate video the user expects to see (e.g.
     * Mufti Menk's 1–3 minute reminders, Sheikh Kishk's Quran
     * recitations under 3 minutes). Dropping the duration heuristic
     * means we may leak a few actual Shorts into the Videos tab when
     * NewPipe's playlist parser doesn't surface isShortFormContent and
     * the URL is /watch?v= — that's the lesser evil. Users with the
     * Shorts sub-tab open will still see Shorts there via the
     * dedicated channel-tab path.
     */
    private fun StreamInfoItem.isLikelyShortByThreeTierDetection(): Boolean {
        if (isShortFormContent) return true
        val itemUrl = url ?: return false
        return itemUrl.contains("/shorts/")
    }

    override suspend fun getVideosViaChannelTab(channelId: String): ChannelPage<ChannelVideo> {
        // Channel-tab path only — used for the fast first-paint phase of
        // [ChannelDetailViewModel.loadVideosInitial]. Avoids the UU uploads
        // playlist's larger response (~3-5x the bytes) so the user sees
        // something painted before the slower UU fetch completes. The
        // ViewModel finalises state with the UU result for reliable
        // continuation tokens; this method is single-page only.
        return withContext(Dispatchers.IO) {
            fetchTabContent(channelId, ChannelTabs.VIDEOS, page = null) { item ->
                (item as? StreamInfoItem)?.takeIf { !it.isShortFormContent }?.toChannelVideo()
            }
        }
    }

    override suspend fun getLiveStreams(channelId: String, page: Page?): ChannelPage<ChannelLiveStream> {
        val result = fetchTabContent(channelId, ChannelTabs.LIVESTREAMS, page) { item ->
            (item as? StreamInfoItem)?.toChannelLiveStream()
        }
        // Graceful indexing: don't block live stream loading on index errors (429, etc)
        try {
            indexRepository.indexChannelStreams(channelId, result.items.map { it.toIndexItem() })
        } catch (e: Exception) {
            Log.w(TAG, "Indexing failed for channel $channelId livestreams (continuing anyway): ${e.message}")
        }
        return result
    }

    override suspend fun getShorts(channelId: String, page: Page?): ChannelPage<ChannelShort> {
        val result = fetchTabContent(channelId, ChannelTabs.SHORTS, page) { item ->
            (item as? StreamInfoItem)?.toChannelShort()
        }
        // Graceful indexing: don't block shorts loading on index errors (429, etc)
        try {
            indexRepository.indexChannelStreams(channelId, result.items.map { it.toIndexItem() })
        } catch (e: Exception) {
            Log.w(TAG, "Indexing failed for channel $channelId shorts (continuing anyway): ${e.message}")
        }
        return result
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
                val hit = cacheMutex.withLock {
                    channelInfoCache[channelId]?.takeIf { now - it.timestamp <= CACHE_TTL_MILLIS }
                }
                if (hit != null) {
                    Log.d(TAG, "Cache hit for channel: $channelId")
                    return@withContext hit.value
                }
            }

            Log.d(TAG, "Fetching channel info for: $channelId")
            try {
                // Mark this NewPipe path as VISIBLE_INTERACTIVE so the
                // [com.albunyaan.tube.data.extractor.RateLimitedDownloader]
                // cooldown gate sees the priority context. The token bucket
                // bypasses for VISIBLE_INTERACTIVE — see
                // [com.albunyaan.tube.data.extractor.GlobalNewPipeRateLimiter].
                // NewPipePriorityContext.with takes a non-suspend lambda, so
                // the suspending cacheMutex.withLock write is hoisted out
                // after the fetch returns.
                val info = retryNewPipeRateLimiterTimeout("channel header $channelId") {
                    NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
                        val handler = createChannelLinkHandler(channelId)
                            ?: throw ExtractionException("Invalid channel ID: $channelId")

                        val extractor = youtubeService.getChannelExtractor(handler)
                        extractor.fetchPage()
                        ChannelInfo.getInfo(extractor)
                    }
                }
                cacheMutex.withLock {
                    // Re-stamp after the fetch returns so a multi-second
                    // NewPipe call doesn't back-date the TTL by its own
                    // duration.
                    channelInfoCache[channelId] = CacheEntry(info, System.currentTimeMillis())
                }
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

            // Find the tab handler using robust matching
            val tabHandler = findTabHandler(channelInfo.tabs, tabName)
            if (tabHandler == null) {
                // Log available tabs for debugging
                logAvailableTabs(channelId, channelInfo.tabs, tabName)
                return@withContext ChannelPage(items = emptyList(), nextPage = null)
            }

            // Cooldown gate (RateLimitedDownloader) reads the priority context;
            // the token bucket bypasses for VISIBLE_INTERACTIVE. [getChannelInfo]
            // sets its own priority above; this scope covers the tab-content
            // HTTP calls.
            retryNewPipeRateLimiterTimeout("$tabName tab for $channelId") {
                NewPipePriorityContext.with(Priority.VISIBLE_INTERACTIVE) {
                    val items: List<T>
                    val nextPage: Page?

                    if (page == null) {
                        // Initial page
                        val tabInfo = ChannelTabInfo.getInfo(youtubeService, tabHandler)
                        items = tabInfo.relatedItems.mapNotNull(mapper)
                        nextPage = Page.fromNewPipePage(tabInfo.nextPage)
                        Log.d(TAG, "Fetched initial $tabName page: ${items.size} items, hasMore=${nextPage != null}")

                        // If items are empty but nextPage exists, content may exist on subsequent pages
                        // This can happen with some channels due to slow loading or extraction issues
                        if (items.isEmpty() && nextPage != null) {
                            Log.d(TAG, "$tabName: Initial page empty but pagination available - content may exist on subsequent pages")
                        }
                    } else {
                        // Subsequent page
                        val morePage = ChannelTabInfo.getMoreItems(youtubeService, tabHandler, page.toNewPipePage())
                        items = morePage.items.mapNotNull(mapper)
                        nextPage = Page.fromNewPipePage(morePage.nextPage)
                        Log.d(TAG, "Fetched more $tabName: ${items.size} items, hasMore=${nextPage != null}")
                    }

                    ChannelPage(items = items, nextPage = nextPage)
                }
            }
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

    private suspend fun <T> retryNewPipeRateLimiterTimeout(
        operation: String,
        block: () -> T
    ): T {
        var lastFailure: Exception? = null
        for (attempt in 1..RATE_LIMIT_RETRY_ATTEMPTS) {
            try {
                return block()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                if (!e.isNewPipeRateLimiterTimeout()) throw e
                lastFailure = e
                if (attempt == RATE_LIMIT_RETRY_ATTEMPTS) break
                val delayMs = RATE_LIMIT_RETRY_DELAY_MS * attempt
                Log.w(
                    TAG,
                    "Retrying $operation after internal NewPipe limiter timeout " +
                        "(attempt $attempt/$RATE_LIMIT_RETRY_ATTEMPTS, delay=${delayMs}ms)",
                    e
                )
                delay(delayMs)
            }
        }
        throw lastFailure ?: IOException("NewPipe rate limiter timeout")
    }

    private fun Throwable.isNewPipeRateLimiterTimeout(): Boolean {
        return message?.contains("NewPipe rate limiter timeout", ignoreCase = true) == true ||
            cause?.isNewPipeRateLimiterTimeout() == true
    }

    /**
     * Finds a tab handler using multiple matching strategies.
     *
     * NewPipe's tab identification can vary:
     * 1. contentFilters contains the exact tab name (ChannelTabs.LIVESTREAMS = "livestreams")
     * 2. The tab URL contains a path segment indicating the tab type
     * 3. The tab's original URL contains identifier patterns
     *
     * This method tries multiple approaches to ensure tabs are found regardless of
     * locale or YouTube's varying response formats.
     */
    private fun findTabHandler(tabs: List<ListLinkHandler>, tabName: String): ListLinkHandler? {
        // Strategy 1: Exact content filter match (most reliable when available)
        tabs.find { it.contentFilters.contains(tabName) }?.let { return it }

        // Strategy 2: Case-insensitive content filter match
        tabs.find { handler ->
            handler.contentFilters.any { filter ->
                filter.equals(tabName, ignoreCase = true)
            }
        }?.let { return it }

        // Strategy 3: URL-based matching for specific tabs
        // YouTube tab URLs have predictable patterns regardless of locale
        val urlPattern = when (tabName) {
            ChannelTabs.LIVESTREAMS -> listOf("/streams", "/live", "tab=streams")
            ChannelTabs.SHORTS -> listOf("/shorts", "tab=shorts")
            ChannelTabs.VIDEOS -> listOf("/videos", "tab=videos")
            ChannelTabs.PLAYLISTS -> listOf("/playlists", "tab=playlists")
            else -> emptyList()
        }

        if (urlPattern.isNotEmpty()) {
            tabs.find { handler ->
                val url = handler.url?.lowercase(Locale.ROOT) ?: ""
                urlPattern.any { pattern -> url.contains(pattern) }
            }?.let { return it }
        }

        // Strategy 4: Check originalUrl if url didn't match
        if (urlPattern.isNotEmpty()) {
            tabs.find { handler ->
                val originalUrl = handler.originalUrl?.lowercase(Locale.ROOT) ?: ""
                urlPattern.any { pattern -> originalUrl.contains(pattern) }
            }?.let { return it }
        }

        return null
    }

    /**
     * Logs available tabs for debugging when a tab is not found.
     */
    private fun logAvailableTabs(channelId: String, tabs: List<ListLinkHandler>, requestedTab: String) {
        Log.d(TAG, "Tab '$requestedTab' not found for channel $channelId")
        Log.d(TAG, "Available tabs (${tabs.size}):")
        tabs.forEachIndexed { index, handler ->
            Log.d(TAG, "  Tab $index: filters=${handler.contentFilters}, url=${handler.url ?: "null"}")
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
            tags = tags
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

    private fun StreamInfoItem.toCacheRow(
        channelId: String,
        channelName: String,
        fetchedAt: Long,
    ): ChannelVideoCache = ChannelVideoCache(
        videoId = extractVideoId(url),
        channelId = channelId,
        channelName = channelName,
        title = name,
        thumbnailUrl = thumbnails.chooseBestUrl(),
        durationSeconds = duration.takeIf { it > 0 }?.toLong(),
        viewCount = viewCount.takeIf { it >= 0 },
        uploadedAt = uploadDate?.offsetDateTime()?.toInstant()?.toEpochMilli(),
        isShort = isShortFormContent,
        fetchedAt = fetchedAt,
    )

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
        // Past/recorded streams have duration > 0 and are not live
        val isPastStream = !isLive && !isUpcoming && duration > 0

        return ChannelLiveStream(
            id = extractVideoId(url),
            title = name,
            thumbnailUrl = thumbnails.chooseBestUrl(),
            isLiveNow = isLive,
            isUpcoming = isUpcoming,
            scheduledStartTime = null, // Not easily available from list items
            viewCount = viewCount.takeIf { it >= 0 },
            uploaderName = uploaderName,
            // Duration only makes sense for past/recorded streams
            durationSeconds = if (isPastStream) duration.takeIf { it > 0 }?.toInt() else null,
            // Textual upload date (e.g., "2 weeks ago", "Streamed 3 days ago")
            publishedTime = textualUploadDate
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

    private fun ChannelVideo.toIndexItem(streamType: String = "VIDEO") = StreamIndexItem(
        id = id, name = title, thumbnailUrl = thumbnailUrl,
        uploaderName = uploaderName, channelId = null,
        duration = durationSeconds?.toLong(), viewCount = viewCount, streamType = streamType
    )

    private fun ChannelShort.toIndexItem() = StreamIndexItem(
        id = id, name = title, thumbnailUrl = thumbnailUrl,
        uploaderName = null, channelId = null,
        duration = durationSeconds?.toLong(), viewCount = viewCount, streamType = "SHORT"
    )

    private fun ChannelLiveStream.toIndexItem() = StreamIndexItem(
        id = id, name = title, thumbnailUrl = thumbnailUrl,
        uploaderName = uploaderName, channelId = null,
        duration = durationSeconds?.toLong(), viewCount = viewCount,
        streamType = if (isLiveNow) "LIVE" else "PAST_LIVE"
    )

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    companion object {
        private const val TAG = "ChannelDetailRepo"
        private const val CACHE_TTL_MILLIS = 30 * 60 * 1000L // 30 minutes
        private const val MAX_CACHE_SIZE = 100 // Maximum cached channels — caps heap footprint
        private const val RATE_LIMIT_RETRY_ATTEMPTS = 2
        private const val RATE_LIMIT_RETRY_DELAY_MS = 1_000L
        private val UCID_REGEX = Regex("/channel/(UC[A-Za-z0-9_-]+)")
    }
}
