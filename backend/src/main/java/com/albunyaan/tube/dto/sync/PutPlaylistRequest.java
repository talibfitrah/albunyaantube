package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

public class PutPlaylistRequest {
    @NotBlank
    private String playlistUrl;
    @NotBlank
    private String name;
    private String thumbnailUrl;
    private String uploaderName;
    @NotNull
    private Long savedAt;

    public String getPlaylistUrl() {
        return playlistUrl;
    }

    public void setPlaylistUrl(String v) {
        this.playlistUrl = v;
    }

    public String getName() {
        return name;
    }

    public void setName(String v) {
        this.name = v;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String v) {
        this.thumbnailUrl = v;
    }

    public String getUploaderName() {
        return uploaderName;
    }

    public void setUploaderName(String v) {
        this.uploaderName = v;
    }

    public Long getSavedAt() {
        return savedAt;
    }

    public void setSavedAt(Long v) {
        this.savedAt = v;
    }
}
