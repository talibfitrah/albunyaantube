package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.PreviewErrorCode;

/**
 * BULK-01 (T2) — result of parsing a single user-supplied URL.
 * If {@code errorCode} is non-null, {@code type}/{@code youtubeId}/{@code normalizedUrl} may be null.
 */
public record YouTubeUrlParseResult(
        YouTubeContentType type,
        String youtubeId,
        String normalizedUrl,
        boolean isShort,
        PreviewErrorCode errorCode
) {
    public static YouTubeUrlParseResult ok(YouTubeContentType type, String youtubeId, String normalizedUrl, boolean isShort) {
        return new YouTubeUrlParseResult(type, youtubeId, normalizedUrl, isShort, null);
    }

    public static YouTubeUrlParseResult error(PreviewErrorCode code) {
        return new YouTubeUrlParseResult(null, null, null, false, code);
    }
}
