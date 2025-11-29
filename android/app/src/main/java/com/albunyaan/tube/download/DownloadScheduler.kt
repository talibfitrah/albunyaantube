package com.albunyaan.tube.download

import androidx.work.Constraints
import androidx.work.Data
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.UUID
import java.util.concurrent.TimeUnit

class DownloadScheduler(
    private val workManager: WorkManager
) {

    fun schedule(request: DownloadRequest): UUID {
        val workId = UUID.randomUUID()
        val inputBuilder = Data.Builder()
            .putString(KEY_DOWNLOAD_ID, request.id)
            .putString(KEY_VIDEO_ID, request.videoId)
            .putString(KEY_TITLE, request.title)
            .putBoolean(KEY_AUDIO_ONLY, request.audioOnly)
            .putString(KEY_WORK_ID, workId.toString())

        // Add target height for quality selection (0 = best available)
        request.targetHeight?.let { inputBuilder.putInt(KEY_TARGET_HEIGHT, it) }

        // Add optional playlist context
        request.playlistId?.let { inputBuilder.putString(KEY_PLAYLIST_ID, it) }
        request.playlistTitle?.let { inputBuilder.putString(KEY_PLAYLIST_TITLE, it) }
        request.playlistQualityLabel?.let { inputBuilder.putString(KEY_PLAYLIST_QUALITY_LABEL, it) }
        request.indexInPlaylist?.let { inputBuilder.putInt(KEY_INDEX_IN_PLAYLIST, it) }
        request.playlistSize?.let { inputBuilder.putInt(KEY_PLAYLIST_SIZE, it) }

        val input = inputBuilder.build()

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

    /**
     * P4-T3: Schedule periodic expiry cleanup worker.
     *
     * Runs once per day to delete downloads older than 30 days.
     * Uses ExistingPeriodicWorkPolicy.KEEP to avoid re-scheduling if already queued.
     */
    fun scheduleExpiryCleanup() {
        val constraints = Constraints.Builder()
            .setRequiresBatteryNotLow(true)
            .build()

        val request = PeriodicWorkRequestBuilder<DownloadExpiryWorker>(
            1, TimeUnit.DAYS
        )
            .setConstraints(constraints)
            .addTag(TAG_EXPIRY_CLEANUP)
            .build()

        workManager.enqueueUniquePeriodicWork(
            DownloadExpiryWorker.WORK_NAME,
            ExistingPeriodicWorkPolicy.KEEP,
            request
        )

        android.util.Log.i("DownloadScheduler", "Scheduled periodic expiry cleanup (daily)")
    }

    /**
     * Cancel the periodic expiry cleanup worker.
     */
    fun cancelExpiryCleanup() {
        workManager.cancelUniqueWork(DownloadExpiryWorker.WORK_NAME)
    }

    companion object {
        private const val TAG_EXPIRY_CLEANUP = "com.albunyaan.tube.download.expiry"
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
        // Playlist context keys
        internal const val KEY_PLAYLIST_ID = "playlist_id"
        internal const val KEY_PLAYLIST_TITLE = "playlist_title"
        internal const val KEY_PLAYLIST_QUALITY_LABEL = "playlist_quality_label"
        internal const val KEY_INDEX_IN_PLAYLIST = "index_in_playlist"
        internal const val KEY_PLAYLIST_SIZE = "playlist_size"
        // Target video height for quality selection (0 for audio-only or best available)
        internal const val KEY_TARGET_HEIGHT = "target_height"

        private val NETWORK_CONSTRAINTS = Constraints.Builder()
            .setRequiredNetworkType(NetworkType.CONNECTED)
            .build()
    }
}
