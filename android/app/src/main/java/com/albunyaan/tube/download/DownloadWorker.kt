package com.albunyaan.tube.download

import android.content.Context
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
    private val metrics: ExtractorMetricsReporter
) : CoroutineWorker(appContext, params) {

    private val notifications = DownloadNotifications(appContext)
    private val httpClient by lazy { OkHttpClient.Builder().build() }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: videoId
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, true)

        setForegroundAsync(notifications.createForegroundInfo(downloadId, title, 0))

        return runCatching {
            val mediaUrl = resolveTrack(videoId, audioOnly) ?: return@runCatching Result.failure()

            // Track download started only after policy/token validation succeeds
            runCatching {
                downloadService.trackDownloadStarted(
                    videoId = videoId,
                    quality = if (audioOnly) "audio" else "video"
                )
            }

            val contentLength = fetchContentLength(mediaUrl)
            if (contentLength != null) {
                storage.ensureSpace(contentLength)
            }
            val tempFile = storage.createTempFile(downloadId)
            try {
                downloadToFile(mediaUrl, tempFile, downloadId, title, contentLength)
                val finalFile = storage.commit(downloadId, audioOnly, tempFile)
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
            } catch (t: Throwable) {
                storage.discardTemp(tempFile)
                metrics.onDownloadFailed(downloadId, t)

                // Track download failed
                runCatching {
                    downloadService.trackDownloadFailed(
                        videoId = videoId,
                        errorReason = t.message ?: "Unknown error"
                    )
                }

                Result.retry()
            }
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

    private suspend fun resolveTrack(videoId: String, audioOnly: Boolean): String? {
        return try {
            // Check download policy first
            val policy = downloadService.checkDownloadPolicy(videoId)
            if (!policy.allowed) {
                android.util.Log.w(TAG, "Download not allowed for $videoId: ${policy.reason}")
                return null
            }

            // Generate download token (required for manifest endpoint)
            val downloadToken = downloadService.generateDownloadToken(videoId, eulaAccepted = true)

            // Get download manifest with stream URLs (requires token)
            val manifest = downloadService.getDownloadManifest(videoId, downloadToken.token)

            // Select best track
            val url = if (audioOnly) manifest.audioUrl else manifest.videoUrl
            if (url.isBlank()) {
                android.util.Log.w(TAG, "No ${if (audioOnly) "audio" else "video"} URL in manifest for $videoId")
                return null
            }

            url
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Failed to resolve track via backend API for $videoId", e)
            // Fall back to extractor if backend fails
            resolveTrackViaExtractor(videoId, audioOnly)
        }
    }

    private suspend fun resolveTrackViaExtractor(videoId: String, audioOnly: Boolean): String? {
        val resolved = repository.resolveStreams(videoId) ?: return null
        val audioTrack = resolved.audioTracks.maxByOrNull { it.bitrate ?: 0 }
        val videoTrack = resolved.videoTracks.maxWithOrNull(
            compareBy<VideoTrack> { it.height ?: 0 }
                .thenBy { it.bitrate ?: 0 }
        )
        return if (audioOnly) {
            audioTrack?.url
        } else {
            videoTrack?.url ?: audioTrack?.url
        }
    }

    private suspend fun fetchContentLength(url: String): Long? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).head().build()
        runCatching { httpClient.newCall(request).execute() }
            .getOrNull()
            ?.use { response ->
                response.header("Content-Length")?.toLongOrNull()?.takeIf { it > 0 }
            }
    }

    private suspend fun downloadToFile(
        url: String,
        target: File,
        downloadId: String,
        title: String,
        contentLength: Long?
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
                var lastProgress = 0
                while (input.read(buffer).also { read = it } != -1) {
                    if (isStopped) throw IOException("Download cancelled")
                    output.write(buffer, 0, read)
                    downloaded += read
                    if (totalBytes > 0) {
                        val progress = (downloaded * 100 / totalBytes).toInt().coerceIn(0, 100)
                        if (progress != lastProgress) {
                            lastProgress = progress
                            setProgress(workDataOf(KEY_PROGRESS to progress))
                            metrics.onDownloadProgress(downloadId, progress)
                            setForegroundAsync(notifications.createForegroundInfo(downloadId, title, progress))
                        }
                    }
                }
                setProgress(workDataOf(KEY_PROGRESS to 100))
                metrics.onDownloadProgress(downloadId, 100)
            }
        }
    }

    companion object {
        private const val TAG = "DownloadWorker"
    }
}
