package com.albunyaan.tube.download

data class DownloadRequest(
    val id: String,
    val title: String,
    val videoId: String,
    val audioOnly: Boolean = true,
    /** Target video height for quality selection (null = best available or audio-only) */
    val targetHeight: Int? = null,
    /** Thumbnail URL for display in downloads list (optional) */
    val thumbnailUrl: String? = null,
    // Optional playlist context for playlist downloads
    val playlistId: String? = null,
    val playlistTitle: String? = null,
    val playlistQualityLabel: String? = null,
    val indexInPlaylist: Int? = null,
    val playlistSize: Int? = null
)

enum class DownloadStatus { QUEUED, RUNNING, PAUSED, COMPLETED, FAILED, CANCELLED }

/**
 * Download policy for content.
 */
enum class DownloadPolicy { ENABLED, QUEUED, DISABLED }

data class DownloadFileMetadata(
    val sizeBytes: Long,
    val completedAtMillis: Long,
    val mimeType: String,
    /** Persisted title for display after app restart */
    val title: String? = null,
    /** Persisted thumbnail URL for display after app restart */
    val thumbnailUrl: String? = null
)

data class DownloadEntry(
    val request: DownloadRequest,
    val status: DownloadStatus,
    val progress: Int = 0,
    val message: String? = null,
    val filePath: String? = null,
    val metadata: DownloadFileMetadata? = null
)
