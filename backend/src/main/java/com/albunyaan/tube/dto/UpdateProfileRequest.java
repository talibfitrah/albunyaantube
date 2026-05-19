package com.albunyaan.tube.dto;

import jakarta.validation.constraints.Size;

import java.time.LocalDate;

/**
 * Plan G B2 — partial-update request for PUT /api/account/profile.
 *
 * <p>Both fields are nullable: null means "no change". A request with both
 * null is a no-op (service returns existing profile without writing).
 *
 * <p>{@code displayName} cap is 40 characters, matching the limit enforced
 * by {@code AccountProfileService.validateDisplayName} (MAX_DISPLAY_NAME_LENGTH = 40).
 * The plan template suggested 80; we keep 40 for consistency with completeProfile.
 *
 * <p>{@code dateOfBirth} is a {@link LocalDate} serialized as an ISO-8601 string
 * ("YYYY-MM-DD") on the wire, consistent with {@code CompleteProfileRequest} and
 * the Android client's {@code UpdateProfileRequestDto}.
 */
public record UpdateProfileRequest(
    @Size(min = 1, max = 40) String displayName,   // null = no change
    LocalDate dateOfBirth                           // null = no change
) {}
