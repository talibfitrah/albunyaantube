package com.albunyaan.tube.registry.dto;

import java.util.List;
import java.util.UUID;

public record VideoSummaryDto(
    UUID id,
    String ytId,
    UUID channelId,
    String channelYtId,
    UUID playlistId,
    String playlistYtId,
    List<CategoryTagDto> categories,
    boolean excludedByChannel,
    boolean excludedByPlaylist
) {}
