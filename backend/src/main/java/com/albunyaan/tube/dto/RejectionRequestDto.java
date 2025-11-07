package com.albunyaan.tube.dto;

/**
 * BACKEND-APPR-01: Rejection Request DTO
 *
 * Request body for rejecting a pending item.
 */
public class RejectionRequestDto {

    /**
     * Rejection reason (required)
     * Values: "NOT_ISLAMIC", "LOW_QUALITY", "DUPLICATE", "OTHER"
     */
    private String reason;

    /**
     * Review notes (optional)
     */
    private String reviewNotes;

    public RejectionRequestDto() {
    }

    public RejectionRequestDto(String reason, String reviewNotes) {
        this.reason = reason;
        this.reviewNotes = reviewNotes;
    }

    // Getters and Setters

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }
}

