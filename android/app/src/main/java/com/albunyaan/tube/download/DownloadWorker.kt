package com.albunyaan.tube.download

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import androidx.work.workDataOf
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_AUDIO_ONLY
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_DOWNLOAD_ID
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_TITLE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_VIDEO_ID
import kotlinx.coroutines.delay

class DownloadWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    private val notifications = DownloadNotifications(appContext)

    override suspend fun doWork(): Result {
        val downloadId = inputData.getString(KEY_DOWNLOAD_ID) ?: return Result.failure()
        val videoId = inputData.getString(KEY_VIDEO_ID) ?: return Result.failure()
        val title = inputData.getString(KEY_TITLE) ?: videoId
        val audioOnly = inputData.getBoolean(KEY_AUDIO_ONLY, true)

        setForegroundAsync(notifications.createForegroundInfo(downloadId, title, 0))

        return try {
            // Placeholder: simulate download progress
            for (progress in 1..100 step 5) {
                delay(200)
                setProgress(workDataOf(KEY_PROGRESS to progress))
                setForegroundAsync(notifications.createForegroundInfo(downloadId, title, progress))
                if (isStopped) return Result.success()
            }
            notifications.notifyCompletion(downloadId, title)
            Result.success()
        } catch (t: Throwable) {
            Result.retry()
        }
    }
}
