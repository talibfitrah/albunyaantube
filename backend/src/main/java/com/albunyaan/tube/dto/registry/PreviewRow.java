package com.albunyaan.tube.dto.registry;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.VideoType;

/** BULK-01 (T3) — one row in the bulk preview response. */
public record PreviewRow(
        int rowIndex,
        String originalUrl,
        String normalizedUrl,
        YouTubeContentType detectedType,
        VideoType videoType,
        PreviewMetadata metadata,
        RowStatus status,
        String duplicateOf,
        String duplicateStatus,
        PreviewError error
) {}
