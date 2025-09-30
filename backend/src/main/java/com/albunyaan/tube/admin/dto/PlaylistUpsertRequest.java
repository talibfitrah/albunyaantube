package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record PlaylistUpsertRequest(
    @NotBlank String ytId,
    @NotBlank String channelYtId,
    @NotEmpty List<@NotBlank String> categorySlugs
) {}
