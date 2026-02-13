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
    /**
     * Lowercase version of title for case-insensitive prefix queries.
     * Auto-maintained by setTitle().
     */
    private String titleLower;
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
     * Count of excluded videos - automatically maintained by setExcludedVideoIds().
     * Enables efficient Firestore queries with whereGreaterThan("excludedVideoCount", 0).
     */
    private Integer excludedVideoCount;

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

    /**
     * Validation status - whether the playlist still exists on YouTube
     * Null if not yet validated
     */
    private ValidationStatus validationStatus;

    /**
     * Last time this playlist was validated against YouTube
     */
    private Timestamp lastValidatedAt;

    /**
     * Display order for custom sorting in Content Library.
     * Lower values appear first. Null by default until explicitly set.
     */
    private Integer displayOrder;

    /**
     * Keywords/tags for improved search accuracy.
     * Optional field - can be null or empty.
     */
    private List<String> keywords;

    /**
     * Lowercase version of keywords for case-insensitive array-contains queries.
     * Auto-maintained by setKeywords().
     */
    private List<String> keywordsLower;

    public Playlist() {
        this.categoryIds = new ArrayList<>();
        this.excludedVideoIds = new ArrayList<>();
        this.excludedVideoCount = 0;
        this.status = "PENDING";
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
        // Normalize to uppercase for consistency with Channel and Video models
        this.status = (status != null) ? status.toUpperCase() : null;
    }

    public List<String> getExcludedVideoIds() {
        return excludedVideoIds;
    }

    public void setExcludedVideoIds(List<String> excludedVideoIds) {
        this.excludedVideoIds = excludedVideoIds;
        // Auto-maintain excludedVideoCount for efficient Firestore queries
        this.excludedVideoCount = (excludedVideoIds != null) ? excludedVideoIds.size() : 0;
    }

    public Integer getExcludedVideoCount() {
        return excludedVideoCount;
    }

    public void setExcludedVideoCount(Integer excludedVideoCount) {
        this.excludedVideoCount = excludedVideoCount;
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
        return "APPROVED".equals(status);
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
        this.titleLower = title != null ? title.toLowerCase(java.util.Locale.ROOT) : null;
    }

    public String getTitleLower() {
        return titleLower;
    }

    public void setTitleLower(String titleLower) {
        this.titleLower = titleLower;
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

    public ValidationStatus getValidationStatus() {
        return validationStatus;
    }

    public void setValidationStatus(ValidationStatus validationStatus) {
        this.validationStatus = validationStatus;
    }

    public Timestamp getLastValidatedAt() {
        return lastValidatedAt;
    }

    public void setLastValidatedAt(Timestamp lastValidatedAt) {
        this.lastValidatedAt = lastValidatedAt;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
        this.keywordsLower = keywords != null
                ? keywords.stream().filter(k -> k != null).map(k -> k.toLowerCase(java.util.Locale.ROOT)).collect(java.util.stream.Collectors.toList())
                : null;
    }

    public List<String> getKeywordsLower() {
        return keywordsLower;
    }

    public void setKeywordsLower(List<String> keywordsLower) {
        this.keywordsLower = keywordsLower;
    }
}

