package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.User;
import com.fasterxml.jackson.annotation.JsonFormat;

import java.time.Instant;

/**
 * Plan C T3: public response for GET /api/account/me and POST /api/account/profile.
 * Exposes only user-safe fields — no admin metadata (blockedBy, deletedBy, etc).
 */
public class AccountMeResponse {

    private final String uid;
    private final String email;
    private final String displayName;
    private final String status;
    private final String role;

    @JsonFormat(shape = JsonFormat.Shape.STRING)
    private final Instant profileCompletedAt;

    private AccountMeResponse(String uid, String email, String displayName,
                               String status, String role, Instant profileCompletedAt) {
        this.uid = uid;
        this.email = email;
        this.displayName = displayName;
        this.status = status;
        this.role = role;
        this.profileCompletedAt = profileCompletedAt;
    }

    public static AccountMeResponse from(User u) {
        Instant completedAt = u.getProfileCompletedAt() == null ? null
                : Instant.ofEpochSecond(
                        u.getProfileCompletedAt().getSeconds(),
                        u.getProfileCompletedAt().getNanos());
        return new AccountMeResponse(
                u.getUid(),
                u.getEmail(),
                u.getDisplayName(),
                u.getStatus(),
                u.getRole(),
                completedAt);
    }

    public String getUid() { return uid; }
    public String getEmail() { return email; }
    public String getDisplayName() { return displayName; }
    public String getStatus() { return status; }
    public String getRole() { return role; }
    public Instant getProfileCompletedAt() { return profileCompletedAt; }
}
