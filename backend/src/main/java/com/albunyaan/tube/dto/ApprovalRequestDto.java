package com.albunyaan.tube.dto;

/**
 * BACKEND-APPR-01: Approval Request DTO
 *
 * Request body for approving a pending item.
 */
public class ApprovalRequestDto {

    /**
     * Review notes (required for approval)
     */
    private String reviewNotes;

    /**
     * Optional category override
     * If provided, will replace the submitted category
     */
    private String categoryOverride;

    /**
     * Finding 3: approval scope. "PUBLIC" (default/null) approves the item into the public
     * library for everyone; "PERSONAL" approves it only for the user(s) who imported it
     * (visibility = PERSONAL) and skips the category requirement.
     */
    private String scope;

    public ApprovalRequestDto() {
    }

    public ApprovalRequestDto(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    // Getters and Setters

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    public String getCategoryOverride() {
        return categoryOverride;
    }

    public void setCategoryOverride(String categoryOverride) {
        this.categoryOverride = categoryOverride;
    }

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    /**
     * True when this request asks for a per-user ("PERSONAL") approval. Trimmed before
     * comparison so a stray-whitespace scope (e.g. " PERSONAL ") is not silently treated
     * as a public approval of content the admin intended to keep personal.
     */
    public boolean isPersonalScope() {
        return scope != null && "PERSONAL".equalsIgnoreCase(scope.trim());
    }
}

