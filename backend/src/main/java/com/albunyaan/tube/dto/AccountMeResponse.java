package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.User;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Plan C T3: public response for GET /api/account/me and POST /api/account/profile.
 * Exposes only user-safe fields — no admin metadata (blockedBy, deletedBy, etc).
 *
 * <p>Plan G cubic R3 P1: now exposes {@code dateOfBirth} as an ISO-8601
 * {@code YYYY-MM-DD} string. Pre-fix the Plan G profile-edit UI could not
 * pre-populate the existing DOB because the field was never serialized,
 * so the user saw an empty placeholder and could not tell what their
 * stored DOB was. The matching Android {@code AccountMeResponseDto} and
 * {@code AccountState.Loaded.dateOfBirth} fields were added in Plan G A2
 * but never wired here.
 */
public class AccountMeResponse {

    private final String uid;
    private final String email;
    private final String displayName;
    private final String dateOfBirth;
    private final String status;
    private final String role;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant profileCompletedAt;

    private AccountMeResponse(String uid, String email, String displayName,
                               String dateOfBirth, String status, String role,
                               Instant profileCompletedAt) {
        this.uid = uid;
        this.email = email;
        this.displayName = displayName;
        this.dateOfBirth = dateOfBirth;
        this.status = status;
        this.role = role;
        this.profileCompletedAt = profileCompletedAt;
    }

    public static AccountMeResponse from(User u) {
        Instant completedAt = u.getProfileCompletedAt() == null ? null
                : Instant.ofEpochSecond(
                        u.getProfileCompletedAt().getSeconds(),
                        u.getProfileCompletedAt().getNanos());
        String dobIso = null;
        if (u.getDateOfBirth() != null) {
            LocalDate ld = Instant.ofEpochSecond(
                            u.getDateOfBirth().getSeconds(),
                            u.getDateOfBirth().getNanos())
                    .atZone(ZoneOffset.UTC).toLocalDate();
            dobIso = ld.toString(); // ISO_LOCAL_DATE → "YYYY-MM-DD"
        }
        return new AccountMeResponse(
                u.getUid(),
                u.getEmail(),
                u.getDisplayName(),
                dobIso,
                u.getStatus(),
                u.getRole(),
                completedAt);
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getDateOfBirth() { return dateOfBirth; }
    public String getStatus() { return status; }
    public String getRole() { return role; }
    public Instant getProfileCompletedAt() { return profileCompletedAt; }
}
