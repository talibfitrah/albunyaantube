package com.albunyaan.tube.download

import android.util.Log
import com.arthenica.ffmpegkit.FFmpegKit
import com.arthenica.ffmpegkit.ReturnCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import javax.inject.Inject
import javax.inject.Singleton

/**
 * P4-T4: FFmpeg-based audio/video merger for high-quality downloads.
 *
 * When downloading streams >480p, video and audio are separate (requiresMerging=true).
 * This utility merges them into a single MP4 file using FFmpeg-kit.
 *
 * Uses community fork: io.github.trongnhan136:ffmpeg-kit-min-gpl
 * (Original arthenica/ffmpeg-kit was archived June 2025)
 */
@Singleton
class FFmpegMerger @Inject constructor() {

    /**
     * Merge video and audio files into a single MP4.
     *
     * @param videoFile The video-only input file
     * @param audioFile The audio-only input file
     * @param outputFile The merged output file
     * @param onProgress Optional progress callback (0-100)
     * @return true if merge succeeded, false otherwise
     */
    suspend fun merge(
        videoFile: File,
        audioFile: File,
        outputFile: File,
        onProgress: ((Int) -> Unit)? = null
    ): Boolean = withContext(Dispatchers.IO) {
        if (!videoFile.exists()) {
            Log.e(TAG, "Video file does not exist: ${videoFile.absolutePath}")
            return@withContext false
        }
        if (!audioFile.exists()) {
            Log.e(TAG, "Audio file does not exist: ${audioFile.absolutePath}")
            return@withContext false
        }

        // Ensure output directory exists
        val parentDir = outputFile.parentFile
        if (parentDir == null) {
            throw IOException("Cannot determine parent directory for output file: ${outputFile.absolutePath}")
        }
        if (!parentDir.exists()) {
            if (!parentDir.mkdirs()) {
                throw IOException("Failed to create output directory: ${parentDir.absolutePath}")
            }
        }

        // Delete existing output file if present
        if (outputFile.exists()) {
            outputFile.delete()
        }

        val command = arrayOf(
            "-i", videoFile.absolutePath,
            "-i", audioFile.absolutePath,
            "-c", "copy",
            "-map", "0:v:0",
            "-map", "1:a:0",
            "-movflags", "+faststart",
            "-y",
            outputFile.absolutePath
        ).joinToString(" ")

        Log.d(TAG, "Starting FFmpeg merge: $command")

        val session = FFmpegKit.execute(command)
        val returnCode = session.returnCode

        when {
            ReturnCode.isSuccess(returnCode) -> {
                Log.i(TAG, "Merge completed successfully: ${outputFile.absolutePath}")
                onProgress?.invoke(100)
                true
            }
            ReturnCode.isCancel(returnCode) -> {
                Log.w(TAG, "Merge cancelled")
                outputFile.delete()
                false
            }
            else -> {
                Log.e(TAG, "Merge failed with return code: ${returnCode.value}")
                Log.e(TAG, "FFmpeg output: ${session.output}")
                outputFile.delete()
                false
            }
        }
    }

    /**
     * Check if FFmpeg-kit is available and functional.
     */
    fun isAvailable(): Boolean {
        return try {
            val session = FFmpegKit.execute("-version")
            ReturnCode.isSuccess(session.returnCode)
        } catch (e: Exception) {
            Log.e(TAG, "FFmpeg-kit not available", e)
            false
        }
    }

    companion object {
        private const val TAG = "FFmpegMerger"
    }
}
