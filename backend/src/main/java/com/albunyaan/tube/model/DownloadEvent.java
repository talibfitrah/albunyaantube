package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

public class DownloadEvent {
    @DocumentId
    private String id;
    private String videoId;
    private String userId;
    private String eventType;
    private String quality;
    private Long fileSize;
    private Timestamp timestamp;
    private String deviceType;
    private String errorReason;

    public DownloadEvent() {
        this.timestamp = Timestamp.now();
    }

    public DownloadEvent(String videoId, String userId, String eventType) {
        this();
        this.videoId = videoId;
        this.userId = userId;
        this.eventType = eventType;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getUserId() { return userId; }
    public void setUserId(String userId) { this.userId = userId; }
    public String getEventType() { return eventType; }
    public void setEventType(String eventType) { this.eventType = eventType; }
    public String getQuality() { return quality; }
    public void setQuality(String quality) { this.quality = quality; }
    public Long getFileSize() { return fileSize; }
    public void setFileSize(Long fileSize) { this.fileSize = fileSize; }
    public Timestamp getTimestamp() { return timestamp; }
    public void setTimestamp(Timestamp timestamp) { this.timestamp = timestamp; }
    public String getDeviceType() { return deviceType; }
    public void setDeviceType(String deviceType) { this.deviceType = deviceType; }
    public String getErrorReason() { return errorReason; }
    public void setErrorReason(String errorReason) { this.errorReason = errorReason; }
}
