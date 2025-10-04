package com.albunyaan.tube.download

import android.content.Context
import androidx.lifecycle.LiveData
import androidx.work.*
import java.util.UUID

class DownloadManager(private val context: Context) {

    private val workManager = WorkManager.getInstance(context)

    fun downloadVideo(videoId: String, videoTitle: String, videoUrl: String): UUID {
        val downloadRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setInputData(
                workDataOf(
                    DownloadWorker.KEY_VIDEO_ID to videoId,
                    DownloadWorker.KEY_VIDEO_TITLE to videoTitle,
                    DownloadWorker.KEY_VIDEO_URL to videoUrl
                )
            )
            .addTag("download_$videoId")
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueue(downloadRequest)
        return downloadRequest.id
    }

    fun getDownloadProgress(workId: UUID): LiveData<WorkInfo> {
        return workManager.getWorkInfoByIdLiveData(workId)
    }

    fun cancelDownload(workId: UUID) {
        workManager.cancelWorkById(workId)
    }

    fun getAllDownloads(): LiveData<List<WorkInfo>> {
        return workManager.getWorkInfosByTagLiveData("download")
    }

    fun cancelAllDownloads() {
        workManager.cancelAllWorkByTag("download")
    }
}
