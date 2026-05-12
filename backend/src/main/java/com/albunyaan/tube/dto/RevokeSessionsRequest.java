package com.albunyaan.tube.dto;

/**
 * Plan F (ADMIN-USER-01) — body for POST /api/admin/users/{uid}/revoke-sessions.
 * F6 — reason is optional, captured in audit details when present.
 */
public class RevokeSessionsRequest {
    private String reason;

    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
