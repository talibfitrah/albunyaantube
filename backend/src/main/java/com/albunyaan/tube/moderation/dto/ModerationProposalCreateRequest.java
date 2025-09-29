package com.albunyaan.tube.moderation.dto;

import com.albunyaan.tube.moderation.ModerationProposalKind;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

public record ModerationProposalCreateRequest(
    @NotNull ModerationProposalKind kind,
    @NotBlank @Size(max = 64) String ytId,
    @NotEmpty List<@NotBlank String> suggestedCategories,
    @Size(max = 1000) String notes
) {}
