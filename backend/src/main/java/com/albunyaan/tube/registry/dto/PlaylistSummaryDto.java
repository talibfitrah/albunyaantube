package com.albunyaan.tube.registry.dto;

import java.util.List;
import java.util.UUID;

public record PlaylistSummaryDto(
    UUID id,
    String ytId,
    UUID channelId,
    String channelYtId,
    List<CategoryTagDto> categories,
    List<String> excludedVideoIds
) {}
