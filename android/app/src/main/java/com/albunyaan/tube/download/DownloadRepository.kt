package com.albunyaan.tube.download

import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_PATH
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_SIZE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_COMPLETED_AT
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_MIME_TYPE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.filterNotNull
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

interface DownloadRepository {
    val downloads: StateFlow<List<DownloadEntry>>

    fun enqueue(request: DownloadRequest)
    fun pause(requestId: String)
    fun resume(requestId: String)
    fun cancel(requestId: String)
}

class DefaultDownloadRepository(
    private val workManager: WorkManager,
    private val scheduler: DownloadScheduler,
    private val storage: DownloadStorage,
    private val metrics: ExtractorMetricsReporter,
    private val scope: CoroutineScope
) : DownloadRepository {

    private val entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    private val workIds = ConcurrentHashMap<String, UUID>()
    private val paused = ConcurrentHashMap.newKeySet<String>()

    override val downloads: StateFlow<List<DownloadEntry>> = entries.asStateFlow()

    init {
        // P3-T3: Clean up expired downloads (30-day retention policy)
        cleanupExpiredDownloads()
        // Restore downloads from WorkManager on initialization
        restoreDownloadsFromWorkManager()
    }

    /**
     * P3-T3: 30-day expiry hook - deletes downloads older than 30 days
     */
    private fun cleanupExpiredDownloads() {
        scope.launch {
            withContext(Dispatchers.IO) {
                val currentTime = System.currentTimeMillis()
                // Add 1-hour grace period to handle clock skew or lastModified anomalies
                val gracePeriodMillis = TimeUnit.HOURS.toMillis(1)
                val expiryThresholdMillis = currentTime - TimeUnit.DAYS.toMillis(EXPIRY_DAYS) - gracePeriodMillis
                val downloadFiles = storage.listAllDownloads()
                var deletedCount = 0
                var failedCount = 0
                var skippedActiveCount = 0

                for ((downloadId, file) in downloadFiles) {
                    // Check if download is currently active in WorkManager
                    val workId = workIds[downloadId]
                    if (workId != null) {
                        val workInfo = try {
                            workManager.getWorkInfoById(workId).get()
                        } catch (e: Exception) {
                            null
                        }

                        if (workInfo != null && !workInfo.state.isFinished) {
                            android.util.Log.d(
                                "DownloadRepository",
                                "Skipping deletion of $downloadId: download is still active (state: ${workInfo.state})"
                            )
                            skippedActiveCount++
                            continue
                        }
                    }

                    // Validate file age with secondary checks
                    val lastModified = file.lastModified()
                    if (lastModified <= 0) {
                        // lastModified returned 0, indicating potential filesystem issue
                        android.util.Log.w(
                            "DownloadRepository",
                            "Skipping deletion of $downloadId: file.lastModified() returned invalid value ($lastModified)"
                        )
                        continue
                    }

                    // Verify file still exists before attempting deletion
                    if (!file.exists()) {
                        android.util.Log.d(
                            "DownloadRepository",
                            "Skipping deletion of $downloadId: file no longer exists"
                        )
                        continue
                    }

                    if (lastModified < expiryThresholdMillis) {
                        val audioOnly = file.name.endsWith(".m4a")
                        val ageInDays = TimeUnit.MILLISECONDS.toDays(currentTime - lastModified)

                        try {
                            storage.delete(downloadId, audioOnly)
                            deletedCount++
                            android.util.Log.d(
                                "DownloadRepository",
                                "Deleted expired download: $downloadId (age: $ageInDays days, size: ${file.length()} bytes)"
                            )
                        } catch (e: Exception) {
                            failedCount++
                            android.util.Log.e(
                                "DownloadRepository",
                                "Failed to delete expired download: $downloadId (age: $ageInDays days)",
                                e
                            )
                        }
                    }
                }

                // Log summary of cleanup operation
                if (deletedCount > 0 || failedCount > 0 || skippedActiveCount > 0) {
                    android.util.Log.i(
                        "DownloadRepository",
                        "Cleanup completed: deleted=$deletedCount, failed=$failedCount, skipped_active=$skippedActiveCount (threshold: >${EXPIRY_DAYS} days)"
                    )
                }
            }
        }
    }

    companion object {
        /** P3-T3: Downloads are automatically deleted after this many days */
        const val EXPIRY_DAYS = 30L
    }

    private fun restoreDownloadsFromWorkManager() {
        scope.launch {
            // Scan filesystem for completed downloads
            val downloadFiles = storage.listAllDownloads()
            android.util.Log.d("DownloadRepository", "Found ${downloadFiles.size} download files on disk")

            for ((downloadId, file) in downloadFiles) {
                // Parse downloadId: "videoId_timestamp" format
                val videoId = downloadId.substringBefore('_')
                val audioOnly = file.name.endsWith(".m4a")

                // Try to fetch actual title from metadata extractor
                val title = try {
                    val extractedTitle = fetchVideoTitle(videoId)
                    extractedTitle ?: videoId
                } catch (e: Exception) {
                    android.util.Log.w("DownloadRepository", "Failed to fetch title for $videoId", e)
                    videoId  // Fallback to videoId
                }

                val request = DownloadRequest(
                    id = downloadId,
                    title = title,
                    videoId = videoId,
                    audioOnly = audioOnly
                )

                val metadata = DownloadFileMetadata(
                    sizeBytes = file.length(),
                    completedAtMillis = file.lastModified(),
                    mimeType = if (audioOnly) "audio/mp4" else "video/mp4"
                )

                updateEntry(request) {
                    it.copy(
                        status = DownloadStatus.COMPLETED,
                        progress = 100,
                        filePath = file.absolutePath,
                        metadata = metadata
                    )
                }
            }

            android.util.Log.d("DownloadRepository", "Restored ${entries.value.size} completed downloads")
        }
    }

    private suspend fun fetchVideoTitle(videoId: String): String? {
        // For now, just use the videoId as title
        // TODO: Fetch actual title from extractor or backend
        return videoId
    }

    override fun enqueue(request: DownloadRequest) {
        val workId = scheduler.schedule(request)
        workIds[request.id] = workId
        updateEntry(request) {
            it.copy(status = DownloadStatus.QUEUED, progress = 0, message = null, filePath = null, metadata = null)
        }
        metrics.onDownloadStarted(request.id, request.videoId)
        observeWork(workId, request)
    }

    private fun metadataFrom(info: WorkInfo): DownloadFileMetadata? {
        val size = info.outputData.getLong(KEY_FILE_SIZE, -1L).takeIf { it >= 0 }
        val completedAt = info.outputData.getLong(KEY_COMPLETED_AT, -1L).takeIf { it >= 0 }
        val mimeType = info.outputData.getString(KEY_MIME_TYPE)
        return if (size != null && completedAt != null && mimeType != null) {
            DownloadFileMetadata(size, completedAt, mimeType)
        } else {
            null
        }
    }

    override fun pause(requestId: String) {
        val workId = workIds[requestId] ?: return
        paused += requestId
        workManager.cancelWorkById(workId)
        updateEntry(requestId) { it.copy(status = DownloadStatus.PAUSED, message = null) }
    }

    override fun resume(requestId: String) {
        val entry = entries.value.firstOrNull { it.request.id == requestId } ?: return
        paused -= requestId
        enqueue(entry.request)
    }

    override fun cancel(requestId: String) {
        val entry = entries.value.firstOrNull { it.request.id == requestId }
        val workId = workIds[requestId]
        if (workId != null) {
            workManager.cancelWorkById(workId)
            workIds.remove(requestId)
        }
        paused -= requestId
        if (entry != null) {
            storage.delete(entry.request.id, entry.request.audioOnly)
        }
        updateEntry(requestId) {
            it.copy(status = DownloadStatus.CANCELLED, progress = 0, filePath = null, metadata = null)
        }
    }

    private fun observeWork(workId: UUID, request: DownloadRequest) {
        scope.launch {
            workManager.workInfoFlow(workId)
                .map { info -> info to info.progress.getInt(KEY_PROGRESS, 0) }
                .collectLatest { (info, progress) ->
                    when (info.state) {
                        WorkInfo.State.ENQUEUED -> updateEntry(request) {
                            it.copy(status = DownloadStatus.QUEUED, progress = progress)
                        }
                        WorkInfo.State.RUNNING -> updateEntry(request) {
                            it.copy(status = DownloadStatus.RUNNING, progress = progress)
                        }
                        WorkInfo.State.SUCCEEDED -> {
                            workIds.remove(request.id)
                            val filePath = info.outputData.getString(KEY_FILE_PATH)
                            val metadata = metadataFrom(info)
                            metadata?.let { metrics.onDownloadSizeKnown(request.id, it.sizeBytes) }
                            updateEntry(request) {
                                it.copy(
                                    status = DownloadStatus.COMPLETED,
                                    progress = 100,
                                    filePath = filePath,
                                    metadata = metadata
                                )
                            }
                            if (filePath != null) {
                                metrics.onDownloadCompleted(request.id, filePath)
                            }
                        }
                        WorkInfo.State.FAILED -> {
                            workIds.remove(request.id)
                            updateEntry(request) {
                                it.copy(status = DownloadStatus.FAILED, message = null, filePath = null, metadata = null)
                            }
                            metrics.onDownloadFailed(request.id, IllegalStateException("Download failed"))
                        }
                        WorkInfo.State.CANCELLED -> {
                            workIds.remove(request.id)
                            if (paused.contains(request.id)) {
                                updateEntry(request) { it.copy(status = DownloadStatus.PAUSED) }
                            } else {
                                updateEntry(request) {
                                    it.copy(status = DownloadStatus.CANCELLED, filePath = null, metadata = null)
                                }
                                metrics.onDownloadFailed(request.id, IllegalStateException("Cancelled"))
                            }
                        }
                        WorkInfo.State.BLOCKED -> {
                            updateEntry(request) { it.copy(status = DownloadStatus.QUEUED) }
                        }
                    }
                }
        }
    }

    private fun updateEntry(requestId: String, transform: (DownloadEntry) -> DownloadEntry) {
        val current = entries.value.toMutableList()
        val index = current.indexOfFirst { it.request.id == requestId }
        if (index >= 0) {
            current[index] = transform(current[index])
        }
        entries.value = current
    }

    private fun updateEntry(request: DownloadRequest, transform: (DownloadEntry) -> DownloadEntry) {
        val current = entries.value.toMutableList()
        val index = current.indexOfFirst { it.request.id == request.id }
        if (index >= 0) {
            current[index] = transform(current[index])
        } else {
            current += transform(DownloadEntry(request, DownloadStatus.QUEUED))
        }
        entries.value = current
    }

    private operator fun MutableCollection<String>.plusAssign(value: String) {
        add(value)
    }

    private operator fun MutableCollection<String>.minusAssign(value: String) {
        remove(value)
    }
}

private fun WorkManager.workInfoFlow(id: UUID) =
    getWorkInfoByIdLiveData(id).asFlow().filterNotNull()
