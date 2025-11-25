package com.albunyaan.tube.dto;

import com.google.cloud.Timestamp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

/**
 * Content Validation: Archived Content DTO
 *
 * Represents an archived content item (channel, playlist, or video)
 * for display in the archived content review UI.
 */
public class ArchivedContentDto {

    /**
     * Document ID
     */
    private String id;

    /**
     * Content type: "CHANNEL", "PLAYLIST", or "VIDEO"
     */
    private String type;

    /**
     * YouTube ID (channel ID, playlist ID, or video ID)
     */
    private String youtubeId;

    /**
     * Title/name of the content
     */
    private String title;

    /**
     * Thumbnail URL
     */
    private String thumbnailUrl;

    /**
     * Category name (if assigned)
     */
    private String category;

    /**
     * When the content was archived (ISO 8601 string)
     */
    private String archivedAt;

    /**
     * When the content was last validated (ISO 8601 string)
     */
    private String lastValidatedAt;

    /**
     * Additional metadata (subscribers, video count, duration, etc.)
     */
    private String metadata;

    public ArchivedContentDto() {
    }

    // Builder-style setters for fluent API
    public ArchivedContentDto id(String id) {
        this.id = id;
        return this;
    }

    public ArchivedContentDto type(String type) {
        this.type = type;
        return this;
    }

    public ArchivedContentDto youtubeId(String youtubeId) {
        this.youtubeId = youtubeId;
        return this;
    }

    public ArchivedContentDto title(String title) {
        this.title = title;
        return this;
    }

    public ArchivedContentDto thumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
        return this;
    }

    public ArchivedContentDto category(String category) {
        this.category = category;
        return this;
    }

    public ArchivedContentDto archivedAt(Timestamp archivedAt) {
        this.archivedAt = formatTimestamp(archivedAt);
        return this;
    }

    public ArchivedContentDto lastValidatedAt(Timestamp lastValidatedAt) {
        this.lastValidatedAt = formatTimestamp(lastValidatedAt);
        return this;
    }

    public ArchivedContentDto metadata(String metadata) {
        this.metadata = metadata;
        return this;
    }

    // Standard Getters and Setters

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

    public String getYoutubeId() {
        return youtubeId;
    }

    public void setYoutubeId(String youtubeId) {
        this.youtubeId = youtubeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String thumbnailUrl) {
        this.thumbnailUrl = thumbnailUrl;
    }

    public String getCategory() {
        return category;
    }

    public void setCategory(String category) {
        this.category = category;
    }

    public String getArchivedAt() {
        return archivedAt;
    }

    public void setArchivedAt(String archivedAt) {
        this.archivedAt = archivedAt;
    }

    public String getLastValidatedAt() {
        return lastValidatedAt;
    }

    public void setLastValidatedAt(String lastValidatedAt) {
        this.lastValidatedAt = lastValidatedAt;
    }

    public String getMetadata() {
        return metadata;
    }

    public void setMetadata(String metadata) {
        this.metadata = metadata;
    }

    /**
     * Convert Google Cloud Timestamp to ISO 8601 string for JSON serialization
     */
    private static String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }
}
