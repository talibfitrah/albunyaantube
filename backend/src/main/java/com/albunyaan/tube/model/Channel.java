package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;
import com.google.cloud.firestore.annotation.Exclude;

import java.util.ArrayList;
import java.util.List;

/**
 * FIREBASE-MIGRATE-03: Channel Model (Firestore)
 *
 * Represents a YouTube channel added to the master list.
 * Stores YouTube channel ID and exclusions for specific content types.
 *
 * Collection: channels
 */
public class Channel {

    @DocumentId
    private String id;

    /**
     * YouTube channel ID (e.g., "UCxxxxxx")
     */
    private String youtubeId;

    /**
     * Cached YouTube metadata
     */
    private String name;
    private String description;
    private String thumbnailUrl;
    private Long subscribers;
    private Integer videoCount;

    /**
     * Assigned category IDs (can be multiple)
     */
    private List<String> categoryIds;

    /**
     * Denormalized category snapshot persisted in Firestore for quick reads.
     */
    private Category category;

    /**
     * Approval status: "PENDING" | "APPROVED" | "REJECTED"
     */
    private String status;

    /**
     * Backwards-compatible boolean flags stored in Firestore.
     */
    private Boolean pending;
    private Boolean approved;

    /**
     * Excluded content from this channel
     */
    private ExcludedItems excludedItems;

    /**
     * Metadata
     */
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String submittedBy; // Firebase UID of moderator/admin
    private String approvedBy;  // Firebase UID of admin (if approved)

    /**
     * Approval metadata (BACKEND-APPR-01)
     */
    private ApprovalMetadata approvalMetadata;

    public Channel() {
        this.categoryIds = new ArrayList<>();
        this.excludedItems = new ExcludedItems();
        this.status = "PENDING";
        this.pending = Boolean.TRUE;
        this.approved = Boolean.FALSE;
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
    }

    public Channel(String youtubeId) {
        this();
        this.youtubeId = youtubeId;
    }

    public static class ExcludedItems {
        private List<String> videos = new ArrayList<>();
        private List<String> liveStreams = new ArrayList<>();
        private List<String> shorts = new ArrayList<>();
        private List<String> playlists = new ArrayList<>();
        private List<String> posts = new ArrayList<>();
        private Integer totalExcludedCount;

        public List<String> getVideos() {
            return videos;
        }

        public void setVideos(List<String> videos) {
            this.videos = videos != null ? new ArrayList<>(videos) : new ArrayList<>();
            recalculateTotal();
        }

        public List<String> getLiveStreams() {
            return liveStreams;
        }

        public void setLiveStreams(List<String> liveStreams) {
            this.liveStreams = liveStreams != null ? new ArrayList<>(liveStreams) : new ArrayList<>();
            recalculateTotal();
        }

        public List<String> getShorts() {
            return shorts;
        }

        public void setShorts(List<String> shorts) {
            this.shorts = shorts != null ? new ArrayList<>(shorts) : new ArrayList<>();
            recalculateTotal();
        }

        public List<String> getPlaylists() {
            return playlists;
        }

        public void setPlaylists(List<String> playlists) {
            this.playlists = playlists != null ? new ArrayList<>(playlists) : new ArrayList<>();
            recalculateTotal();
        }

        public List<String> getPosts() {
            return posts;
        }

        public void setPosts(List<String> posts) {
            this.posts = posts != null ? new ArrayList<>(posts) : new ArrayList<>();
            recalculateTotal();
        }

        public int getTotalExcludedCount() {
            if (totalExcludedCount != null) {
                return totalExcludedCount;
            }
            return calculateTotalExcludedCount();
        }

        public void setTotalExcludedCount(Integer totalExcludedCount) {
            this.totalExcludedCount = totalExcludedCount;
        }

        private int calculateTotalExcludedCount() {
            return videos.size() + liveStreams.size() + shorts.size() + playlists.size() + posts.size();
        }

        private void recalculateTotal() {
            this.totalExcludedCount = calculateTotalExcludedCount();
        }
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
        this.categoryIds = categoryIds != null ? new ArrayList<>(categoryIds) : new ArrayList<>();
    }

    public Category getCategory() {
        return category;
    }

    public void setCategory(Category category) {
        this.category = category;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        if (status == null) {
            this.status = null;
            this.pending = null;
            this.approved = null;
            return;
        }

        this.status = status.toUpperCase();
        this.pending = "PENDING".equals(this.status);
        this.approved = "APPROVED".equals(this.status);
    }

    public Boolean getPending() {
        if (pending != null) {
            return pending;
        }
        return status != null && "PENDING".equalsIgnoreCase(status);
    }

    public void setPending(Boolean pending) {
        this.pending = pending;
        if (Boolean.TRUE.equals(pending)) {
            this.status = "PENDING";
            this.approved = Boolean.FALSE;
        }
    }

    public Boolean getApproved() {
        if (approved != null) {
            return approved;
        }
        return status != null && "APPROVED".equalsIgnoreCase(status);
    }

    public void setApproved(Boolean approved) {
        this.approved = approved;
        if (Boolean.TRUE.equals(approved)) {
            this.status = "APPROVED";
            this.pending = Boolean.FALSE;
        }
    }

    public ExcludedItems getExcludedItems() {
        return excludedItems;
    }

    public void setExcludedItems(ExcludedItems excludedItems) {
        this.excludedItems = excludedItems != null ? excludedItems : new ExcludedItems();
        this.excludedItems.recalculateTotal();
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

    /**
     * Convenience method to check if channel is approved.
     * Excluded from Firestore serialization to avoid conflict with getApproved()
     */
    @Exclude
    public boolean isApproved() {
        return status != null && status.equalsIgnoreCase("APPROVED");
    }

    /**
     * Convenience method to check if channel is pending approval.
     * Excluded from Firestore serialization
     */
    @Exclude
    public boolean isPending() {
        return status != null && status.equalsIgnoreCase("PENDING");
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
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

    public Long getSubscribers() {
        return subscribers;
    }

    public void setSubscribers(Long subscribers) {
        this.subscribers = subscribers;
    }

    public Integer getVideoCount() {
        return videoCount;
    }

    public void setVideoCount(Integer videoCount) {
        this.videoCount = videoCount;
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
    @Exclude
    public String getFirstCategoryId() {
        return categoryIds != null && !categoryIds.isEmpty() ? categoryIds.get(0) : null;
    }
}

