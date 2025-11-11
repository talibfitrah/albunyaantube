package com.albunyaan.tube.dto;

import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Enriched search result with additional metadata from NewPipeExtractor
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

    /**
     * Create EnrichedSearchResult from NewPipe ChannelInfoItem
     */
    public static EnrichedSearchResult fromChannelInfoItem(ChannelInfoItem channel) {
        EnrichedSearchResult result = new EnrichedSearchResult();
        result.setType("channel");
        result.setId(extractYouTubeId(channel.getUrl()));
        result.setTitle(channel.getName());
        result.setDescription(channel.getDescription());
        result.setThumbnailUrl(getBestThumbnailUrl(channel.getThumbnails()));
        result.setSubscriberCount(channel.getSubscriberCount());
        result.setVideoCount(channel.getStreamCount());
        return result;
    }

    /**
     * Create EnrichedSearchResult from NewPipe PlaylistInfoItem
     */
    public static EnrichedSearchResult fromPlaylistInfoItem(PlaylistInfoItem playlist) {
        EnrichedSearchResult result = new EnrichedSearchResult();
        result.setType("playlist");
        result.setId(extractYouTubeId(playlist.getUrl()));
        result.setTitle(playlist.getName());
        // Note: PlaylistInfoItem (from search results) does not provide description
        // Description is only available in PlaylistInfo (full playlist details)
        // To get description, the frontend should fetch full playlist details via getPlaylistDetails()
        result.setDescription("");
        result.setThumbnailUrl(getBestThumbnailUrl(playlist.getThumbnails()));
        result.setChannelTitle(playlist.getUploaderName());
        result.setItemCount(playlist.getStreamCount());
        return result;
    }

    /**
     * Create EnrichedSearchResult from NewPipe StreamInfoItem
     */
    public static EnrichedSearchResult fromStreamInfoItem(StreamInfoItem stream) {
        EnrichedSearchResult result = new EnrichedSearchResult();
        result.setType("video");
        result.setId(extractYouTubeId(stream.getUrl()));
        result.setTitle(stream.getName());
        result.setDescription("");
        result.setThumbnailUrl(getBestThumbnailUrl(stream.getThumbnails()));
        result.setChannelId(extractYouTubeId(stream.getUploaderUrl()));
        result.setChannelTitle(stream.getUploaderName());
        result.setViewCount(stream.getViewCount());
        result.setDuration(formatDuration(stream.getDuration()));

        // Format upload date if available
        if (stream.getUploadDate() != null) {
            try {
                result.setPublishedAt(stream.getUploadDate()
                        .offsetDateTime()
                        .format(DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (Exception e) {
                // Ignore date formatting errors
            }
        }

        return result;
    }

    /**
     * Extract YouTube ID from URL
     * e.g., "https://www.youtube.com/channel/UCxxxxxx" -> "UCxxxxxx"
     */
    private static String extractYouTubeId(String url) {
        if (url == null || url.isEmpty()) {
            return null;
        }
        // Extract last segment from URL
        String[] parts = url.split("/");
        if (parts.length > 0) {
            String lastPart = parts[parts.length - 1];
            // Remove query parameters if present
            return lastPart.split("\\?")[0];
        }
        return url;
    }

    /**
     * Get best quality thumbnail URL from NewPipe Image list
     */
    private static String getBestThumbnailUrl(List<Image> thumbnails) {
        if (thumbnails == null || thumbnails.isEmpty()) {
            return null;
        }
        // NewPipe provides images sorted by quality, so take the first one
        return thumbnails.get(0).getUrl();
    }

    /**
     * Format duration from seconds to ISO 8601 duration string (PT#M#S)
     */
    private static String formatDuration(long durationSeconds) {
        if (durationSeconds <= 0) {
            return "PT0S";
        }
        long hours = durationSeconds / 3600;
        long minutes = (durationSeconds % 3600) / 60;
        long seconds = durationSeconds % 60;

        StringBuilder duration = new StringBuilder("PT");
        if (hours > 0) {
            duration.append(hours).append("H");
        }
        if (minutes > 0) {
            duration.append(minutes).append("M");
        }
        if (seconds > 0 || (hours == 0 && minutes == 0)) {
            duration.append(seconds).append("S");
        }
        return duration.toString();
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

