package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;

/**
 * Unified DTO for content items (channels, playlists, videos) in public API.
 * Fields are nullable to accommodate different content types.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ContentItemDto {
    private String id;
    private String type; // CHANNEL, PLAYLIST, VIDEO

    // Common fields
    private String title;
    private String name; // For channels
    private String category;
    private String description;
    private String thumbnailUrl;

    // Video-specific
    private Integer durationSeconds;
    private Integer uploadedDaysAgo;
    private Long viewCount;
    private String channelTitle; // channel name for the video

    // Channel-specific
    private Long subscribers;
    private Integer videoCount;

    // Playlist-specific
    private Integer itemCount;

    // Search-only: not serialized to API response
    @JsonIgnore
    private List<String> keywords;

    public ContentItemDto() {
    }

    // Builder pattern
    public static ContentItemDto channel(String id, String name, String category,
                                         Long subscribers, String description,
                                         String thumbnailUrl, Integer videoCount,
                                         List<String> keywords) {
        ContentItemDto dto = new ContentItemDto();
        dto.id = id;
        dto.type = "CHANNEL";
        dto.name = name;
        dto.category = category;
        dto.subscribers = subscribers;
        dto.description = description;
        dto.thumbnailUrl = thumbnailUrl;
        dto.videoCount = videoCount;
        dto.keywords = keywords;
        return dto;
    }

    public static ContentItemDto playlist(String id, String title, String category,
                                          Integer itemCount, String description,
                                          String thumbnailUrl, List<String> keywords) {
        ContentItemDto dto = new ContentItemDto();
        dto.id = id;
        dto.type = "PLAYLIST";
        dto.title = title;
        dto.category = category;
        dto.itemCount = itemCount;
        dto.description = description;
        dto.thumbnailUrl = thumbnailUrl;
        dto.keywords = keywords;
        return dto;
    }

    public static ContentItemDto video(String id, String title, String category,
                                       Integer durationSeconds, Integer uploadedDaysAgo,
                                       String description, String thumbnailUrl, Long viewCount,
                                       String channelTitle, List<String> keywords) {
        ContentItemDto dto = new ContentItemDto();
        dto.id = id;
        dto.type = "VIDEO";
        dto.title = title;
        dto.category = category;
        dto.durationSeconds = durationSeconds;
        dto.uploadedDaysAgo = uploadedDaysAgo;
        dto.description = description;
        dto.thumbnailUrl = thumbnailUrl;
        dto.viewCount = viewCount;
        dto.channelTitle = channelTitle;
        dto.keywords = keywords;
        return dto;
    }

    // Getters and setters
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

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
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

    public Integer getUploadedDaysAgo() {
        return uploadedDaysAgo;
    }

    public void setUploadedDaysAgo(Integer uploadedDaysAgo) {
        this.uploadedDaysAgo = uploadedDaysAgo;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
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

    public Integer getItemCount() {
        return itemCount;
    }

    public void setItemCount(Integer itemCount) {
        this.itemCount = itemCount;
    }

    public String getChannelTitle() {
        return channelTitle;
    }

    public void setChannelTitle(String channelTitle) {
        this.channelTitle = channelTitle;
    }

    public List<String> getKeywords() {
        return keywords;
    }

    public void setKeywords(List<String> keywords) {
        this.keywords = keywords;
    }
}

