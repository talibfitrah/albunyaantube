package com.albunyaan.tube.download

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import java.io.File

/**
 * ANDROID-DL-02: Mock Download Service
 *
 * Simulates video/audio download progress for testing without backend API.
 * Replace with real download service when backend endpoints are ready.
 */
class MockDownloadService {

    /**
     * Simulates downloading a video with progress updates
     *
     * @param videoId The video ID to download
     * @param audioOnly Whether to download audio-only
     * @param outputFile Target file for the download
     * @return Flow emitting progress from 0 to 100
     */
    fun downloadVideo(
        videoId: String,
        audioOnly: Boolean,
        outputFile: File
    ): Flow<DownloadProgress> = flow {
        // Emit initial queued state
        emit(DownloadProgress(0, DownloadProgressState.QUEUED))
        delay(500)

        // Start downloading
        emit(DownloadProgress(0, DownloadProgressState.DOWNLOADING))

        // Simulate download progress in chunks
        val totalSize = if (audioOnly) 5_000_000L else 25_000_000L // 5MB audio, 25MB video
        var downloadedBytes = 0L

        // Simulate chunked download with varying speeds
        while (downloadedBytes < totalSize) {
            val chunkSize = (totalSize * 0.05).toLong() // 5% chunks
            downloadedBytes = minOf(downloadedBytes + chunkSize, totalSize)
            val progress = ((downloadedBytes.toDouble() / totalSize) * 100).toInt()

            emit(DownloadProgress(
                progress = progress,
                state = DownloadProgressState.DOWNLOADING,
                downloadedBytes = downloadedBytes,
                totalBytes = totalSize
            ))

            // Simulate network delay (50-200ms per chunk)
            delay((50..200).random().toLong())
        }

        // Write a dummy file to simulate completed download
        try {
            outputFile.parentFile?.mkdirs()
            outputFile.writeText("Mock download content for video $videoId (${if (audioOnly) "audio" else "video"})")
            emit(DownloadProgress(100, DownloadProgressState.COMPLETED, totalSize, totalSize))
        } catch (e: Exception) {
            emit(DownloadProgress(0, DownloadProgressState.FAILED, error = e.message))
        }
    }

    /**
     * Simulates fetching download manifest (stream URLs)
     * In real implementation, this would call backend API
     */
    fun getDownloadManifest(videoId: String): DownloadManifest {
        // Return a progressive stream (no merging required)
        // Progressive streams only have progressiveUrl set; videoUrl and audioUrl must be null
        return DownloadManifest(
            videoId = videoId,
            selectedStream = SelectedStream(
                id = "mock-480p",
                qualityLabel = "480p",
                mimeType = "video/mp4",
                requiresMerging = false,
                progressiveUrl = "mock://video/$videoId",
                videoUrl = null,
                audioUrl = null,
                fileSize = 25_000_000L,
                bitrate = 1500
            ),
            expiresAtMillis = System.currentTimeMillis() + 3600_000 // 1 hour
        )
    }

    /**
     * Checks if video allows downloads
     * In real implementation, this would call backend policy endpoint
     */
    fun checkDownloadPolicy(videoId: String): DownloadPolicyResult {
        return DownloadPolicyResult(
            allowed = true,
            reason = "Mock service - always allowed",
            requiresEula = false
        )
    }
}

/**
 * Download progress state
 */
enum class DownloadProgressState {
    QUEUED,
    DOWNLOADING,
    COMPLETED,
    FAILED,
    PAUSED
}

/**
 * Download progress data
 */
data class DownloadProgress(
    val progress: Int,
    val state: DownloadProgressState,
    val downloadedBytes: Long = 0L,
    val totalBytes: Long = 0L,
    val error: String? = null
)

/**
 * Selected stream option from manifest for download.
 *
 * For progressive streams (≤480p): progressiveUrl is non-null, requiresMerging is false.
 * For split streams (>480p): videoUrl and audioUrl are non-null, requiresMerging is true.
 */
data class SelectedStream(
    val id: String,
    val qualityLabel: String,
    val mimeType: String,
    val requiresMerging: Boolean,
    /** Progressive stream URL (non-null when requiresMerging == false) */
    val progressiveUrl: String?,
    /** Video-only stream URL (non-null when requiresMerging == true) */
    val videoUrl: String?,
    /** Audio-only stream URL (non-null when requiresMerging == true) */
    val audioUrl: String?,
    val fileSize: Long,
    val bitrate: Int
)

/**
 * Download manifest from backend with selected stream option.
 */
data class DownloadManifest(
    val videoId: String,
    val selectedStream: SelectedStream,
    /** Expiration time in milliseconds since epoch (UTC) */
    val expiresAtMillis: Long
)

/**
 * Download policy result from backend
 */
data class DownloadPolicyResult(
    val allowed: Boolean,
    val reason: String,
    val requiresEula: Boolean
)
