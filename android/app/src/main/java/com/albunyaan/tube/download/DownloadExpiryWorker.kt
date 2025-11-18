package com.albunyaan.tube.download

import android.content.Context
import androidx.hilt.work.HiltWorker
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import dagger.assisted.Assisted
import dagger.assisted.AssistedInject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.TimeUnit

/**
 * P4-T3: Periodic worker for cleaning up expired downloads.
 *
 * Runs daily (or as scheduled) to delete downloads older than 30 days.
 * Uses [DownloadExpiryPolicy] for cutoff calculation and [DownloadRepository] for deletion.
 */
@HiltWorker
class DownloadExpiryWorker @AssistedInject constructor(
    @Assisted context: Context,
    @Assisted workerParams: WorkerParameters,
    private val storage: DownloadStorage,
    private val expiryPolicy: DownloadExpiryPolicy
) : CoroutineWorker(context, workerParams) {

    override suspend fun doWork(): Result = withContext(Dispatchers.IO) {
        try {
            val cutoffMillis = expiryPolicy.cutoffMillis()
            val downloadFiles = storage.listAllDownloads()

            var deletedCount = 0
            var failedCount = 0

            for ((downloadId, file) in downloadFiles) {
                try {
                    // Use stored completion timestamp (source of truth), falling back to lastModified
                    val completedAt = storage.getCompletionTimestamp(downloadId)

                    // Skip files with invalid timestamps
                    if (completedAt == null || completedAt == 0L) {
                        android.util.Log.w(TAG, "Skipping file with invalid timestamp: ${file.name}")
                        continue
                    }

                    if (completedAt < cutoffMillis) {
                        val deleted = storage.delete(downloadId)
                        if (deleted) {
                            deletedCount++
                            android.util.Log.d(TAG, "Deleted expired download: ${file.name}")
                        } else {
                            failedCount++
                            android.util.Log.w(TAG, "Failed to delete expired download: ${file.name}")
                        }
                    }
                } catch (e: Exception) {
                    failedCount++
                    android.util.Log.e(TAG, "Error processing file ${file.name}", e)
                }
            }

            if (deletedCount > 0 || failedCount > 0) {
                android.util.Log.i(
                    TAG,
                    "Expiry cleanup completed: deleted=$deletedCount, failed=$failedCount " +
                    "(threshold: >${expiryPolicy.ttlDays} days)"
                )
            }

            // Return retry only if there were failures and no deletions succeeded
            if (failedCount > 0 && deletedCount == 0) {
                Result.retry()
            } else {
                Result.success()
            }
        } catch (e: Exception) {
            android.util.Log.e(TAG, "Expiry worker failed", e)
            Result.retry()
        }
    }

    companion object {
        private const val TAG = "DownloadExpiryWorker"

        /** Unique work name for periodic expiry cleanup */
        const val WORK_NAME = "download_expiry_cleanup"
    }
}
