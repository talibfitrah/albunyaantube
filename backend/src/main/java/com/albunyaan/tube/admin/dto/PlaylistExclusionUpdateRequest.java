package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import java.util.List;

public record PlaylistExclusionUpdateRequest(
    List<@NotBlank String> excludedVideoIds
) {}
