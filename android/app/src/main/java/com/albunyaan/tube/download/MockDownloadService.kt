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
        return DownloadManifest(
            videoId = videoId,
            audioUrl = "mock://audio/$videoId",
            videoUrl = "mock://video/$videoId",
            audioSize = 5_000_000L,
            videoSize = 25_000_000L,
            expiresAt = System.currentTimeMillis() + 3600_000 // 1 hour
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
 * Download manifest from backend
 */
data class DownloadManifest(
    val videoId: String,
    val audioUrl: String,
    val videoUrl: String,
    val audioSize: Long,
    val videoSize: Long,
    val expiresAt: Long
)

/**
 * Download policy result from backend
 */
data class DownloadPolicyResult(
    val allowed: Boolean,
    val reason: String,
    val requiresEula: Boolean
)

