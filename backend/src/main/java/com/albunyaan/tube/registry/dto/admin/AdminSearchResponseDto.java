package com.albunyaan.tube.registry.dto.admin;

import java.util.List;

public record AdminSearchResponseDto(
    String query,
    List<AdminSearchChannelResultDto> channels,
    List<AdminSearchPlaylistResultDto> playlists,
    List<AdminSearchVideoResultDto> videos
) {}
