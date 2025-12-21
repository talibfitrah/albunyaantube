package com.albunyaan.tube.download

import androidx.lifecycle.asFlow
import androidx.work.WorkInfo
import androidx.work.WorkManager
import com.albunyaan.tube.analytics.ExtractorMetricsReporter
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_COMPLETED_AT
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_ERROR_REASON
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_PATH
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_FILE_SIZE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_MIME_TYPE
import com.albunyaan.tube.download.DownloadScheduler.Companion.KEY_PROGRESS
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
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

    /**
     * Remove a failed or cancelled download entry from the list.
     * Clears the entry and any associated temp files.
     */
    fun remove(requestId: String)

    /**
     * Retry a failed download by re-enqueuing the original request.
     */
    fun retry(requestId: String)

    /**
     * Delete a completed download (removes file and clears entry).
     * @return true if deletion was successful, false if entry not found or not completed
     */
    fun delete(requestId: String): Boolean

    /**
     * Enqueue all videos in a playlist for download.
     *
     * @param playlistId YouTube playlist ID
     * @param playlistTitle Playlist title for display
     * @param qualityLabel Selected quality (e.g., "360p")
     * @param items List of playlist items to download
     * @param audioOnly Whether to download audio only
     * @param targetHeight Target video height for quality selection (null = best available)
     * @return Number of items actually enqueued (excludes duplicates)
     */
    fun enqueuePlaylist(
        playlistId: String,
        playlistTitle: String,
        qualityLabel: String,
        items: List<PlaylistDownloadItem>,
        audioOnly: Boolean,
        targetHeight: Int? = null
    ): Int

    /**
     * Check if a playlist is currently being downloaded at a specific quality.
     */
    fun isPlaylistDownloading(playlistId: String, qualityLabel: String): Boolean
}

/**
 * Simplified playlist item for download purposes.
 */
data class PlaylistDownloadItem(
    val videoId: String,
    val title: String,
    val indexInPlaylist: Int,
    val thumbnailUrl: String? = null
)

class DefaultDownloadRepository(
    private val workManager: WorkManager,
    private val scheduler: DownloadScheduler,
    private val storage: DownloadStorage,
    private val metrics: ExtractorMetricsReporter,
    private val expiryPolicy: DownloadExpiryPolicy,
    private val scope: CoroutineScope
) : DownloadRepository {

    private val entries = MutableStateFlow<List<DownloadEntry>>(emptyList())
    private val entriesLock = Any() // Synchronization lock for entries read-modify-write
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
                val cutoffMillis = expiryPolicy.cutoffMillis()
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

                    // Use stored completion timestamp (source of truth)
                    val completedAt = storage.getCompletionTimestamp(downloadId)
                    if (completedAt == null || completedAt <= 0) {
                        android.util.Log.w(
                            "DownloadRepository",
                            "Skipping deletion of $downloadId: no valid completion timestamp"
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

                    if (completedAt < cutoffMillis) {
                        val audioOnly = file.name.endsWith(".m4a")
                        val ageInDays = expiryPolicy.ttlDays - expiryPolicy.daysUntilExpiry(completedAt)

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
                        "Cleanup completed: deleted=$deletedCount, failed=$failedCount, skipped_active=$skippedActiveCount (threshold: >${expiryPolicy.ttlDays} days)"
                    )
                }
            }
        }
    }

    companion object {
        // Note: TTL is now defined in DownloadExpiryPolicy (single source of truth)
        /** Delimiter for composite playlist download IDs: "playlistId|qualityLabel|videoId" */
        private const val PLAYLIST_ID_DELIMITER = '|'
    }

    private fun restoreDownloadsFromWorkManager() {
        scope.launch {
            // First, restore in-progress downloads from WorkManager
            restoreRunningDownloads()

            // Then scan filesystem for completed downloads
            val downloadFiles = storage.listAllDownloads()
            android.util.Log.d("DownloadRepository", "Found ${downloadFiles.size} download files on disk")

            for ((downloadId, file) in downloadFiles) {
                // Skip if already restored from WorkManager (in-progress or completed)
                if (entries.value.any { it.request.id == downloadId }) {
                    continue
                }

                // Parse downloadId to extract videoId
                // Supports two formats:
                // 1. Playlist format: "playlistId|qualityLabel|videoId" (new)
                // 2. Single video format: "videoId_timestamp" (legacy)
                val videoId = parseVideoIdFromDownloadId(downloadId)
                val audioOnly = file.name.endsWith(".m4a")

                // Load extended metadata from storage (title, thumbnailUrl)
                // This was saved when the download was enqueued
                val storedMetadata = storage.metadataFor(downloadId, audioOnly)

                // Use stored title, fallback to videoId only if not available
                val title = storedMetadata?.title ?: videoId
                val thumbnailUrl = storedMetadata?.thumbnailUrl

                // Extract playlist metadata if present
                val playlistMetadata = parsePlaylistMetadataFromDownloadId(downloadId)

                val request = DownloadRequest(
                    id = downloadId,
                    title = title,
                    videoId = videoId,
                    audioOnly = audioOnly,
                    thumbnailUrl = thumbnailUrl,
                    playlistId = playlistMetadata?.first,
                    playlistQualityLabel = playlistMetadata?.second
                )

                val metadata = storedMetadata ?: DownloadFileMetadata(
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

            android.util.Log.d("DownloadRepository", "Restored ${entries.value.size} total downloads")
        }
    }

    /**
     * Restore running/queued downloads from WorkManager after app restart.
     * Queries WorkManager for active downloads tagged with our download tag,
     * reconstructs DownloadRequest from stored metadata, and re-observes progress.
     */
    private suspend fun restoreRunningDownloads() {
        try {
            // Query all work with our download tag - use withContext to safely block
            val workInfos = withContext(Dispatchers.IO) {
                workManager.getWorkInfosByTag("com.albunyaan.tube.download").get()
            }

            var restoredCount = 0

            for (info in workInfos) {
                // Only restore non-finished work
                if (info.state.isFinished) continue

                // Extract download ID from tags (format: "download_${downloadId}")
                // WorkInfo doesn't expose input data, but tags are preserved
                val downloadTag = info.tags.firstOrNull { it.startsWith("download_") }
                val downloadId = downloadTag?.removePrefix("download_")

                if (downloadId.isNullOrEmpty()) {
                    android.util.Log.w("DownloadRepository", "Could not extract downloadId from WorkInfo tags")
                    continue
                }

                // Skip if already have this entry
                if (entries.value.any { it.request.id == downloadId }) {
                    android.util.Log.d("DownloadRepository", "Skipping already tracked download: $downloadId")
                    continue
                }

                // Parse metadata
                val videoId = parseVideoIdFromDownloadId(downloadId)
                val storedMetadata = storage.getExtendedMetadata(downloadId)
                val title = storedMetadata?.first ?: videoId
                val thumbnailUrl = storedMetadata?.second

                android.util.Log.d("DownloadRepository", "Restoring download: id=$downloadId, title=$title, thumbnailUrl=${thumbnailUrl != null}")

                // Determine audio only from stored metadata or downloadId pattern
                val audioOnly = storage.isAudioOnlyDownload(downloadId)

                // Extract playlist metadata if present
                val playlistMetadata = parsePlaylistMetadataFromDownloadId(downloadId)

                val request = DownloadRequest(
                    id = downloadId,
                    title = title,
                    videoId = videoId,
                    audioOnly = audioOnly,
                    thumbnailUrl = thumbnailUrl,
                    playlistId = playlistMetadata?.first,
                    playlistQualityLabel = playlistMetadata?.second
                )

                // Determine status based on WorkInfo state
                val status = when (info.state) {
                    WorkInfo.State.RUNNING -> DownloadStatus.RUNNING
                    WorkInfo.State.ENQUEUED, WorkInfo.State.BLOCKED -> DownloadStatus.QUEUED
                    else -> DownloadStatus.QUEUED
                }

                // Get progress if available
                val progress = info.progress.getInt(DownloadScheduler.KEY_PROGRESS, 0)

                // Store workId and start observing
                workIds[request.id] = info.id

                updateEntry(request) {
                    it.copy(status = status, progress = progress)
                }

                // Re-observe this work for progress updates
                observeWork(info.id, request)

                restoredCount++
                android.util.Log.d("DownloadRepository", "Restored running download: $downloadId (status=$status, progress=$progress%)")
            }

            if (restoredCount > 0) {
                android.util.Log.i("DownloadRepository", "Restored $restoredCount running/queued downloads from WorkManager")
            }
        } catch (e: Exception) {
            android.util.Log.e("DownloadRepository", "Failed to restore running downloads", e)
        }
    }

    /**
     * Parse videoId from downloadId supporting both formats:
     * - Playlist format: "playlistId|qualityLabel|videoId" -> returns videoId (third part)
     * - Legacy format: "videoId_timestamp" -> returns videoId (before underscore)
     */
    private fun parseVideoIdFromDownloadId(downloadId: String): String {
        return if (downloadId.contains(PLAYLIST_ID_DELIMITER)) {
            // Playlist format: playlistId|qualityLabel|videoId
            val parts = downloadId.split(PLAYLIST_ID_DELIMITER)
            if (parts.size == 3) parts[2] else downloadId.substringBefore('_')
        } else {
            // Legacy format: videoId_timestamp
            downloadId.substringBefore('_')
        }
    }

    /**
     * Extract playlist metadata (playlistId, qualityLabel) from downloadId if present.
     * Returns null for legacy single-video downloads.
     */
    private fun parsePlaylistMetadataFromDownloadId(downloadId: String): Pair<String, String>? {
        return if (downloadId.contains(PLAYLIST_ID_DELIMITER)) {
            val parts = downloadId.split(PLAYLIST_ID_DELIMITER)
            if (parts.size == 3) Pair(parts[0], parts[1]) else null
        } else {
            null
        }
    }

    private suspend fun fetchVideoTitle(videoId: String): String? {
        // For now, just use the videoId as title
        // TODO: Fetch actual title from extractor or backend
        return videoId
    }

    override fun enqueue(request: DownloadRequest) {
        // Save extended metadata (title, thumbnailUrl) before scheduling
        // This ensures the metadata is available for display after app restart
        storage.saveExtendedMetadata(request.id, request.title, request.thumbnailUrl)

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

    override fun remove(requestId: String) {
        val entry = entries.value.firstOrNull { it.request.id == requestId } ?: return
        // Cancel any pending work
        val workId = workIds[requestId]
        if (workId != null) {
            workManager.cancelWorkById(workId)
            workIds.remove(requestId)
        }
        paused -= requestId
        // Delete any temp files
        storage.delete(entry.request.id, entry.request.audioOnly)
        // Remove entry from list
        removeEntry(requestId)
        android.util.Log.d("DownloadRepository", "Removed download entry: $requestId")
    }

    override fun retry(requestId: String) {
        val entry = entries.value.firstOrNull { it.request.id == requestId } ?: return
        if (entry.status !in listOf(DownloadStatus.FAILED, DownloadStatus.CANCELLED)) {
            android.util.Log.w("DownloadRepository", "Cannot retry download with status: ${entry.status}")
            return
        }
        android.util.Log.d("DownloadRepository", "Retrying download: $requestId")
        // Re-enqueue with the same request
        enqueue(entry.request)
    }

    override fun delete(requestId: String): Boolean {
        synchronized(entriesLock) {
            val entry = entries.value.firstOrNull { it.request.id == requestId }
            if (entry == null) {
                android.util.Log.w("DownloadRepository", "Cannot delete: entry not found: $requestId")
                return false
            }
            if (entry.status != DownloadStatus.COMPLETED) {
                android.util.Log.w("DownloadRepository", "Cannot delete non-completed download: ${entry.status}")
                return false
            }
            // Delete the file
            storage.delete(entry.request.id, entry.request.audioOnly)
            // Remove entry from list (already inside lock, so use direct mutation)
            val current = entries.value.toMutableList()
            current.removeAll { it.request.id == requestId }
            entries.value = current
            android.util.Log.d("DownloadRepository", "Deleted completed download: $requestId")
            return true
        }
    }

    private fun removeEntry(requestId: String) {
        synchronized(entriesLock) {
            val current = entries.value.toMutableList()
            current.removeAll { it.request.id == requestId }
            entries.value = current
        }
    }

    override fun enqueuePlaylist(
        playlistId: String,
        playlistTitle: String,
        qualityLabel: String,
        items: List<PlaylistDownloadItem>,
        audioOnly: Boolean,
        targetHeight: Int?
    ): Int {
        val playlistSize = items.size
        var enqueuedCount = 0

        for (item in items) {
            // Generate deterministic request ID for deduplication
            // Format: playlistId|qualityLabel|videoId
            val requestId = "$playlistId$PLAYLIST_ID_DELIMITER$qualityLabel$PLAYLIST_ID_DELIMITER${item.videoId}"

            // Skip if already downloaded or in progress
            val existing = entries.value.find { it.request.id == requestId }
            if (existing != null && existing.status in listOf(
                    DownloadStatus.QUEUED,
                    DownloadStatus.RUNNING,
                    DownloadStatus.PAUSED,
                    DownloadStatus.COMPLETED
                )
            ) {
                android.util.Log.d(
                    "DownloadRepository",
                    "Skipping duplicate: $requestId (status=${existing.status})"
                )
                continue
            }

            val request = DownloadRequest(
                id = requestId,
                title = item.title,
                videoId = item.videoId,
                audioOnly = audioOnly,
                targetHeight = targetHeight,
                thumbnailUrl = item.thumbnailUrl,
                playlistId = playlistId,
                playlistTitle = playlistTitle,
                playlistQualityLabel = qualityLabel,
                indexInPlaylist = item.indexInPlaylist,
                playlistSize = playlistSize
            )

            enqueue(request)
            enqueuedCount++
        }

        android.util.Log.i(
            "DownloadRepository",
            "Playlist enqueue: $enqueuedCount of ${items.size} items queued for $playlistTitle ($qualityLabel)"
        )

        return enqueuedCount
    }

    override fun isPlaylistDownloading(playlistId: String, qualityLabel: String): Boolean {
        val prefix = "$playlistId$PLAYLIST_ID_DELIMITER$qualityLabel$PLAYLIST_ID_DELIMITER"
        return entries.value.any { entry ->
            entry.request.id.startsWith(prefix) &&
                    entry.status in listOf(DownloadStatus.QUEUED, DownloadStatus.RUNNING)
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
                            val errorMessage = info.outputData.getString(KEY_ERROR_REASON)
                                ?: "Download failed"
                            updateEntry(request) {
                                it.copy(status = DownloadStatus.FAILED, message = errorMessage, filePath = null, metadata = null)
                            }
                            metrics.onDownloadFailed(request.id, IllegalStateException(errorMessage))
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
        synchronized(entriesLock) {
            val current = entries.value.toMutableList()
            val index = current.indexOfFirst { it.request.id == requestId }
            if (index >= 0) {
                current[index] = transform(current[index])
            }
            entries.value = current
        }
    }

    private fun updateEntry(request: DownloadRequest, transform: (DownloadEntry) -> DownloadEntry) {
        synchronized(entriesLock) {
            val current = entries.value.toMutableList()
            val index = current.indexOfFirst { it.request.id == request.id }
            if (index >= 0) {
                current[index] = transform(current[index])
            } else {
                current += transform(DownloadEntry(request, DownloadStatus.QUEUED))
            }
            entries.value = current
        }
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
