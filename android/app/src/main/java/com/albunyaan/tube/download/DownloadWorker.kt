package com.albunyaan.tube.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.albunyaan.tube.ServiceLocator
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.data.extractor.AudioTrack
import com.albunyaan.tube.data.extractor.VideoTrack
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
import java.io.File
import java.io.IOException
import kotlin.math.max
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notifications = DownloadNotifications(appContext)
    private val storage = ServiceLocator.provideDownloadStorage()
    private val repository: PlayerRepository by lazy { ServiceLocator.providePlayerRepository() }
    private val metrics: ExtractorMetricsReporter by lazy { ServiceLocator.provideExtractorMetricsReporter() }
    private val httpClient by lazy { OkHttpClient.Builder().build() }

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: videoId
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, true)

        setForegroundAsync(notifications.createForegroundInfo(downloadId, title, 0))

        return runCatching {
            val mediaUrl = resolveTrack(videoId, audioOnly) ?: return@runCatching Result.failure()
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
                Result.retry()
            }
        }.getOrElse { throwable ->
            metrics.onDownloadFailed(downloadId, throwable)
            Result.retry()
        }
    }

    private suspend fun resolveTrack(videoId: String, audioOnly: Boolean): String? {
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
}

