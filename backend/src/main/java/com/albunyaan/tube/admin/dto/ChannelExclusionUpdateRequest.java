package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record ChannelExclusionUpdateRequest(
    List<@NotBlank String> excludedPlaylistIds,
    List<@NotBlank String> excludedVideoIds
) {}
