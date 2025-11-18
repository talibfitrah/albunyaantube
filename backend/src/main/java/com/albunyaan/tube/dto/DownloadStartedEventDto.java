package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * P4-T1: DTO for download started analytics event
 */
public class DownloadStartedEventDto {

    @NotBlank(message = "videoId is required")
    private String videoId;

    @NotBlank(message = "quality is required")
    private String quality;

    private String deviceType = "unknown";

    public DownloadStartedEventDto() {}

    public DownloadStartedEventDto(String videoId, String quality, String deviceType) {
        this.videoId = videoId;
        this.quality = quality;
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

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType != null ? deviceType : "unknown";
    }
}
