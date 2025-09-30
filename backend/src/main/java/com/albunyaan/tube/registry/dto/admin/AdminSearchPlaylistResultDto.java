package com.albunyaan.tube.registry.dto.admin;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import java.util.List;
import java.util.UUID;

public record AdminSearchPlaylistResultDto(
    UUID id,
    String ytId,
    String title,
    String thumbnailUrl,
    int itemCount,
    ChannelSummarySnapshotDto owner,
    List<CategoryTagDto> categories,
    boolean downloadable,
    IncludeState includeState,
    UUID parentChannelId,
    int excludedVideoCount,
    List<String> excludedVideoIds,
    boolean bulkEligible
) {}
