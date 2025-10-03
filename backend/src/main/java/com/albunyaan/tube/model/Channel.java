package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

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
     * Assigned category IDs (can be multiple)
     */
    private List<String> categoryIds;

    /**
     * Approval status: "pending" | "approved" | "rejected"
     */
    private String status;

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

    public Channel() {
        this.categoryIds = new ArrayList<>();
        this.excludedItems = new ExcludedItems();
        this.status = "pending";
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

        public List<String> getVideos() {
            return videos;
        }

        public void setVideos(List<String> videos) {
            this.videos = videos;
        }

        public List<String> getLiveStreams() {
            return liveStreams;
        }

        public void setLiveStreams(List<String> liveStreams) {
            this.liveStreams = liveStreams;
        }

        public List<String> getShorts() {
            return shorts;
        }

        public void setShorts(List<String> shorts) {
            this.shorts = shorts;
        }

        public List<String> getPlaylists() {
            return playlists;
        }

        public void setPlaylists(List<String> playlists) {
            this.playlists = playlists;
        }

        public List<String> getPosts() {
            return posts;
        }

        public void setPosts(List<String> posts) {
            this.posts = posts;
        }

        public int getTotalExcludedCount() {
            return videos.size() + liveStreams.size() + shorts.size() + playlists.size() + posts.size();
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
        this.categoryIds = categoryIds;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public ExcludedItems getExcludedItems() {
        return excludedItems;
    }

    public void setExcludedItems(ExcludedItems excludedItems) {
        this.excludedItems = excludedItems;
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

    public boolean isPending() {
        return "pending".equals(status);
    }
}
