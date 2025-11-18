package com.albunyaan.tube.dto;

/**
 * Download authorization token.
 *
 * <p>Tokens are short-lived (configured in DownloadTokenService) and should be used
 * immediately to fetch the manifest. Clients must start downloads promptly after
 * fetching the manifest as URLs are only valid for the token's lifetime.
 */
public class DownloadTokenDto {
    private String token;
    /** Token expiration time in milliseconds since epoch (UTC) */
    private long expiresAtMillis;
    private String videoId;

    public DownloadTokenDto() {}

    public DownloadTokenDto(String token, long expiresAtMillis, String videoId) {
        this.token = token;
        this.expiresAtMillis = expiresAtMillis;
        this.videoId = videoId;
    }

    public String getToken() { return token; }
    public void setToken(String token) { this.token = token; }
    /** @return Token expiration time in milliseconds since epoch (UTC) */
    public long getExpiresAtMillis() { return expiresAtMillis; }
    public void setExpiresAtMillis(long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }
    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
}

