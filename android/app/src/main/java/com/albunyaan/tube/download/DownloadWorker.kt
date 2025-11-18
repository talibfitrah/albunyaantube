package com.albunyaan.tube.download

import android.content.Context
import android.util.Log
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.extractor.VideoTrack
import com.albunyaan.tube.data.source.RetrofitDownloadService
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_AUDIO_ONLY
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_DOWNLOAD_ID
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_PATH
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_SIZE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_COMPLETED_AT
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_MIME_TYPE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_TITLE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_VIDEO_ID
import com.albunyaan.tube.player.PlayerRepository
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
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
    private val downloadService: RetrofitDownloadService,
    private val repository: PlayerRepository,
    private val metrics: ExtractorMetricsReporter,
    private val ffmpegMerger: FFmpegMerger
) : CoroutineWorker(appContext, params) {

    private val notifications = DownloadNotifications(appContext)
    private val httpClient by lazy { OkHttpClient.Builder().build() }

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
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: videoId
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, true)

        setForegroundAsync(notifications.createForegroundInfo(downloadId, title, 0))

        return runCatching {
            val resolvedStream = resolveStream(videoId, audioOnly) ?: return@runCatching Result.failure()

            // Track download started only after policy/token validation succeeds
            runCatching {
                downloadService.trackDownloadStarted(
                    videoId = videoId,
                    quality = if (audioOnly) "audio" else "video"
                )
            }

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

            // Track download completed
            runCatching {
                downloadService.trackDownloadCompleted(
                    videoId = videoId,
                    quality = if (audioOnly) "audio" else "video",
                    fileSize = finalFile.length()
                )
            }

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
            metrics.onDownloadFailed(downloadId, throwable)

            // Track download failed
            runCatching {
                downloadService.trackDownloadFailed(
                    videoId = videoId,
                    errorReason = throwable.message ?: "Unknown error"
                )
            }

            Result.retry()
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

            val mergeSuccess = ffmpegMerger.merge(videoTempFile, audioTempFile, mergedTempFile)

            if (!mergeSuccess) {
                throw IOException("FFmpeg merge failed")
            }

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
     * Resolve stream URLs from backend API or fallback to local extractor.
     */
    private suspend fun resolveStream(videoId: String, audioOnly: Boolean): ResolvedStream? {
        return try {
            // Check download policy first
            val policy = downloadService.checkDownloadPolicy(videoId)
            if (!policy.allowed) {
                Log.w(TAG, "Download not allowed for $videoId: ${policy.reason}")
                return null
            }

            // Generate download token (required for manifest endpoint)
            val downloadToken = downloadService.generateDownloadToken(videoId, eulaAccepted = true)

            // Get download manifest with stream URLs (requires token)
            // Pass FFmpeg availability so backend only returns split streams if client can merge
            val supportsMerging = ffmpegMerger.isAvailable()
            val manifest = downloadService.getDownloadManifest(videoId, downloadToken.token, supportsMerging)
            val stream = manifest.selectedStream

            // Get URL based on stream type
            when {
                stream.requiresMerging -> {
                    // P4-T4: Check FFmpeg availability before returning split stream
                    if (!ffmpegMerger.isAvailable()) {
                        Log.w(TAG, "FFmpeg not available, falling back to progressive stream for $videoId")
                        // Fall back to progressive URL if available
                        val progressiveUrl = stream.progressiveUrl
                        if (!progressiveUrl.isNullOrBlank()) {
                            Log.d(TAG, "Using progressive fallback: ${stream.qualityLabel}")
                            return ResolvedStream.Progressive(progressiveUrl)
                        }
                        // If no progressive available, try audio-only as last resort
                        val audioUrl = stream.audioUrl
                        if (!audioUrl.isNullOrBlank() && audioOnly) {
                            Log.d(TAG, "Using audio-only fallback")
                            return ResolvedStream.Progressive(audioUrl)
                        }
                        // No fallback available - fail the download
                        Log.e(TAG, "FFmpeg unavailable and no progressive fallback for $videoId")
                        return null
                    }

                    // FFmpeg available - return split stream for merging
                    val videoUrl = stream.videoUrl
                    val audioUrl = stream.audioUrl
                    if (videoUrl.isNullOrBlank() || audioUrl.isNullOrBlank()) {
                        Log.w(TAG, "Missing video or audio URL for merge: video=$videoUrl, audio=$audioUrl")
                        return null
                    }
                    Log.d(TAG, "Resolved split stream for merge: ${stream.qualityLabel}")
                    ResolvedStream.Split(videoUrl, audioUrl)
                }
                audioOnly -> {
                    val url = stream.audioUrl ?: stream.progressiveUrl
                    if (url.isNullOrBlank()) {
                        Log.w(TAG, "No audio URL available in manifest for $videoId")
                        return null
                    }
                    ResolvedStream.Progressive(url)
                }
                else -> {
                    val url = stream.progressiveUrl
                    if (url.isNullOrBlank()) {
                        Log.w(TAG, "No progressive URL available in manifest for $videoId")
                        return null
                    }
                    ResolvedStream.Progressive(url)
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to resolve stream via backend API for $videoId", e)
            // Fall back to extractor if backend fails
            resolveStreamViaExtractor(videoId, audioOnly)
        }
    }

    /**
     * Fallback: resolve stream via local NewPipe extractor.
     */
    private suspend fun resolveStreamViaExtractor(videoId: String, audioOnly: Boolean): ResolvedStream? {
        val resolved = repository.resolveStreams(videoId) ?: return null
        val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
        val videoTrack = resolved.videoTracks.maxWithOrNull(
            compareBy<VideoTrack> { it.height ?: 0 }
                .thenBy { it.bitrate ?: 0 }
        )

        val url = if (audioOnly) {
            audioTrack?.url
        } else {
            videoTrack?.url ?: audioTrack?.url
        }

        return url?.let { ResolvedStream.Progressive(it) }
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
            if (!response.isSuccessful) throw IOException("Unexpected code $response")
            val body = response.body ?: throw IOException("Empty body")
            val totalBytes = max(contentLength ?: -1, body.contentLength())
            val input = body.byteStream()
            target.outputStream().use { output ->
                val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                var read: Int
                var downloaded = 0L
                var lastProgress = -1
                while (input.read(buffer).also { read = it } != -1) {
                    if (isStopped) throw IOException("Download cancelled")
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val rawProgress = (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                        val scaledProgress = progressOffset + (rawProgress * progressScale).toInt()
                        if (scaledProgress != lastProgress) {
                            lastProgress = scaledProgress
                            setProgress(workDataOf(KEY_PROGRESS to scaledProgress))
                            metrics.onDownloadProgress(downloadId, scaledProgress)
                            setForegroundAsync(notifications.createForegroundInfo(downloadId, title, scaledProgress))
                        }
                    }
                }
                // Report completion of this segment
                val finalProgress = progressOffset + (100 * progressScale).toInt()
                setProgress(workDataOf(KEY_PROGRESS to finalProgress))
                metrics.onDownloadProgress(downloadId, finalProgress)
            }
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
    }
}
