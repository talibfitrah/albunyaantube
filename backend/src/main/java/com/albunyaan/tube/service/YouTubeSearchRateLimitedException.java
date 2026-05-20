package com.albunyaan.tube.service;

/**
 * Thrown when a moderator YouTube search is blocked by YouTube's rate limiting
 * (ReCaptcha / "sign in to confirm you're not a bot" response).
 * Maps to HTTP 429 in GlobalExceptionHandler so the Android client surfaces a
 * "try again later" message instead of a generic 502.
 */
public class YouTubeSearchRateLimitedException extends RuntimeException {
    private final long retryAfterSec;

    public YouTubeSearchRateLimitedException(long retryAfterSec, Throwable cause) {
        super("YouTube search rate-limited by YouTube; retry after " + retryAfterSec + "s", cause);
        this.retryAfterSec = retryAfterSec;
    }

    public long getRetryAfterSec() {
        return retryAfterSec;
    }
}
