package com.albunyaan.tube.dto;

import java.util.List;

/**
 * DTO for playlist details to avoid exposing NewPipe library types in API contracts
 */
public class PlaylistDetailsDto {
    private String id;
    private String name;
    private String url;
    private String description;
    private String thumbnailUrl;
    private String uploaderName;
    private String uploaderUrl;
    private Long streamCount;
    private List<StreamItemDto> relatedStreams;

    public PlaylistDetailsDto() {}

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getUrl() {
        return url;
    }

    public void setUrl(String url) {
        this.url = url;
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

    public String getUploaderName() {
        return uploaderName;
    }

    public void setUploaderName(String uploaderName) {
        this.uploaderName = uploaderName;
    }

    public String getUploaderUrl() {
        return uploaderUrl;
    }

    public void setUploaderUrl(String uploaderUrl) {
        this.uploaderUrl = uploaderUrl;
    }

    public Long getStreamCount() {
        return streamCount;
    }

    public void setStreamCount(Long streamCount) {
        this.streamCount = streamCount;
    }

    public List<StreamItemDto> getRelatedStreams() {
        return relatedStreams;
    }

    public void setRelatedStreams(List<StreamItemDto> relatedStreams) {
        this.relatedStreams = relatedStreams;
    }
}
