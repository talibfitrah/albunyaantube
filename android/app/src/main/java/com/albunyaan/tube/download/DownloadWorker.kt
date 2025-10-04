package com.albunyaan.tube.download

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.core.app.NotificationCompat
import androidx.work.CoroutineWorker
import androidx.work.ForegroundInfo
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.albunyaan.tube.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.net.HttpURLConnection
import java.net.URL

class DownloadWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val KEY_VIDEO_ID = "video_id"
        const val KEY_VIDEO_TITLE = "video_title"
        const val KEY_VIDEO_URL = "video_url"
        const val KEY_PROGRESS = "progress"
        const val KEY_ERROR = "error"

        private const val CHANNEL_ID = "download_channel"
        private const val NOTIFICATION_ID = 1001
    }

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return@withContext Result.failure()
        val videoTitle = inputData.getString(KEY_VIDEO_TITLE) ?: "Video"
        val videoUrl = inputData.getString(KEY_VIDEO_URL) ?: return@withContext Result.failure()

        try {
            createNotificationChannel()
            setForeground(createForegroundInfo(videoTitle, 0))
            downloadVideo(videoId, videoTitle, videoUrl)
            Result.success()
        } catch (e: Exception) {
            Result.failure(workDataOf(KEY_ERROR to e.message))
        }
    }

    private suspend fun downloadVideo(videoId: String, videoTitle: String, videoUrl: String) {
        val outputDir = File(applicationContext.getExternalFilesDir(null), "downloads")
        if (!outputDir.exists()) outputDir.mkdirs()

        val outputFile = File(outputDir, "$videoId.mp4")
        val connection = URL(videoUrl).openConnection() as HttpURLConnection

        try {
            connection.connect()
            val fileLength = connection.contentLength
            val input = connection.inputStream
            val output = FileOutputStream(outputFile)
            val buffer = ByteArray(8192)
            var total: Long = 0
            var count: Int

            while (input.read(buffer).also { count = it } != -1) {
                if (isStopped) {
                    output.close()
                    input.close()
                    outputFile.delete()
                    return
                }
                total += count
                output.write(buffer, 0, count)
                if (fileLength > 0) {
                    val progress = (total * 100 / fileLength).toInt()
                    setProgressAsync(workDataOf(KEY_PROGRESS to progress))
                    setForeground(createForegroundInfo(videoTitle, progress))
                }
            }
            output.flush()
            output.close()
            input.close()
            showCompletionNotification(videoTitle)
        } finally {
            connection.disconnect()
        }
    }

    private fun createForegroundInfo(title: String, progress: Int): ForegroundInfo {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Downloading")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download)
            .setProgress(100, progress, progress == 0)
            .setOngoing(true)
            .build()
        return ForegroundInfo(NOTIFICATION_ID, notification)
    }

    private fun showCompletionNotification(title: String) {
        val notification = NotificationCompat.Builder(applicationContext, CHANNEL_ID)
            .setContentTitle("Download complete")
            .setContentText(title)
            .setSmallIcon(android.R.drawable.stat_sys_download_done)
            .setAutoCancel(true)
            .build()
        notificationManager.notify(NOTIFICATION_ID + 1, notification)
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "Downloads",
                NotificationManager.IMPORTANCE_LOW
            ).apply {
                description = "Video download notifications"
            }
            notificationManager.createNotificationChannel(channel)
        }
    }
}
