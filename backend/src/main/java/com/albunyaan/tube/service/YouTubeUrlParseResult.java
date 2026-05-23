package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;

/**
 * Result of parsing a single user-supplied URL.
 * If {@code errorCode} is non-null, {@code type}/{@code youtubeId}/{@code normalizedUrl} may be null.
 */
public record YouTubeUrlParseResult(
        YouTubeContentType type,
        String youtubeId,
        String normalizedUrl,
        PreviewErrorCode errorCode
) {
    public static YouTubeUrlParseResult ok(YouTubeContentType type, String youtubeId, String normalizedUrl) {
        return new YouTubeUrlParseResult(type, youtubeId, normalizedUrl, null);
    }

    public static YouTubeUrlParseResult error(PreviewErrorCode code) {
        return new YouTubeUrlParseResult(null, null, null, code);
    }
}
