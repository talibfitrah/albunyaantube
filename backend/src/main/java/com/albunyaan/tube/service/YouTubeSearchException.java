package com.albunyaan.tube.service;

/**
 * Thrown when a moderator YouTube search fails (circuit-breaker tripped, extraction error, etc.).
 */
public class YouTubeSearchException extends RuntimeException {
    public YouTubeSearchException(String msg, Throwable cause) {
        super(msg, cause);
    }
}
