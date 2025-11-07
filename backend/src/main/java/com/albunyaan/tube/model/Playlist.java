package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.IgnoreExtraProperties;

import java.util.ArrayList;
import java.util.List;

/**
 * FIREBASE-MIGRATE-03: Playlist Model (Firestore)
 *
 * Represents a YouTube playlist added to the master list.
 *
 * Collection: playlists
 *
 * Note: @IgnoreExtraProperties is used to ignore legacy fields (approved, category)
 * that were seeded in historical data but are now replaced by approvalMetadata.
 */
@IgnoreExtraProperties
public class Playlist {

    @DocumentId
    private String id;

    /**
     * YouTube playlist ID (e.g., "PLxxxxxx")
     */
    private String youtubeId;

    /**
     * Cached YouTube metadata
     */
    private String title;
    private String description;
    private String thumbnailUrl;
    private Integer itemCount;

    /**
     * Assigned category IDs
     */
    private List<String> categoryIds;

    /**
     * Approval status
     */
    private String status;

    /**
     * Excluded videos from this playlist
     */
    private List<String> excludedVideoIds;

    /**
     * Metadata
     */
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String submittedBy;
    private String approvedBy;

    /**
     * Approval metadata (BACKEND-APPR-01)
     */
    private ApprovalMetadata approvalMetadata;

    public Playlist() {
        this.categoryIds = new ArrayList<>();
        this.excludedVideoIds = new ArrayList<>();
        this.status = "pending";
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
    }

    public Playlist(String youtubeId) {
        this();
        this.youtubeId = youtubeId;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getYoutubeId() {
        return youtubeId;
    }

    public void setYoutubeId(String youtubeId) {
        this.youtubeId = youtubeId;
    }

    public List<String> getCategoryIds() {
        return categoryIds;
    }

    public void setCategoryIds(List<String> categoryIds) {
        this.categoryIds = categoryIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public List<String> getExcludedVideoIds() {
        return excludedVideoIds;
    }

    public void setExcludedVideoIds(List<String> excludedVideoIds) {
        this.excludedVideoIds = excludedVideoIds;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getSubmittedBy() {
        return submittedBy;
    }

    public void setSubmittedBy(String submittedBy) {
        this.submittedBy = submittedBy;
    }

    public String getApprovedBy() {
        return approvedBy;
    }

    public void setApprovedBy(String approvedBy) {
        this.approvedBy = approvedBy;
    }

    public void touch() {
        this.updatedAt = Timestamp.now();
    }

    public boolean isApproved() {
        return "approved".equals(status);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public ApprovalMetadata getApprovalMetadata() {
        return approvalMetadata;
    }

    public void setApprovalMetadata(ApprovalMetadata approvalMetadata) {
        this.approvalMetadata = approvalMetadata;
    }

    /**
     * Get the first category from categoryIds list
     * Helper method for PublicContentService
     */
    public Category getCategory() {
        // This returns null for now - will need to be populated by service layer
        return null;
    }
}

