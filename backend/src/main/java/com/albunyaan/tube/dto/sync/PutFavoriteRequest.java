package com.albunyaan.tube.dto.sync;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;

/** Cubic R-final5 P2 — see {@link PutSubscriptionRequest} for rationale. */
@JsonIgnoreProperties(ignoreUnknown = false)
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

    // A10 — import metadata fields
    private String approvalStatus;
    private String source;
    private Long importedAt;

    public String getApprovalStatus() {
        return approvalStatus;
    }

    public void setApprovalStatus(String v) {
        this.approvalStatus = v;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String v) {
        this.source = v;
    }

    public Long getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(Long v) {
        this.importedAt = v;
    }
}
