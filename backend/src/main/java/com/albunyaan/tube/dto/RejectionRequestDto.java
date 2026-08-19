package com.albunyaan.tube.dto;

/**
 * BACKEND-APPR-01: Rejection Request DTO
 *
 * Request body for rejecting a pending item.
 */
public class RejectionRequestDto {

    /**
     * Optional free-text feedback for the submitter.
     *
     * <p>Null or blank means the admin rejected without writing a reason. It is stored as absent
     * rather than coerced to a placeholder, which would put words in front of the submitter that
     * nobody wrote.
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

