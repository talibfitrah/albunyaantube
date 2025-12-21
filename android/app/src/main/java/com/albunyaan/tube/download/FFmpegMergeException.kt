package com.albunyaan.tube.download

import java.io.IOException

/**
 * Exception thrown when FFmpeg merge operation fails.
 *
 * This allows type-safe error classification in DownloadWorker instead of
 * fragile string matching on exception messages. Maps to DownloadErrorCode.MERGE.
 *
 * @param message Human-readable error message
 * @param returnCode FFmpeg return code (null if not available)
 * @param ffmpegOutput FFmpeg command output for debugging (null if not available)
 * @param cause The underlying cause (null if not available)
 */
class FFmpegMergeException(
    message: String,
    val returnCode: Int? = null,
    val ffmpegOutput: String? = null,
    cause: Throwable? = null
) : IOException(message, cause)
