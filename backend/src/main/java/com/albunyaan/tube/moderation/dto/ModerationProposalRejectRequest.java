package com.albunyaan.tube.moderation.dto;

import jakarta.validation.constraints.Size;

public record ModerationProposalRejectRequest(@Size(max = 500) String reason) {}
