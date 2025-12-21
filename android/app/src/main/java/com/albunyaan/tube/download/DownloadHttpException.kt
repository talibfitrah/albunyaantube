package com.albunyaan.tube.download

import java.io.IOException

/**
 * Exception thrown when an HTTP request during download fails with a specific status code.
 *
 * This allows error classification based on the actual HTTP status code rather than
 * parsing exception messages, which is more reliable and less brittle.
 *
 * @param statusCode The HTTP status code (e.g., 403, 429, 500)
 * @param message Human-readable error message
 */
class DownloadHttpException(
    val statusCode: Int,
    message: String
) : IOException(message) {

    /**
     * Whether this is a client error (4xx status code).
     */
    val isClientError: Boolean get() = statusCode in 400..499

    /**
     * Whether this is a server error (5xx status code).
     */
    val isServerError: Boolean get() = statusCode in 500..599

    /**
     * Whether this is an access denied error (403 Forbidden).
     */
    val isForbidden: Boolean get() = statusCode == 403

    /**
     * Whether this is a rate limit error (429 Too Many Requests).
     */
    val isRateLimited: Boolean get() = statusCode == 429
}
