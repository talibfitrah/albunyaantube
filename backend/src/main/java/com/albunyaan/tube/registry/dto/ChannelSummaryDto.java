package com.albunyaan.tube.registry.dto;

import java.util.List;
import java.util.UUID;

public record ChannelSummaryDto(
    UUID id,
    String ytId,
    List<CategoryTagDto> categories,
    List<String> excludedPlaylistIds,
    List<String> excludedVideoIds
) {}
