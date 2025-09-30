package com.albunyaan.tube.registry.dto.admin;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

public record AdminSearchVideoResultDto(
    UUID id,
    String ytId,
    String title,
    String thumbnailUrl,
    int durationSeconds,
    OffsetDateTime publishedAt,
    long viewCount,
    ChannelSummarySnapshotDto channel,
    List<CategoryTagDto> categories,
    Boolean bookmarked,
    Boolean downloaded,
    IncludeState includeState,
    UUID parentChannelId,
    List<String> parentPlaylistIds
) {}
