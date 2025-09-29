package com.albunyaan.tube.registry.dto;

import java.util.List;

public record PlaylistSummaryDto(
    String id,
    String ytId,
    String title,
    String thumbnailUrl,
    int itemCount,
    ChannelSummaryDto owner,
    List<CategoryTagDto> categories,
    Boolean downloadable
) {}

