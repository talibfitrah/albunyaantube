package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record VideoUpsertRequest(
    @NotBlank String ytId,
    @NotBlank String channelYtId,
    String playlistYtId,
    @NotEmpty List<@NotBlank String> categorySlugs
) {}
