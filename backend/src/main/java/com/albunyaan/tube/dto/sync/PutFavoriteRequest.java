package com.albunyaan.tube.dto.sync;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

public class PutFavoriteRequest {
    @NotBlank
    private String title;
    @NotBlank
    private String channelName;
    private String thumbnailUrl;
    @PositiveOrZero
    private int durationSeconds;
    @NotNull
    private Long addedAt;

    public String getTitle() {
        return title;
    }

    public void setTitle(String v) {
        this.title = v;
    }

    public String getChannelName() {
        return channelName;
    }

    public void setChannelName(String v) {
        this.channelName = v;
    }

    public String getThumbnailUrl() {
        return thumbnailUrl;
    }

    public void setThumbnailUrl(String v) {
        this.thumbnailUrl = v;
    }

    public int getDurationSeconds() {
        return durationSeconds;
    }

    public void setDurationSeconds(int v) {
        this.durationSeconds = v;
    }

    public Long getAddedAt() {
        return addedAt;
    }

    public void setAddedAt(Long v) {
        this.addedAt = v;
    }
}
