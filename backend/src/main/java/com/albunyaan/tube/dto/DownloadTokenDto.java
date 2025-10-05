package com.albunyaan.tube.dto;

public class DownloadTokenDto {
    private String token;
    private long expiresAt;
    private String videoId;

    public DownloadTokenDto() {}

    public DownloadTokenDto(String token, long expiresAt, String videoId) {
        this.token = token;
        this.expiresAt = expiresAt;
        this.videoId = videoId;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
}
