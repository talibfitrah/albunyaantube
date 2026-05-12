package com.albunyaan.tube.dto;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/**
 * Plan F (ADMIN-USER-01, F4) — request body for the four bulk endpoints.
 * Bean Validation enforces 1 ≤ uids.size() ≤ 100 and rejects null/blank uid elements.
 */
public class BulkUserActionRequest {

    @NotNull
    @Size(min = 1, max = 100, message = "uids must contain 1 to 100 entries")
    @Valid
    private List<@NotBlank String> uids;

    @Size(max = 500, message = "reason must be at most 500 characters")
    private String reason;

    public List<String> getUids() { return uids; }
    public void setUids(List<String> uids) { this.uids = uids; }
    public String getReason() { return reason; }
    public void setReason(String reason) { this.reason = reason; }
}
