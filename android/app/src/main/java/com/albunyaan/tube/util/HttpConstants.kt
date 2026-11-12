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
     * Used for every NewPipe resolve (VISIONOS + ANDROID URLs); those URLs were proven with this
     * UA, probe 2026-11-11. NewPipe's own IOS client is off.
     *
     * IMPORTANT: This is the YouTube iOS app UA (not iOS Safari browser).
     *
     * @see org.schabi.newpipe.extractor.services.youtube.extractors.YoutubeStreamExtractor
     */
    const val YOUTUBE_IOS_USER_AGENT =
        "com.google.ios.youtube/19.29.1 (iPhone16,2; U; CPU iOS 17_5_1 like Mac OS X;)"

    /**
     * User-Agent for YouTube MWEB (mobile web) dub-audio requests. The dub audio URLs are
     * minted by the MWEB client under this UA ([DubAudioEnumerator]); the segment factory
     * ([SegmentDataSourceFactoryProvider.forWebDub]) MUST reuse it or the GVS segment fetch
     * 403s on UA mismatch. Verified in the spike (memory/player-dubs-phase2-spike.md).
     */
    const val YOUTUBE_MWEB_USER_AGENT =
        "Mozilla/5.0 (Linux; Android 14; SM-S938U) AppleWebKit/537.36 " +
            "(KHTML, like Gecko) Chrome/131.0.0.0 Mobile Safari/537.36"
}
