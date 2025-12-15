package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.extractor.cache.MetadataCache
import com.albunyaan.tube.data.model.ContentType
import java.io.IOException
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap
import java.util.regex.Pattern
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.schabi.newpipe.extractor.NewPipe
import org.schabi.newpipe.extractor.ServiceList
import org.schabi.newpipe.extractor.channel.ChannelInfo
import org.schabi.newpipe.extractor.exceptions.ExtractionException
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler
import org.schabi.newpipe.extractor.localization.ContentCountry
import org.schabi.newpipe.extractor.localization.Localization
import org.schabi.newpipe.extractor.playlist.PlaylistInfo
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory
import org.schabi.newpipe.extractor.stream.StreamInfo
import org.schabi.newpipe.extractor.stream.StreamType

class NewPipeExtractorClient(
    private val downloader: OkHttpDownloader,
    private val cache: MetadataCache,
    private val metrics: ExtractorMetricsReporter,
    private val clock: () -> Long = { System.currentTimeMillis() }
) : ExtractorClient {

    private val youtubeService = ServiceList.YouTube
    private val streamLinkHandlerFactory = YoutubeStreamLinkHandlerFactory.getInstance()
    private val channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance()
    private val playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance()
    private val localization = Localization.fromLocale(Locale.US)
    private val contentCountry = ContentCountry("US")
    private val streamCache = ConcurrentHashMap<String, CacheEntry<ResolvedStreams>>()

    init {
        initializeNewPipe()
    }

    /**
     * Resolve stream URLs for the given video.
     * @param forceRefresh If true, bypass cache and fetch fresh URLs (use for recovery from playback failures).
     *        Note: On forceRefresh, old cache is NOT removed before fetch - it's only replaced on success.
     *        This allows subsequent non-forceRefresh calls to still use cached data if the refresh failed.
     */
    suspend fun resolveStreams(videoId: String, forceRefresh: Boolean = false): ResolvedStreams? = withContext(Dispatchers.IO) {
        if (!YOUTUBE_ID_PATTERN.matcher(videoId).matches()) return@withContext null
        val now = clock()
        // Only use cache if not forcing refresh
        if (!forceRefresh) {
            streamCache[videoId]?.takeIf { now - it.timestamp <= STREAM_CACHE_TTL_MILLIS }?.let {
                metrics.onCacheHit(ContentType.VIDEOS, 1)
                return@withContext it.value
            }
        }
        // Don't remove cache entry before fetch - successful fetch will overwrite it,
        // and keeping it preserves fallback data if fresh fetch fails
        metrics.onCacheMiss(ContentType.VIDEOS, 1)
        val start = clock()
        try {
            val handler = streamLinkHandlerFactory.fromId(videoId)
            val extractor = youtubeService.getStreamExtractor(handler)
            // CRITICAL: Must call fetchPage() before getInfo() to get ALL video formats!
            extractor.fetchPage()
            val info = StreamInfo.getInfo(extractor)
            val urlGeneratedAt = clock()
            val resolved = info.toResolvedStreams(videoId, urlGeneratedAt) ?: return@withContext null
            streamCache[videoId] = CacheEntry(resolved, urlGeneratedAt)
            metrics.onStreamResolveSuccess(videoId, clock() - start)
            resolved
        } catch (c: CancellationException) {
            throw c
        } catch (throwable: Throwable) {
            metrics.onStreamResolveFailure(videoId, throwable)
            if (throwable is IOException || throwable is ExtractionException) {
                throw throwable
            } else {
                throw ExtractionException("Unexpected stream extraction failure", throwable)
            }
        }
    }

    override suspend fun fetchVideoMetadata(ids: List<String>): Map<String, VideoMetadata> {
        return fetch(ContentType.VIDEOS, ids, cache::getVideo, cache::putVideo) { id ->
            loadVideoMetadata(id)
        }
    }

    override suspend fun fetchChannelMetadata(ids: List<String>): Map<String, ChannelMetadata> {
        return fetch(ContentType.CHANNELS, ids, cache::getChannel, cache::putChannel) { id ->
            loadChannelMetadata(id)
        }
    }

    override suspend fun fetchPlaylistMetadata(ids: List<String>): Map<String, PlaylistMetadata> {
        return fetch(ContentType.PLAYLISTS, ids, cache::getPlaylist, cache::putPlaylist) { id ->
            loadPlaylistMetadata(id)
        }
    }

    private suspend fun <T> fetch(
        type: ContentType,
        ids: List<String>,
        cacheReader: (String, Long) -> T?,
        cacheWriter: (String, T, Long) -> Unit,
        loader: suspend (String) -> T?
    ): Map<String, T> {
        if (ids.isEmpty()) return emptyMap()
        val now = clock()
        val result = mutableMapOf<String, T>()
        val misses = mutableListOf<String>()
        ids.forEach { id ->
            val cached = cacheReader(id, now)
            if (cached != null) {
                result[id] = cached
            } else {
                misses += id
            }
        }
        if (result.isNotEmpty()) {
            metrics.onCacheHit(type, result.size)
        }
        if (misses.isEmpty()) {
            return result
        }
        metrics.onCacheMiss(type, misses.size)
        val start = clock()
        var successCount = 0
        for (id in misses) {
            val metadata = try {
                loader(id)
            } catch (t: Throwable) {
                if (t is CancellationException) throw t
                metrics.onFetchFailure(type, listOf(id), t)
                null
            }
            if (metadata != null) {
                successCount += 1
                val timestamp = clock()
                cacheWriter(id, metadata, timestamp)
                result[id] = metadata
            }
        }
        if (successCount > 0) {
            val duration = clock() - start
            metrics.onFetchSuccess(type, successCount, duration)
        }
        return result
    }

    private suspend fun loadVideoMetadata(id: String): VideoMetadata? = withContext(Dispatchers.IO) {
        if (!YOUTUBE_ID_PATTERN.matcher(id).matches()) return@withContext null
        runCatching {
            val handler = streamLinkHandlerFactory.fromId(id)
            val extractor = youtubeService.getStreamExtractor(handler)
            StreamInfo.getInfo(extractor)
        }.map { info ->
            if (info.streamType == StreamType.NONE) return@map null
            VideoMetadata(
                title = info.name,
                description = info.description?.content?.takeIf { it.isNotBlank() },
                thumbnailUrl = info.thumbnails.chooseBestUrl(),
                durationSeconds = info.duration.takeIf { it > 0 }?.toInt(),
                viewCount = info.viewCount.takeIf { it >= 0 }
            )
        }.getOrElse { throwable ->
            if (throwable is IOException || throwable is ExtractionException) {
                throw throwable
            } else {
                throw ExtractionException("Unexpected video extraction failure", throwable)
            }
        }
    }

    private suspend fun loadChannelMetadata(id: String): ChannelMetadata? = withContext(Dispatchers.IO) {
        val handler = createChannelLinkHandler(id) ?: return@withContext null
        runCatching {
            val extractor = youtubeService.getChannelExtractor(handler)
            extractor.fetchPage()
            ChannelInfo.getInfo(extractor)
        }.map { info ->
            ChannelMetadata(
                name = info.name,
                description = info.description?.takeIf { it.isNotBlank() },
                thumbnailUrl = info.avatars.chooseBestUrl(),
                subscriberCount = info.subscriberCount.takeIf { it >= 0 },
                videoCount = null
            )
        }.getOrElse { throwable ->
            if (throwable is IOException || throwable is ExtractionException) {
                throw throwable
            } else {
                throw ExtractionException("Unexpected channel extraction failure", throwable)
            }
        }
    }

    private suspend fun loadPlaylistMetadata(id: String): PlaylistMetadata? = withContext(Dispatchers.IO) {
        val handler = createPlaylistLinkHandler(id) ?: return@withContext null
        runCatching {
            val extractor = youtubeService.getPlaylistExtractor(handler)
            extractor.fetchPage()
            PlaylistInfo.getInfo(extractor)
        }.map { info ->
            PlaylistMetadata(
                title = info.name,
                description = info.description?.content?.takeIf { it.isNotBlank() },
                thumbnailUrl = info.thumbnails.chooseBestUrl(),
                itemCount = info.streamCount.toIntSafely()
            )
        }.getOrElse { throwable ->
            if (throwable is IOException || throwable is ExtractionException) {
                throw throwable
            } else {
                throw ExtractionException("Unexpected playlist extraction failure", throwable)
            }
        }
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

    private fun createChannelLinkHandler(rawId: String): ListLinkHandler? {
        return tryCandidates(rawId, ::expandChannelId) { candidate ->
            channelLinkHandlerFactory.fromId(candidate)
        }
    }

    private fun createPlaylistLinkHandler(rawId: String): ListLinkHandler? {
        return tryCandidates(rawId, ::expandPlaylistId) { candidate ->
            playlistLinkHandlerFactory.fromId(candidate)
        }
    }

    private fun <T> tryCandidates(
        original: String,
        expansion: (String) -> List<String>,
        block: (String) -> T
    ): T? {
        val attempts = buildList {
            add(original)
            addAll(expansion(original))
        }.distinct()
        for (candidate in attempts) {
            try {
                return block(candidate)
            } catch (_: Exception) {
                // try next
            }
        }
        return null
    }

    private fun expandChannelId(id: String): List<String> = buildList {
        if (id.startsWith("channel/") || id.startsWith("user/") || id.startsWith("c/") || id.startsWith("@")) {
            // already qualified
        } else if (id.startsWith("UC", ignoreCase = true)) {
            add("channel/$id")
        } else {
            add("c/$id")
        }
    }

    private fun expandPlaylistId(id: String): List<String> = buildList {
        if (id.startsWith("playlist?list=") || id.startsWith("channel/") || id.startsWith("user/")) {
            // already url-like
        } else if (id.startsWith("PL", ignoreCase = true) || id.startsWith("UU", ignoreCase = true)
            || id.startsWith("OL", ignoreCase = true)
        ) {
            add("playlist?list=$id")
        }
    }

    private fun StreamInfo.toResolvedStreams(streamId: String, generatedAt: Long): ResolvedStreams? {
        // Extract ALL video streams (muxed + video-only) for all quality options
        android.util.Log.d("NewPipeExtractor", "Video streams (muxed): ${videoStreams.size}")
        android.util.Log.d("NewPipeExtractor", "Video-only streams: ${videoOnlyStreams.size}")

        // Combine both muxed AND video-only streams to get ALL qualities
        val allVideoStreams = (videoStreams + videoOnlyStreams).distinctBy { it.content }
        android.util.Log.d("NewPipeExtractor", "Total combined video streams: ${allVideoStreams.size}")

        val videoTracks = allVideoStreams
            .filter { it.content.isNotBlank() }
            .map { stream ->
                // Create proper quality label based on height (e.g., "720p", "1080p")
                val properLabel = when {
                    stream.height > 0 -> "${stream.height}p${if (stream.fps > 30) stream.fps else ""}"
                    stream.width > 0 -> "${stream.width}x${stream.height}"
                    else -> stream.quality // Fallback to NewPipe's label
                }
                android.util.Log.d("NewPipeExtractor", "Video stream: $properLabel (${stream.width}x${stream.height}), bitrate=${stream.bitrate}, videoOnly=${stream.isVideoOnly}")
                VideoTrack(
                    url = stream.content,
                    mimeType = stream.format?.mimeType,
                    width = stream.width.takeIf { it > 0 },
                    height = stream.height.takeIf { it > 0 },
                    bitrate = stream.bitrate.takeIf { it > 0 },
                    qualityLabel = properLabel,
                    fps = stream.fps.takeIf { it > 0 },
                    isVideoOnly = stream.isVideoOnly
                )
            }
            // No additional deduplication - preserve ALL streams for maximum step-down flexibility.
            // URL-based deduplication already happened above (distinctBy { it.content }).
            // This preserves multiple bitrates at the same resolution for quality step-down.
            .sortedWith(
                compareByDescending<VideoTrack> { it.height ?: 0 }
                    .thenByDescending { it.bitrate ?: 0 }
            )

        android.util.Log.d("NewPipeExtractor", "Extracted ${videoTracks.size} unique video qualities: ${videoTracks.map { it.qualityLabel }}")

        val audioTracksRaw = audioStreams
            .filter { it.content.isNotBlank() }
            .map { stream ->
                AudioTrack(
                    url = stream.content,
                    mimeType = stream.format?.mimeType,
                    bitrate = stream.averageBitrate.takeIf { it > 0 },
                    codec = stream.codec
                )
            }

        val audioTracks = when {
            audioTracksRaw.isNotEmpty() -> audioTracksRaw
            videoTracks.isNotEmpty() -> listOf(
                AudioTrack(
                    url = videoTracks.first().url,
                    mimeType = videoTracks.first().mimeType,
                    bitrate = videoTracks.first().bitrate,
                    codec = null
                )
            )
            else -> emptyList()
        }

        if (videoTracks.isEmpty() && audioTracks.isEmpty()) return null

        val durationSeconds = duration.takeIf { it > 0 }?.toInt()

        // Extract HLS/DASH URLs for adaptive streaming (better for long videos)
        val hlsStreamUrl = hlsUrl?.takeIf { it.isNotBlank() }
        val dashStreamUrl = dashMpdUrl?.takeIf { it.isNotBlank() }

        if (hlsStreamUrl != null || dashStreamUrl != null) {
            android.util.Log.d("NewPipeExtractor", "Adaptive streams available: HLS=${hlsStreamUrl != null}, DASH=${dashStreamUrl != null}")
        }

        // TODO: Extract subtitle tracks from StreamInfo when NewPipe adds support
        return ResolvedStreams(
            streamId = streamId,
            videoTracks = videoTracks,
            audioTracks = audioTracks,
            subtitleTracks = emptyList(),
            durationSeconds = durationSeconds,
            hlsUrl = hlsStreamUrl,
            dashUrl = dashStreamUrl,
            urlGeneratedAt = generatedAt
        )
    }

    private fun Long.toIntSafely(): Int? = when {
        this <= 0L -> null
        this > Int.MAX_VALUE -> Int.MAX_VALUE
        else -> toInt()
    }

    private data class CacheEntry<T>(val value: T, val timestamp: Long)

    private fun initializeNewPipe() {
        synchronized(NewPipeExtractorClient::class.java) {
            val currentDownloader = runCatching { NewPipe.getDownloader() }.getOrNull()
            if (currentDownloader !== downloader) {
                NewPipe.init(downloader, localization, contentCountry)
                NewPipe.setupLocalization(localization, contentCountry)
            }
        }
    }

    companion object {
        // Increased cache TTL to 30 minutes for better performance
        // YouTube stream URLs typically expire after 6 hours
        private const val STREAM_CACHE_TTL_MILLIS = 30 * 60 * 1000L
        private val YOUTUBE_ID_PATTERN: Pattern = Pattern.compile("^[a-zA-Z0-9_-]{11}")
    }
}
