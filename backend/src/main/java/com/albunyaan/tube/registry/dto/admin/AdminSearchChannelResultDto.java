package com.albunyaan.tube.registry.dto.admin;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import java.util.List;
import java.util.UUID;

public record AdminSearchChannelResultDto(
    UUID id,
    String ytId,
    String name,
    String avatarUrl,
    long subscriberCount,
    List<CategoryTagDto> categories,
    IncludeState includeState,
    ExcludedItemCountsDto excludedItemCounts,
    List<String> excludedPlaylistIds,
    List<String> excludedVideoIds,
    boolean bulkEligible
) {}
