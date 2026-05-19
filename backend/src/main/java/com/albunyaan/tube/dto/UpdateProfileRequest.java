package com.albunyaan.tube.dto;

import com.google.cloud.Timestamp;
import jakarta.validation.constraints.Size;

/**
 * Plan G B2 — partial-update request for PUT /api/account/profile.
 *
 * <p>Both fields are nullable: null means "no change". A request with both
 * null is a no-op (service returns existing profile without writing).
 *
 * <p>{@code displayName} cap is 40 characters, matching the limit enforced
 * by {@code AccountProfileService.validateDisplayName} (MAX_DISPLAY_NAME_LENGTH = 40).
 * The plan template suggested 80; we keep 40 for consistency with completeProfile.
 */
public record UpdateProfileRequest(
    @Size(min = 1, max = 40) String displayName,   // null = no change
    Timestamp dateOfBirth                           // null = no change
) {}
