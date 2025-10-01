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

    suspend fun resolveStreams(videoId: String): ResolvedStreams? = withContext(Dispatchers.IO) {
        if (!YOUTUBE_ID_PATTERN.matcher(videoId).matches()) return@withContext null
        val now = clock()
        streamCache[videoId]?.takeIf { now - it.timestamp <= STREAM_CACHE_TTL_MILLIS }?.let {
            metrics.onCacheHit(ContentType.VIDEOS, 1)
            return@withContext it.value
        }
        metrics.onCacheMiss(ContentType.VIDEOS, 1)
        val start = clock()
        try {
            val handler = streamLinkHandlerFactory.fromId(videoId)
            val extractor = youtubeService.getStreamExtractor(handler)
            val info = StreamInfo.getInfo(extractor)
            val resolved = info.toResolvedStreams(videoId) ?: return@withContext null
            streamCache[videoId] = CacheEntry(resolved, clock())
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

    private fun StreamInfo.toResolvedStreams(streamId: String): ResolvedStreams? {
        val videoTracks = videoStreams
            .filter { !it.isVideoOnly && it.content.isNotBlank() }
            .map { stream ->
                VideoTrack(
                    url = stream.content,
                    mimeType = stream.format?.mimeType,
                    width = stream.width.takeIf { it > 0 },
                    height = stream.height.takeIf { it > 0 },
                    bitrate = stream.bitrate.takeIf { it > 0 },
                    qualityLabel = stream.quality,
                    fps = stream.fps.takeIf { it > 0 }
                )
            }

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
        return ResolvedStreams(streamId, videoTracks, audioTracks, durationSeconds)
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
        private const val STREAM_CACHE_TTL_MILLIS = 10 * 60 * 1000L
        private val YOUTUBE_ID_PATTERN: Pattern = Pattern.compile("^[a-zA-Z0-9_-]{11}")
    }
}
