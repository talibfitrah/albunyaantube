package com.albunyaan.tube.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F4) — request body for the four bulk endpoints.
 * Bean Validation enforces 1 ≤ uids.size() ≤ 100 and rejects null/blank uid elements.
 */
public class BulkUserActionRequest {

    /**
     * Cubic R7 P2 — UID shape validation at the DTO boundary.
     *
     * <p>Pre-fix the self-action guard's {@code uid.equals(actorUid)} was
     * cryptographically correct but trailing whitespace / homoglyph
     * surrogate variants in the request body bypassed it (the request
     * carried the trailing-whitespace uid, the principal carried the
     * canonical uid, equals returned false, and the admin walked through
     * the self-block). Firebase UIDs are at most 128 chars from the
     * {@code [A-Za-z0-9]} alphabet (per Firebase Auth spec); the regex
     * pins each element to that shape, rejecting whitespace and
     * non-printable variants at the controller before they reach the
     * comparison.
     */
    @NotNull
    @Size(min = 1, max = 100, message = "uids must contain 1 to 100 entries")
    @Valid
    private List<@NotBlank @Pattern(
            regexp = "^[A-Za-z0-9]{20,128}$",
            message = "uid must be 20-128 alphanumeric chars") String> uids;

    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;

    public List<String> getUids() { return uids; }
    public void setUids(List<String> uids) { this.uids = uids; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
