package com.albunyaan.tube.util

/**
 * Centralized HTTP constants for YouTube stream requests.
 *
 * These values are shared between playback (ExoPlayer) and downloads (OkHttp)
 * to ensure consistent behavior and prevent HTTP 403 errors.
 */
object HttpConstants {
    /**
     * User-Agent for YouTube progressive stream requests.
     *
     * YouTube may block requests without a recognizable User-Agent.
     * Using a mobile Chrome User-Agent provides best compatibility for progressive streams.
     */
    const val YOUTUBE_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 10) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Mobile Safari/537.36"

    /**
     * User-Agent for YouTube HLS stream requests (YouTube iOS app format).
     *
     * When iOS client fetch is enabled (setFetchIosClient=true), HLS manifest URLs are returned
     * by YouTube's iOS endpoint. These HLS segment URLs expect iOS-like headers.
     * Using the YouTube iOS app User-Agent prevents HTTP 403 errors on HLS segment requests.
     *
     * IMPORTANT: This is the YouTube iOS app UA (not iOS Safari browser).
     * This matches NewPipeExtractor's iOS client version strings used in YoutubeStreamExtractor.
     * The coupling is intentional: NPE iOS fetch returns URLs that expect this specific UA.
     *
     * @see org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
     */
    const val YOUTUBE_IOS_USER_AGENT =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"
}
