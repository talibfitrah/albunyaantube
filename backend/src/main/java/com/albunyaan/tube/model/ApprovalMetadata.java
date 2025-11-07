package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;

/**
 * BACKEND-APPR-01: Approval Metadata
 *
 * Stores metadata about approval/rejection actions.
 * This is embedded in Channel and Playlist documents.
 */
public class ApprovalMetadata {

    /**
     * Firebase UID of the reviewer who approved/rejected
     */
    private String reviewedBy;

    /**
     * Display name of the reviewer (cached)
     */
    private String reviewerDisplayName;

    /**
     * Timestamp when the review happened
     */
    private Timestamp reviewedAt;

    /**
     * Review notes from the moderator
     */
    private String reviewNotes;

    /**
     * Rejection reason (for rejected items)
     * Values: "NOT_ISLAMIC", "LOW_QUALITY", "DUPLICATE", "OTHER"
     */
    private String rejectionReason;

    public ApprovalMetadata() {
    }

    public ApprovalMetadata(String reviewedBy, String reviewerDisplayName, String reviewNotes) {
        this.reviewedBy = reviewedBy;
        this.reviewerDisplayName = reviewerDisplayName;
        this.reviewNotes = reviewNotes;
        this.reviewedAt = Timestamp.now();
    }

    // Getters and Setters

    public String getReviewedBy() {
        return reviewedBy;
    }

    public void setReviewedBy(String reviewedBy) {
        this.reviewedBy = reviewedBy;
    }

    public String getReviewerDisplayName() {
        return reviewerDisplayName;
    }

    public void setReviewerDisplayName(String reviewerDisplayName) {
        this.reviewerDisplayName = reviewerDisplayName;
    }

    public Timestamp getReviewedAt() {
        return reviewedAt;
    }

    public void setReviewedAt(Timestamp reviewedAt) {
        this.reviewedAt = reviewedAt;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }
}

