package com.albunyaan.tube.dto;

/**
 * DTO for the admin Content Sorting page — represents a content item within a category
 * with its sort position.
 */
public class ContentSortDto {
    private String contentId;
    private String contentType; // "channel", "playlist", "video"
    private String title;
    private String thumbnailUrl;
    private Integer position;
    private String youtubeId;

    public ContentSortDto() {}

    public ContentSortDto(String contentId, String contentType, String title,
                          String thumbnailUrl, Integer position, String youtubeId) {
        this.contentId = contentId;
        this.contentType = contentType;
        this.title = title;
        this.thumbnailUrl = thumbnailUrl;
        this.position = position;
        this.youtubeId = youtubeId;
    }

    public String getContentId() { return contentId; }
    public void setContentId(String contentId) { this.contentId = contentId; }

    public String getContentType() { return contentType; }
    public void setContentType(String contentType) { this.contentType = contentType; }

    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }

    public String getThumbnailUrl() { return thumbnailUrl; }
    public void setThumbnailUrl(String thumbnailUrl) { this.thumbnailUrl = thumbnailUrl; }

    public Integer getPosition() { return position; }
    public void setPosition(Integer position) { this.position = position; }

    public String getYoutubeId() { return youtubeId; }
    public void setYoutubeId(String youtubeId) { this.youtubeId = youtubeId; }
}
