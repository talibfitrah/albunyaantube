package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.User;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;

/**
 * Public response for GET /api/account/me and POST /api/account/profile.
 * User-safe fields only — no admin metadata (blockedBy, deletedBy, etc).
 * {@code dateOfBirth} serializes as ISO-8601 {@code YYYY-MM-DD}.
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
