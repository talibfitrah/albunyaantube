package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.ArrayList;
import java.util.List;

/**
 * FIREBASE-MIGRATE-03: Playlist Model (Firestore)
 *
 * Represents a YouTube playlist added to the master list.
 *
 * Collection: playlists
 */
public class Playlist {

    @DocumentId
    private String id;

    /**
     * YouTube playlist ID (e.g., "PLxxxxxx")
     */
    private String youtubeId;

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
}
