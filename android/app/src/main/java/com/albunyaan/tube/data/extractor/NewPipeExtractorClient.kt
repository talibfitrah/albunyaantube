package com.albunyaan.tube.data.extractor

import com.albunyaan.tube.BuildConfig
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
import org.schabi.newpipe.extractor.stream.DeliveryMethod
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
        // Combine both muxed AND video-only streams to get ALL qualities
        val allVideoStreams = (videoStreams + videoOnlyStreams).distinctBy { it.content }

        // Debug logging for stream counts
        if (BuildConfig.DEBUG) {
            android.util.Log.d("NewPipeExtractor", "Video streams (muxed): ${videoStreams.size}")
            android.util.Log.d("NewPipeExtractor", "Video-only streams: ${videoOnlyStreams.size}")
            android.util.Log.d("NewPipeExtractor", "Total combined video streams: ${allVideoStreams.size}")
        }

        // PR6.1 Measurement: Log stream metadata for synthetic DASH feasibility assessment
        val durationSec = duration.takeIf { it > 0 }
        logSyntheticDashMetrics(streamId, allVideoStreams, audioStreams, durationSec)

        val videoTracks = allVideoStreams
            .filter { it.content.isNotBlank() }
            .map { stream ->
                // Create proper quality label based on height (e.g., "720p", "1080p")
                val properLabel = when {
                    stream.height > 0 -> "${stream.height}p${if (stream.fps > 30) stream.fps else ""}"
                    stream.width > 0 -> "${stream.width}x${stream.height}"
                    else -> stream.quality // Fallback to NewPipe's label
                }
                if (BuildConfig.DEBUG) {
                    android.util.Log.d("NewPipeExtractor", "Video stream: $properLabel (${stream.width}x${stream.height}), bitrate=${stream.bitrate}, videoOnly=${stream.isVideoOnly}")
                }
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

        if (BuildConfig.DEBUG) {
            android.util.Log.d("NewPipeExtractor", "Extracted ${videoTracks.size} unique video qualities: ${videoTracks.map { it.qualityLabel }}")
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

        // Extract HLS/DASH URLs for adaptive streaming (better for long videos)
        val hlsStreamUrl = hlsUrl?.takeIf { it.isNotBlank() }
        val dashStreamUrl = dashMpdUrl?.takeIf { it.isNotBlank() }

        // Log adaptive stream availability for debugging (without exposing full URLs with tokens)
        if (BuildConfig.DEBUG) {
            android.util.Log.d("NewPipeExtractor", "Stream $streamId: HLS=${hlsStreamUrl != null}, DASH=${dashStreamUrl != null}")
            if (hlsStreamUrl == null && dashStreamUrl == null) {
                android.util.Log.w("NewPipeExtractor", "WARNING: No adaptive manifests for $streamId - will use progressive (may buffer)")
            }
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

    /**
     * PR6.1 Measurement Pass: Log stream metadata for synthetic DASH feasibility assessment.
     *
     * This logs structured data for each video/audio stream to validate whether
     * YoutubeProgressiveDashManifestCreator can be used to wrap progressive streams.
     *
     * Go/No-Go Threshold: Phase A proceeds only if ≥80% of PROGRESSIVE_HTTP streams have:
     * - Valid init/index ranges (start <= end, all >= 0)
     * - Valid ItagItem present
     * - Usable duration (StreamInfo duration or ItagItem approxDurationMs)
     *
     * Filter logcat with: adb logcat -s SyntheticDASH
     *
     * NOTE: This logging is debug-only to avoid perf/noise in production builds.
     */
    private fun logSyntheticDashMetrics(
        videoId: String,
        videoStreams: List<org.schabi.newpipe.extractor.stream.VideoStream>,
        audioStreams: List<org.schabi.newpipe.extractor.stream.AudioStream>,
        durationSec: Long?
    ) {
        // Only run in debug builds to avoid perf impact in production
        if (!BuildConfig.DEBUG) return

        // Filter to only streams with actual content (matches playable stream reality)
        val playableVideoStreams = videoStreams.filter { it.content.isNotBlank() }
        val playableAudioStreams = audioStreams.filter { it.content.isNotBlank() }

        // Track MPD generation success counts for summary (named to reflect actual meaning)
        var mpdOKVideoCount = 0
        var mpdOKAudioCount = 0

        // Log video streams with eligibility check
        playableVideoStreams.forEach { stream ->
            val itagItem = stream.itagItem
            val approxDurationMs = itagItem?.approxDurationMs

            val streamData = SyntheticDashEligibility.StreamData(
                deliveryMethod = stream.deliveryMethod,
                isVideoOnly = stream.isVideoOnly,
                hasItagItem = itagItem != null,
                initStart = stream.initStart.toLong(),
                initEnd = stream.initEnd.toLong(),
                indexStart = stream.indexStart.toLong(),
                indexEnd = stream.indexEnd.toLong(),
                streamInfoDurationSec = durationSec,
                itagApproxDurationMs = approxDurationMs,
                hasContent = true // already filtered above
            )

            val eligibility = SyntheticDashEligibility.checkVideoStreamEligibility(streamData)

            // Attempt actual MPD generation for eligible streams (no-network validation)
            val mpdResult = if (eligibility.eligible && itagItem != null) {
                // Use ceiling division to avoid truncation to 0 for short videos (e.g., 500ms -> 1 sec, not 0)
                val fallbackDuration = durationSec ?: approxDurationMs?.let { (it + 999) / 1000 } ?: 1L
                SyntheticDashEligibility.tryGenerateMpd(stream.content, itagItem, fallbackDuration)
            } else {
                null
            }
            val mpdSuccess = mpdResult?.success == true
            if (mpdSuccess) mpdOKVideoCount++

            // Structured JSON log (single line for easy parsing)
            android.util.Log.d(
                SYNTHETIC_DASH_TAG,
                buildString {
                    append("{")
                    append("\"vid\":\"$videoId\",")
                    append("\"type\":\"video\",")
                    append("\"delivery\":\"${stream.deliveryMethod.name}\",")
                    append("\"itag\":${stream.itag},")
                    append("\"codec\":${SyntheticDashEligibility.jsonEscape(stream.codec ?: "unknown")},")
                    append("\"bitrate\":${stream.bitrate},")
                    append("\"w\":${stream.width},")
                    append("\"h\":${stream.height},")
                    append("\"fps\":${stream.fps},")
                    append("\"muxed\":${!stream.isVideoOnly},")
                    append("\"hasItag\":${itagItem != null},")
                    append("\"initS\":${stream.initStart},")
                    append("\"initE\":${stream.initEnd},")
                    append("\"idxS\":${stream.indexStart},")
                    append("\"idxE\":${stream.indexEnd},")
                    append("\"durSec\":${durationSec ?: -1},")
                    append("\"approxMs\":${approxDurationMs ?: -1},")
                    append("\"preCheck\":${eligibility.eligible},")
                    append("\"preReasons\":${SyntheticDashEligibility.jsonArray(eligibility.failureReasons)},")
                    append("\"mpdOK\":$mpdSuccess,")
                    append("\"mpdErr\":${SyntheticDashEligibility.jsonEscape(mpdResult?.errorMessage ?: "")}")
                    append("}")
                }
            )
        }

        // Log audio streams with eligibility check
        playableAudioStreams.forEach { stream ->
            val itagItem = stream.itagItem
            val approxDurationMs = itagItem?.approxDurationMs

            val streamData = SyntheticDashEligibility.StreamData(
                deliveryMethod = stream.deliveryMethod,
                isVideoOnly = false, // Not applicable for audio; checkAudioStreamEligibility ignores this field
                hasItagItem = itagItem != null,
                initStart = stream.initStart.toLong(),
                initEnd = stream.initEnd.toLong(),
                indexStart = stream.indexStart.toLong(),
                indexEnd = stream.indexEnd.toLong(),
                streamInfoDurationSec = durationSec,
                itagApproxDurationMs = approxDurationMs,
                hasContent = true
            )

            val eligibility = SyntheticDashEligibility.checkAudioStreamEligibility(streamData)

            // Attempt actual MPD generation for eligible streams (no-network validation)
            val mpdResult = if (eligibility.eligible && itagItem != null) {
                // Use ceiling division to avoid truncation to 0 for short videos (e.g., 500ms -> 1 sec, not 0)
                val fallbackDuration = durationSec ?: approxDurationMs?.let { (it + 999) / 1000 } ?: 1L
                SyntheticDashEligibility.tryGenerateMpd(stream.content, itagItem, fallbackDuration)
            } else {
                null
            }
            val mpdSuccess = mpdResult?.success == true
            if (mpdSuccess) mpdOKAudioCount++

            android.util.Log.d(
                SYNTHETIC_DASH_TAG,
                buildString {
                    append("{")
                    append("\"vid\":\"$videoId\",")
                    append("\"type\":\"audio\",")
                    append("\"delivery\":\"${stream.deliveryMethod.name}\",")
                    append("\"itag\":${stream.itag},")
                    append("\"codec\":${SyntheticDashEligibility.jsonEscape(stream.codec ?: "unknown")},")
                    append("\"bitrate\":${stream.averageBitrate},")
                    append("\"hasItag\":${itagItem != null},")
                    append("\"initS\":${stream.initStart},")
                    append("\"initE\":${stream.initEnd},")
                    append("\"idxS\":${stream.indexStart},")
                    append("\"idxE\":${stream.indexEnd},")
                    append("\"durSec\":${durationSec ?: -1},")
                    append("\"approxMs\":${approxDurationMs ?: -1},")
                    append("\"preCheck\":${eligibility.eligible},")
                    append("\"preReasons\":${SyntheticDashEligibility.jsonArray(eligibility.failureReasons)},")
                    append("\"mpdOK\":$mpdSuccess,")
                    append("\"mpdErr\":${SyntheticDashEligibility.jsonEscape(mpdResult?.errorMessage ?: "")}")
                    append("}")
                }
            )
        }

        // Log summary for this video
        val progressiveVideoCount = playableVideoStreams.count { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val progressiveAudioCount = playableAudioStreams.count { it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP }
        val videoOnlyProgressiveCount = playableVideoStreams.count {
            it.deliveryMethod == DeliveryMethod.PROGRESSIVE_HTTP && it.isVideoOnly
        }

        android.util.Log.d(
            SYNTHETIC_DASH_TAG,
            buildString {
                append("{")
                append("\"vid\":\"$videoId\",")
                append("\"type\":\"summary\",")
                append("\"totalVideo\":${playableVideoStreams.size},")
                append("\"totalAudio\":${playableAudioStreams.size},")
                append("\"progVideo\":$progressiveVideoCount,")
                append("\"progAudio\":$progressiveAudioCount,")
                append("\"videoOnlyProg\":$videoOnlyProgressiveCount,")
                append("\"mpdOKVideo\":$mpdOKVideoCount,")
                append("\"mpdOKAudio\":$mpdOKAudioCount,")
                append("\"durSec\":${durationSec ?: -1}")
                append("}")
            }
        )
    }

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

        // PR6.1 Measurement: Log tag for synthetic DASH feasibility assessment
        // Filter with: adb logcat -s SyntheticDASH
        private const val SYNTHETIC_DASH_TAG = "SyntheticDASH"
    }
}
