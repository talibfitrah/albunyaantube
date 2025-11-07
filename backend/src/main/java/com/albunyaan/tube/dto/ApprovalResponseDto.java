package com.albunyaan.tube.dto;

import com.google.cloud.Timestamp;

/**
 * BACKEND-APPR-01: Approval Response DTO
 *
 * Response after approving or rejecting an item.
 */
public class ApprovalResponseDto {

    /**
     * New status: "APPROVED" or "REJECTED"
     */
    private String status;

    /**
     * When the review happened
     */
    private Timestamp reviewedAt;

    /**
     * Who reviewed it
     */
    private String reviewedBy;

    /**
     * Review notes
     */
    private String reviewNotes;

    public ApprovalResponseDto() {
    }

    public ApprovalResponseDto(String status, Timestamp reviewedAt, String reviewedBy) {
        this.status = status;
        this.reviewedAt = reviewedAt;
        this.reviewedBy = reviewedBy;
    }

    // Getters and Setters

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Timestamp reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }
}

