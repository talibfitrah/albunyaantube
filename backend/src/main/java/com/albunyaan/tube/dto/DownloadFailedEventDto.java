package com.albunyaan.tube.dto;

import jakarta.validation.constraints.NotBlank;

/**
 * P4-T1: DTO for download failed analytics event
 */
public class DownloadFailedEventDto {

    @NotBlank(message = "videoId is required")
    private String videoId;

    @NotBlank(message = "errorReason is required")
    private String errorReason;

    private String deviceType = "unknown";

    public DownloadFailedEventDto() {}

    public DownloadFailedEventDto(String videoId, String errorReason, String deviceType) {
        this.videoId = videoId;
        this.errorReason = errorReason;
        setDeviceType(deviceType);
    }

    public String getVideoId() {
        return videoId;
    }

    public void setVideoId(String videoId) {
        this.videoId = videoId;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }

    public String getDeviceType() {
        return deviceType;
    }

    public void setDeviceType(String deviceType) {
        this.deviceType = deviceType != null ? deviceType : "unknown";
    }
}
