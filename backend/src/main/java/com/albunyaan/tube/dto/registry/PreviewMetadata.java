package com.albunyaan.tube.dto.registry;

/** BULK-01 (T3) — NewPipe metadata snapshot for a single previewed URL. Sparse: fields are populated based on type. */
public record PreviewMetadata(
        String youtubeId,
        String title,
        String thumbnailUrl,
        String channelName,
        String channelId,
        Long subscribers,
        Long itemCount,
        Long durationSeconds,
        Long viewCount
) {}
