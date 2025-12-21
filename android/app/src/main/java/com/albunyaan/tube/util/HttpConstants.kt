package com.albunyaan.tube.util

/**
 * Centralized HTTP constants for YouTube stream requests.
 *
 * These values are shared between playback (ExoPlayer) and downloads (OkHttp)
 * to ensure consistent behavior and prevent HTTP 403 errors.
 */
object HttpConstants {
    /**
     * User-Agent for YouTube stream requests.
     *
     * YouTube may block requests without a recognizable User-Agent.
     * Using a mobile Chrome User-Agent provides best compatibility.
     */
    const val YOUTUBE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"
}
