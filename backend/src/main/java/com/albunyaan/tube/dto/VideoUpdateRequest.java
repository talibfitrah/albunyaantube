package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Partial-update body for PUT /api/admin/registry/videos/{id} (iOS Phase 3, offlineAllowed gate).
 *
 * <p>Deliberately NOT the {@link com.albunyaan.tube.model.Video} entity: Video's no-arg
 * constructor defaults status to "PENDING" and categoryIds to an empty list, so a
 * Jackson-deserialized partial body like {@code {"offlineAllowed": true}} arrived carrying a
 * non-null PENDING status — a null-guarded merge over the entity still silently un-approved
 * the video and wiped its categories. Every field here is nullable with no defaults:
 * null means "field absent, leave it alone".
 */
public class VideoUpdateRequest {

    private String title;
    private String description;
    private List<String> categoryIds;
    private String status;
    private String thumbnailUrl;
    private Integer durationSeconds;
    private Long viewCount;
    private Boolean offlineAllowed;

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

    public Boolean getOfflineAllowed() {
        return offlineAllowed;
    }

    public void setOfflineAllowed(Boolean offlineAllowed) {
        this.offlineAllowed = offlineAllowed;
    }
}
