package com.albunyaan.tube.dto;

import com.google.cloud.Timestamp;

import java.util.HashMap;
import java.util.Map;

/**
 * BACKEND-APPR-01: Pending Approval DTO
 *
 * Represents a pending approval item (channel or playlist).
 */
public class PendingApprovalDto {

    /**
     * Approval item ID (document ID)
     */
    private String id;

    /**
     * Type: "CHANNEL" or "PLAYLIST"
     */
    private String type;

    /**
     * Entity ID (channel or playlist document ID)
     */
    private String entityId;

    /**
     * Title/name of the entity
     */
    private String title;

    /**
     * Category name
     */
    private String category;

    /**
     * When submitted
     */
    private Timestamp submittedAt;

    /**
     * Who submitted
     */
    private String submittedBy;

    /**
     * Current status: PENDING, APPROVED, REJECTED
     */
    private String status;

    /**
     * Rejection reason (if rejected)
     */
    private String rejectionReason;

    /**
     * Review notes from the admin (if approved or rejected)
     */
    private String reviewNotes;

    /**
     * Additional metadata (subscribers, video count, etc.)
     */
    private Map<String, Object> metadata;

    public PendingApprovalDto() {
        this.metadata = new HashMap<>();
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public Timestamp getSubmittedAt() {
        return submittedAt;
    }

    public void setSubmittedAt(Timestamp submittedAt) {
        this.submittedAt = submittedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getRejectionReason() {
        return rejectionReason;
    }

    public void setRejectionReason(String rejectionReason) {
        this.rejectionReason = rejectionReason;
    }

    public String getReviewNotes() {
        return reviewNotes;
    }

    public void setReviewNotes(String reviewNotes) {
        this.reviewNotes = reviewNotes;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void addMetadata(String key, Object value) {
        this.metadata.put(key, value);
    }
}

