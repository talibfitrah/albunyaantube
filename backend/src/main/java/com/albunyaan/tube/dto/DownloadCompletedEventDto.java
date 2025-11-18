package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * P4-T1: DTO for download completed analytics event
 */
public class DownloadCompletedEventDto {

    @NotBlank(message = "videoId is required")
    private String videoId;

    @NotBlank(message = "quality is required")
    private String quality;

    private Long fileSize;

    private String deviceType = "unknown";

    public DownloadCompletedEventDto() {}

    public DownloadCompletedEventDto(String videoId, String quality, Long fileSize, String deviceType) {
        this.videoId = videoId;
        this.quality = quality;
        this.fileSize = fileSize;
        setDeviceType(deviceType);
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getQuality() {
        return quality;
    }

    public void setQuality(String quality) {
        this.quality = quality;
    }

    public Long getFileSize() {
        return fileSize;
    }

    public void setFileSize(Long fileSize) {
        this.fileSize = fileSize;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType != null ? deviceType : "unknown";
    }
}
