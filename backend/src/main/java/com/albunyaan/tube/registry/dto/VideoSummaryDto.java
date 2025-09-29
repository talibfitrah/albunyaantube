package com.albunyaan.tube.registry.dto;

import java.time.Instant;
import java.util.List;

public record VideoSummaryDto(
    String id,
    String ytId,
    String title,
    String thumbnailUrl,
    int durationSeconds,
    Instant publishedAt,
    long viewCount,
    ChannelSummaryDto channel,
    List<CategoryTagDto> categories,
    Boolean bookmarked,
    Boolean downloaded
) {}

