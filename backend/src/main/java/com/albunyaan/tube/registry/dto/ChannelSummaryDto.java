package com.albunyaan.tube.registry.dto;

import java.util.List;

public record ChannelSummaryDto(
    String id,
    String ytId,
    String name,
    String avatarUrl,
    long subscriberCount,
    List<CategoryTagDto> categories
) {}

