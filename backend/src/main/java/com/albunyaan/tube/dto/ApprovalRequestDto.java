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
}

