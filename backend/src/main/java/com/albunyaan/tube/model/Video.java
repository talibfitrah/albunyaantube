package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.ArrayList;
import java.util.List;

/**
 * FIREBASE-MIGRATE-03: Video Model (Firestore)
 *
 * Represents individual YouTube videos added to the master list.
 *
 * Collection: videos
 */
public class Video {

    @DocumentId
    private String id;

    /**
     * YouTube video ID (e.g., "dQw4w9WgXcQ")
     */
    private String youtubeId;

    /**
     * YouTube metadata (cached from YouTube API)
     */
    private String title;
    private String description;
    private String thumbnailUrl;
    private Integer durationSeconds;
    private Long viewCount;
    private Timestamp uploadedAt;
    private String channelId;
    private String channelTitle;

    /**
     * Assigned category IDs
     */
    private List<String> categoryIds;

    /**
     * Approval status
     */
    private String status;

    /**
     * Metadata
     */
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String submittedBy;
    private String approvedBy;

    public Video() {
        this.categoryIds = new ArrayList<>();
        this.status = "pending";
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
    }

    public Video(String youtubeId) {
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

    public Integer getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(Integer durationSeconds) {
        this.durationSeconds = durationSeconds;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public Timestamp getUploadedAt() {
        return uploadedAt;
    }

    public void setUploadedAt(Timestamp uploadedAt) {
        this.uploadedAt = uploadedAt;
    }

    public String getChannelId() {
        return channelId;
    }

    public void setChannelId(String channelId) {
        this.channelId = channelId;
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public void setChannelTitle(String channelTitle) {
        this.channelTitle = channelTitle;
    }

    public void touch() {
        this.updatedAt = Timestamp.now();
    }

    public boolean isApproved() {
        return "APPROVED".equalsIgnoreCase(status);
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
