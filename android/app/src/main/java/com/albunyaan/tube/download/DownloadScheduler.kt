package com.albunyaan.tube.download

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID

class DownloadScheduler(
    private val workManager: WorkManager
) {

    fun schedule(request: DownloadRequest): UUID {
        val workId = UUID.randomUUID()
        val input = Data.Builder()
            .putString(KEY_DOWNLOAD_ID, request.id)
            .putString(KEY_VIDEO_ID, request.videoId)
            .putString(KEY_TITLE, request.title)
            .putBoolean(KEY_AUDIO_ONLY, request.audioOnly)
            .putString(KEY_WORK_ID, workId.toString())
            .build()

        val workRequest = OneTimeWorkRequestBuilder<DownloadWorker>()
            .setId(workId)
            .setConstraints(NETWORK_CONSTRAINTS)
            .setInputData(input)
            .addTag("com.albunyaan.tube.download")
            .addTag("download_${request.id}")
            .build()

        workManager.beginUniqueWork(request.id, ExistingWorkPolicy.REPLACE, workRequest).enqueue()
        return workId
    }

    fun cancel(requestId: String) {
        workManager.cancelUniqueWork(requestId)
    }

    companion object {
        internal const val KEY_DOWNLOAD_ID = "download_id"
        internal const val KEY_VIDEO_ID = "video_id"
        internal const val KEY_TITLE = "title"
        internal const val KEY_AUDIO_ONLY = "audio_only"
        internal const val KEY_WORK_ID = "work_id"
        internal const val KEY_PROGRESS = "progress"
        internal const val KEY_FILE_PATH = "file_path"
        internal const val KEY_FILE_SIZE = "file_size"
        internal const val KEY_COMPLETED_AT = "completed_at"
        internal const val KEY_MIME_TYPE = "mime_type"

        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
