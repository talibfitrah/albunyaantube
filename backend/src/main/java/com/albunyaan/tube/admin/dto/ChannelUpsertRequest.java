package com.albunyaan.tube.admin.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record ChannelUpsertRequest(
    @NotBlank String ytId,
    @NotEmpty List<@NotBlank String> categorySlugs
) {}
