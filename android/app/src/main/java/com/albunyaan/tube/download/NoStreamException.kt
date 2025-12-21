package com.albunyaan.tube.download

import java.io.IOException

/**
 * Exception thrown when no suitable stream is available for download.
 *
 * This can occur when:
 * - Video is unavailable, private, or geo-blocked
 * - Stream extraction fails
 * - No compatible streams found for the requested format
 * - Video-only streams exist but no compatible audio for merge
 *
 * This allows type-safe error classification in DownloadWorker instead of
 * fragile string matching on exception messages.
 *
 * @param videoId The YouTube video ID that failed
 * @param errorCode The specific error code for UI display (from DownloadErrorCode)
 * @param message Human-readable error message for logging
 * @param cause The underlying cause (null if not available)
 */
class NoStreamException(
    val videoId: String,
    val errorCode: String = DownloadErrorCode.NO_STREAM,
    message: String,
    cause: Throwable? = null
) : IOException(message, cause)
