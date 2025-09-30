package com.albunyaan.tube.registry.dto.admin;

import com.albunyaan.tube.registry.dto.CategoryTagDto;
import java.util.List;
import java.util.UUID;

public record ChannelSummarySnapshotDto(
    UUID id,
    String ytId,
    String name,
    String avatarUrl,
    long subscriberCount,
    List<CategoryTagDto> categories
) {}
