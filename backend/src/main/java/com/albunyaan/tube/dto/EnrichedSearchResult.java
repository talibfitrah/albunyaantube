package com.albunyaan.tube.dto;

import com.google.api.services.youtube.model.SearchResult;
import com.google.api.services.youtube.model.ThumbnailDetails;

/**
 * Enriched search result with additional metadata from YouTube API
 */
public class EnrichedSearchResult {
    private String id;
    private String type; // channel, playlist, or video
    private String title;
    private String description;
    private String thumbnailUrl;
    private String publishedAt;
    private String channelId;
    private String channelTitle;

    // Channel-specific
    private Long subscriberCount;
    private Long videoCount;

    // Playlist-specific
    private Long itemCount;
    private java.util.List<String> videoThumbnails; // First 4 video thumbnails for playlists

    // Video-specific
    private Long viewCount;
    private String duration;

    public EnrichedSearchResult() {}

    public static EnrichedSearchResult fromSearchResult(SearchResult result, String type) {
        EnrichedSearchResult enriched = new EnrichedSearchResult();
        enriched.setType(type);

        if (result.getSnippet() != null) {
            enriched.setTitle(result.getSnippet().getTitle());
            enriched.setDescription(result.getSnippet().getDescription());
            enriched.setPublishedAt(result.getSnippet().getPublishedAt() != null ?
                result.getSnippet().getPublishedAt().toStringRfc3339() : null);
            enriched.setChannelId(result.getSnippet().getChannelId());
            enriched.setChannelTitle(result.getSnippet().getChannelTitle());

            ThumbnailDetails thumbnails = result.getSnippet().getThumbnails();
            if (thumbnails != null) {
                if (thumbnails.getMedium() != null) {
                    enriched.setThumbnailUrl(thumbnails.getMedium().getUrl());
                } else if (thumbnails.getDefault() != null) {
                    enriched.setThumbnailUrl(thumbnails.getDefault().getUrl());
                }
            }
        }

        if (result.getId() != null) {
            if ("channel".equals(type)) {
                enriched.setId(result.getId().getChannelId());
            } else if ("playlist".equals(type)) {
                enriched.setId(result.getId().getPlaylistId());
            } else if ("video".equals(type)) {
                enriched.setId(result.getId().getVideoId());
            }
        }

        return enriched;
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

    public String getPublishedAt() {
        return publishedAt;
    }

    public void setPublishedAt(String publishedAt) {
        this.publishedAt = publishedAt;
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

    public Long getSubscriberCount() {
        return subscriberCount;
    }

    public void setSubscriberCount(Long subscriberCount) {
        this.subscriberCount = subscriberCount;
    }

    public Long getVideoCount() {
        return videoCount;
    }

    public void setVideoCount(Long videoCount) {
        this.videoCount = videoCount;
    }

    public Long getItemCount() {
        return itemCount;
    }

    public void setItemCount(Long itemCount) {
        this.itemCount = itemCount;
    }

    public Long getViewCount() {
        return viewCount;
    }

    public void setViewCount(Long viewCount) {
        this.viewCount = viewCount;
    }

    public String getDuration() {
        return duration;
    }

    public void setDuration(String duration) {
        this.duration = duration;
    }

    public java.util.List<String> getVideoThumbnails() {
        return videoThumbnails;
    }

    public void setVideoThumbnails(java.util.List<String> videoThumbnails) {
        this.videoThumbnails = videoThumbnails;
    }
}
