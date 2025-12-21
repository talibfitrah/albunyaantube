package com.albunyaan.tube.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_AUDIO_ONLY
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_COMPLETED_AT
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_DOWNLOAD_ID
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_ERROR_REASON
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_PATH
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_SIZE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_MIME_TYPE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_TARGET_HEIGHT
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_TITLE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_VIDEO_ID
import com.albunyaan.tube.player.PlayerRepository
import com.albunyaan.tube.util.HttpConstants
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.CancellationException
import java.util.concurrent.TimeUnit
import okhttp3.OkHttpClient
import okhttp3.Request

/**
 * P3-T3: DownloadWorker with Hilt DI
 */
@HiltWorker
class DownloadWorker @AssistedInject constructor(
    @Assisted appContext: Context,
    @Assisted params: WorkerParameters,
    private val storage: DownloadStorage,
    private val repository: PlayerRepository,
    private val ffmpegMerger: FFmpegMerger
) : CoroutineWorker(appContext, params) {

    private val notifications = DownloadNotifications(appContext)

    /**
     * OkHttpClient configured for large file downloads.
     *
     * - User-Agent: YouTube may block requests without a proper User-Agent (HTTP 403)
     * - Extended timeouts: Large video files may take time to download, especially on
     *   slower connections. Default OkHttp timeout (10s) is too aggressive for downloads.
     *   Read timeout is set longer since data transfer can stall during download.
     */
    private val httpClient by lazy {
        OkHttpClient.Builder()
            .connectTimeout(30, TimeUnit.SECONDS)  // Connection establishment
            .readTimeout(60, TimeUnit.SECONDS)     // Data read during download
            .writeTimeout(30, TimeUnit.SECONDS)    // Request body write (minimal for downloads)
            .addInterceptor { chain ->
                val request = chain.request().newBuilder()
                    .header("User-Agent", HttpConstants.YOUTUBE_USER_AGENT)
                    .build()
                chain.proceed(request)
            }
            .build()
    }

    /**
     * Resolved stream information for download.
     */
    private sealed class ResolvedStream {
        /** Single progressive URL (audio-only or combined audio+video) */
        data class Progressive(val url: String) : ResolvedStream()
        /** Separate video and audio URLs requiring FFmpeg merge */
        data class Split(val videoUrl: String, val audioUrl: String) : ResolvedStream()
    }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_REASON to DownloadErrorCode.INVALID_INPUT))
        val videoId = inputData.getString(KEY_VIDEO_ID)
            ?: return Result.failure(workDataOf(KEY_ERROR_REASON to DownloadErrorCode.INVALID_INPUT))
        val title = inputData.getString(KEY_TITLE) ?: videoId
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, true)
        // Target height for quality selection (0 means not specified - use best available)
        val targetHeight = inputData.getInt(KEY_TARGET_HEIGHT, 0).takeIf { it > 0 }

        setForegroundAsync(notifications.createForegroundInfo(downloadId, title, 0))

        return runCatching {
            val resolvedStream = resolveStream(videoId, audioOnly, targetHeight)
                ?: throw NoStreamException(
                    videoId = videoId,
                    errorCode = DownloadErrorCode.NO_STREAM,
                    message = "Failed to resolve stream for $videoId"
                )

            val finalFile = when (resolvedStream) {
                is ResolvedStream.Progressive -> {
                    downloadProgressiveStream(resolvedStream.url, downloadId, title, audioOnly)
                }
                is ResolvedStream.Split -> {
                    downloadAndMergeStreams(resolvedStream, downloadId, title)
                }
            }

            val mimeType = if (audioOnly) "audio/mp4" else "video/mp4"
            notifications.notifyCompletion(downloadId, title)

            Result.success(
                workDataOf(
                    KEY_FILE_PATH to finalFile.absolutePath,
                    KEY_FILE_SIZE to finalFile.length(),
                    KEY_COMPLETED_AT to System.currentTimeMillis(),
                    KEY_MIME_TYPE to mimeType,
                    KEY_PROGRESS to 100
                )
            )
        }.getOrElse { throwable ->
            // If CancellationException, re-throw immediately so WorkManager reports CANCELLED
            if (throwable is CancellationException) {
                Log.d(TAG, "Download cancelled by user for $videoId")
                throw throwable
            }

            // Double-check: if worker was stopped but exception isn't CancellationException,
            // convert it so WorkManager reports CANCELLED state instead of FAILED
            if (isStopped) {
                Log.d(TAG, "Download stopped (converting to cancel) for $videoId")
                throw CancellationException("Download cancelled by user")
            }

            Log.e(TAG, "Download failed for $videoId", throwable)

            // Determine error code for UI localization
            // Uses type-safe exception checks - no fragile string matching
            // These codes are mapped to localized strings in DownloadsAdapter
            val errorCode = when (throwable) {
                // Type-safe HTTP status code detection
                is DownloadHttpException -> when {
                    throwable.isForbidden -> DownloadErrorCode.HTTP_403
                    throwable.isRateLimited -> DownloadErrorCode.HTTP_429
                    else -> DownloadErrorCode.NETWORK
                }
                // Type-safe exception classification (no brittle string matching)
                is FFmpegMergeException -> DownloadErrorCode.MERGE
                // NoStreamException carries its own error code for specific failure reason
                is NoStreamException -> throwable.errorCode
                // General network errors (IOException but not our specific subtypes)
                is IOException -> DownloadErrorCode.NETWORK
                else -> DownloadErrorCode.UNKNOWN
            }

            // Return failure with error code (UI maps to localized string)
            Result.failure(workDataOf(KEY_ERROR_REASON to errorCode))
        }
    }

    /**
     * Download a single progressive stream (audio-only or combined).
     */
    private suspend fun downloadProgressiveStream(
        url: String,
        downloadId: String,
        title: String,
        audioOnly: Boolean
    ): File {
        val contentLength = fetchContentLength(url)
        if (contentLength != null) {
            storage.ensureSpace(contentLength)
        }
        val tempFile = storage.createTempFile(downloadId)
        try {
            downloadToFile(url, tempFile, downloadId, title, contentLength)
            return storage.commit(downloadId, audioOnly, tempFile)
        } catch (t: Throwable) {
            storage.discardTemp(tempFile)
            throw t
        }
    }

    /**
     * Download separate video and audio streams, then merge with FFmpeg.
     */
    private suspend fun downloadAndMergeStreams(
        split: ResolvedStream.Split,
        downloadId: String,
        title: String
    ): File {
        // Get temp directory from storage once, then create specific temp files
        val baseTempFile = storage.createTempFile(downloadId)
        val tempDir = baseTempFile.parentFile ?: throw IOException("Cannot determine temp directory")
        baseTempFile.delete() // Clean up the base temp file we don't need

        val videoTempFile = File(tempDir, "${downloadId}_video.tmp")
        val audioTempFile = File(tempDir, "${downloadId}_audio.tmp")
        val mergedTempFile = File(tempDir, "${downloadId}_merged.tmp")

        try {
            // Download video stream (50% of progress)
            val videoLength = fetchContentLength(split.videoUrl)
            val audioLength = fetchContentLength(split.audioUrl)
            val totalLength = (videoLength ?: 0) + (audioLength ?: 0)

            if (totalLength > 0) {
                storage.ensureSpace(totalLength)
            }

            Log.d(TAG, "Downloading video stream for merge: ${split.videoUrl}")
            downloadToFile(split.videoUrl, videoTempFile, downloadId, title, videoLength, progressOffset = 0, progressScale = 0.4f)

            // Download audio stream (40% of progress)
            Log.d(TAG, "Downloading audio stream for merge: ${split.audioUrl}")
            downloadToFile(split.audioUrl, audioTempFile, downloadId, title, audioLength, progressOffset = 40, progressScale = 0.4f)

            // Merge with FFmpeg (20% of progress)
            Log.d(TAG, "Merging video and audio streams with FFmpeg")
            setProgress(workDataOf(KEY_PROGRESS to 80))
            notifications.updateProgress(downloadId, title, 80)

            // merge() throws FFmpegMergeException on failure
            ffmpegMerger.merge(videoTempFile, audioTempFile, mergedTempFile)

            // Commit merged file
            val finalFile = storage.commit(downloadId, audioOnly = false, mergedTempFile)

            // Cleanup temp files (commit may copy rather than move)
            videoTempFile.delete()
            audioTempFile.delete()
            mergedTempFile.delete()

            return finalFile
        } catch (t: Throwable) {
            // Cleanup on failure
            videoTempFile.delete()
            audioTempFile.delete()
            mergedTempFile.delete()
            throw t
        }
    }

    /**
     * Resolve stream URLs using local NewPipe extractor.
     *
     * Uses the on-device extractor directly - no backend calls needed.
     * The backend only manages curated content lists (IDs), not stream extraction.
     * This approach scales to millions of users from a single VPS.
     *
     * @param videoId YouTube video ID
     * @param audioOnly Whether to download audio only
     * @param targetHeight Target video height for quality selection (null = best available)
     */
    private suspend fun resolveStream(videoId: String, audioOnly: Boolean, targetHeight: Int?): ResolvedStream? {
        return resolveStreamViaExtractor(videoId, audioOnly, targetHeight)
    }

    /**
     * Resolve stream via local NewPipe extractor.
     *
     * Stream Selection Strategy:
     * 1. Audio-only: Use best audio track (progressive)
     * 2. Video: Prefer muxed (audio+video) tracks when available at target quality
     * 3. If best quality is video-only: Pair with best audio for FFmpeg merge
     *
     * **MP4 Compatibility for FFmpeg Merge:**
     * When using -c copy (stream copy, no re-encoding), we must ensure codecs are MP4-compatible:
     * - Video: H.264 (AVC) - mimeType contains "video/mp4" or "avc"
     * - Audio: AAC/M4A - mimeType contains "audio/mp4" or "m4a" (NOT OPUS/WebM)
     *
     * If incompatible codecs (VP9/WebM video or OPUS/WebM audio), fall back to muxed track
     * to avoid FFmpeg merge failures.
     *
     * @param videoId YouTube video ID
     * @param audioOnly Whether to download audio only
     * @param targetHeight Target video height for quality selection (null = best available)
     */
    private suspend fun resolveStreamViaExtractor(
        videoId: String,
        audioOnly: Boolean,
        targetHeight: Int?
    ): ResolvedStream? {
        val resolved = repository.resolveStreams(videoId) ?: return null

        // For audio-only, prefer AAC/M4A but fall back to any audio if not available
        val mp4CompatibleAudio = resolved.audioTracks.filter { isAudioMp4Compatible(it) }
        val bestMp4Audio = mp4CompatibleAudio.maxByOrNull { it.bitrate ?: 0 }
        val bestAnyAudio = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }

        // Audio-only download: return progressive audio stream
        if (audioOnly) {
            // Prefer MP4-compatible audio, but any audio works for progressive download
            return (bestMp4Audio ?: bestAnyAudio)?.url?.let { ResolvedStream.Progressive(it) }
        }

        // Video download: prefer muxed tracks, fall back to video-only + audio merge

        // Comparator for video quality (height first, then bitrate)
        val videoQualityComparator = compareBy<VideoTrack> { it.height ?: 0 }
            .thenBy { it.bitrate ?: 0 }

        // Filter tracks by target height if specified
        val eligibleTracks = if (targetHeight != null) {
            resolved.videoTracks.filter { it.height != null && it.height <= targetHeight }
                .ifEmpty { resolved.videoTracks.filter { it.height != null } }
        } else {
            resolved.videoTracks.filter { it.height != null }
        }

        // Separate muxed and video-only tracks
        val muxedTracks = eligibleTracks.filter { !it.isVideoOnly }
        val videoOnlyTracks = eligibleTracks.filter { it.isVideoOnly }

        // For merge, filter to MP4-compatible video-only tracks (H.264)
        val mp4VideoOnlyTracks = videoOnlyTracks.filter { isVideoMp4Compatible(it) }

        // Get best of each type
        val bestMuxedTrack = muxedTracks.maxWithOrNull(videoQualityComparator)
        val bestMp4VideoOnlyTrack = mp4VideoOnlyTracks.maxWithOrNull(videoQualityComparator)

        // Decision logic:
        // 1. If we have MP4-compatible video-only at higher quality than muxed AND MP4-compatible audio, merge
        // 2. If muxed track available at acceptable quality, use it (no codec concerns)
        // 3. Fall back to any video-only as mute if nothing else works

        val muxedHeight = bestMuxedTrack?.height ?: 0
        val mp4VideoOnlyHeight = bestMp4VideoOnlyTrack?.height ?: 0

        return when {
            // MP4-compatible video-only is higher quality and we have MP4-compatible audio for merge
            bestMp4VideoOnlyTrack != null && bestMp4Audio != null && mp4VideoOnlyHeight > muxedHeight -> {
                Log.d(TAG, "Using MP4-compatible video-only (${mp4VideoOnlyHeight}p) + AAC audio merge (muxed available: ${muxedHeight}p)")
                ResolvedStream.Split(bestMp4VideoOnlyTrack.url, bestMp4Audio.url)
            }
            // Muxed track available - use progressive download (always works, no codec concerns)
            bestMuxedTrack != null -> {
                Log.d(TAG, "Using muxed track (${muxedHeight}p)")
                ResolvedStream.Progressive(bestMuxedTrack.url)
            }
            // MP4-compatible video-only with MP4-compatible audio - merge even if muxed not available
            bestMp4VideoOnlyTrack != null && bestMp4Audio != null -> {
                Log.d(TAG, "Only MP4-compatible video-only available (${mp4VideoOnlyHeight}p), using AAC audio merge")
                ResolvedStream.Split(bestMp4VideoOnlyTrack.url, bestMp4Audio.url)
            }
            // Non-MP4 video-only (e.g., VP9) - can't merge safely, fail with clear error
            videoOnlyTracks.isNotEmpty() -> {
                val bestAny = videoOnlyTracks.maxWithOrNull(videoQualityComparator)
                val reason = if (bestMp4Audio == null) "no AAC audio available" else "VP9/WebM video codec"
                val errorCode = if (bestMp4Audio == null) {
                    DownloadErrorCode.VIDEO_AUDIO_MISMATCH
                } else {
                    DownloadErrorCode.NO_COMPATIBLE_VIDEO
                }
                Log.e(TAG, "Cannot download video for $videoId: $reason (would result in muted video). " +
                    "Available: ${bestAny?.height}p ${bestAny?.mimeType}. Error code: $errorCode")
                throw NoStreamException(
                    videoId = videoId,
                    errorCode = errorCode,
                    message = "Cannot download video: $reason. Video-only stream (${bestAny?.height}p) " +
                        "cannot be merged with audio."
                )
            }
            // No video tracks at all - fail with clear error (user requested video, not audio)
            bestAnyAudio != null -> {
                Log.e(TAG, "No video tracks available for $videoId, only audio. " +
                    "Error code: ${DownloadErrorCode.NO_COMPATIBLE_VIDEO}")
                throw NoStreamException(
                    videoId = videoId,
                    errorCode = DownloadErrorCode.NO_COMPATIBLE_VIDEO,
                    message = "No video tracks available for download. Only audio streams found."
                )
            }
            // No streams at all
            else -> null
        }
    }

    /**
     * Check if a video track is MP4/H.264 compatible for FFmpeg stream copy.
     * H.264 (AVC) works with -c copy to MP4 container.
     * VP9/WebM does not.
     */
    private fun isVideoMp4Compatible(track: VideoTrack): Boolean {
        val mimeType = track.mimeType?.lowercase() ?: return false
        val codec = track.syntheticDashMetadata?.codec?.lowercase()

        // Check mimeType first (most reliable)
        if (mimeType.contains("video/mp4") || mimeType.contains("video/3gpp")) {
            return true
        }

        // Check codec if available (avc = H.264, hvc/hev = H.265)
        if (codec != null && (codec.startsWith("avc") || codec.startsWith("hvc") || codec.startsWith("hev"))) {
            return true
        }

        // WebM/VP9 is not compatible
        return false
    }

    /**
     * Check if an audio track is MP4/AAC compatible for FFmpeg stream copy.
     * AAC/M4A works with -c copy to MP4 container.
     * OPUS/WebM does not.
     */
    private fun isAudioMp4Compatible(track: com.albunyaan.tube.data.extractor.AudioTrack): Boolean {
        val mimeType = track.mimeType?.lowercase() ?: return false
        val codec = track.codec?.lowercase()

        // Check mimeType first (most reliable)
        if (mimeType.contains("audio/mp4") || mimeType.contains("audio/m4a") ||
            mimeType.contains("audio/aac") || mimeType.contains("audio/3gpp")) {
            return true
        }

        // Check codec if available
        if (codec != null && (codec.startsWith("mp4a") || codec.startsWith("aac"))) {
            return true
        }

        // OPUS/WebM is not compatible with MP4 container
        return false
    }

    private suspend fun fetchContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        runCatching { httpClient.newCall(request).execute() }
            .getOrNull()
            ?.use { response ->
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            }
    }

    /**
     * Download file with progress reporting.
     *
     * @param progressOffset Base progress value (for multi-part downloads)
     * @param progressScale Scale factor for this download's progress (0.0 - 1.0)
     */
    private suspend fun downloadToFile(
        url: String,
        target: File,
        downloadId: String,
        title: String,
        contentLength: Long?,
        progressOffset: Int = 0,
        progressScale: Float = 1.0f
    ) = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).build()
        httpClient.newCall(request).execute().use { response ->
            if (!response.isSuccessful) {
                throw DownloadHttpException(response.code, "HTTP ${response.code}: ${response.message}")
            }
            val body = response.body ?: throw IOException("Empty body")
            val totalBytes = max(contentLength ?: -1, body.contentLength())
            val input = body.byteStream()
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var downloaded = 0L
                var lastProgress = -1
                while (input.read(buffer).also { read = it } != -1) {
                    // Throw CancellationException directly so WorkManager reports CANCELLED (not FAILED)
                    if (isStopped) throw CancellationException("Download cancelled by user")
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val rawProgress = (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                        val scaledProgress = progressOffset + (rawProgress * progressScale).toInt()
                        if (scaledProgress != lastProgress) {
                            lastProgress = scaledProgress
                            setProgress(workDataOf(KEY_PROGRESS to scaledProgress))
                            setForegroundAsync(notifications.createForegroundInfo(downloadId, title, scaledProgress))
                        }
                    }
                }
                // Report completion of this segment
                val finalProgress = progressOffset + (100 * progressScale).toInt()
                setProgress(workDataOf(KEY_PROGRESS to finalProgress))
            }
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
    }
}
