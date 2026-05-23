package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.registry.PreviewErrorCode;
import com.albunyaan.tube.dto.registry.PreviewMetadata;
import com.albunyaan.tube.model.VideoType;

/**
 * BULK-01 (T5) — result of fetching one URL's metadata through NewPipe.
 * Either {@code metadata} is non-null (success) or {@code errorCode} is non-null (failure).
 */
public record PreviewFetchResult(
        PreviewMetadata metadata,
        VideoType videoType,    // STANDARD/LIVE for VIDEO type; null for CHANNEL/PLAYLIST or on error
        PreviewErrorCode errorCode
) {
    public static PreviewFetchResult ok(PreviewMetadata m, VideoType vt) {
        return new PreviewFetchResult(m, vt, null);
    }

    public static PreviewFetchResult error(PreviewErrorCode code) {
        return new PreviewFetchResult(null, null, code);
    }
}
