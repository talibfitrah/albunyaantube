package com.albunyaan.tube.download

/**
 * Shared error codes for download failures.
 *
 * These codes are produced by DownloadWorker and consumed by DownloadsAdapter
 * to display localized error messages. Keeping them in a shared domain object
 * decouples the UI from worker implementation details.
 *
 * Each code maps to a corresponding R.string.download_error_* resource.
 */
object DownloadErrorCode {
    /** HTTP 403 Forbidden - stream URL expired or access denied */
    const val HTTP_403 = "ERROR_HTTP_403"

    /** HTTP 429 Too Many Requests - rate limited */
    const val HTTP_429 = "ERROR_HTTP_429"

    /** Network error - connection issues */
    const val NETWORK = "ERROR_NETWORK"

    /** FFmpeg merge failed */
    const val MERGE = "ERROR_MERGE"

    /** No stream available - video unavailable or blocked */
    const val NO_STREAM = "ERROR_NO_STREAM"

    /** No compatible video stream - only incompatible codecs (VP9/WebM) available */
    const val NO_COMPATIBLE_VIDEO = "ERROR_NO_COMPATIBLE_VIDEO"

    /** Video-only stream without compatible audio - would result in muted video */
    const val VIDEO_AUDIO_MISMATCH = "ERROR_VIDEO_AUDIO_MISMATCH"

    /** Missing required input data (download ID, video ID) */
    const val INVALID_INPUT = "ERROR_INVALID_INPUT"

    /** Unknown/generic error */
    const val UNKNOWN = "ERROR_UNKNOWN"
}
