package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

/**
 * Result for a single item in a simple format import.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimpleImportItemResult {

    private String youtubeId;
    private String title;
    private String type; // CHANNEL, PLAYLIST, VIDEO
    private String status; // SUCCESS, SKIPPED, FAILED
    private String errorReason;

    public SimpleImportItemResult() {
    }

    public SimpleImportItemResult(String youtubeId, String title, String type, String status, String errorReason) {
        this.youtubeId = youtubeId;
        this.title = title;
        this.type = type;
        this.status = status;
        this.errorReason = errorReason;
    }

    public static SimpleImportItemResult success(String youtubeId, String title, String type) {
        return new SimpleImportItemResult(youtubeId, title, type, "SUCCESS", null);
    }

    public static SimpleImportItemResult skipped(String youtubeId, String title, String type, String reason) {
        return new SimpleImportItemResult(youtubeId, title, type, "SKIPPED", reason);
    }

    public static SimpleImportItemResult failed(String youtubeId, String title, String type, String reason) {
        return new SimpleImportItemResult(youtubeId, title, type, "FAILED", reason);
    }

    // Getters and Setters
    public String getYoutubeId() {
        return youtubeId;
    }

    public void setYoutubeId(String youtubeId) {
        this.youtubeId = youtubeId;
    }

    public String getTitle() {
        return title;
    }

    public void setTitle(String title) {
        this.title = title;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public String getErrorReason() {
        return errorReason;
    }

    public void setErrorReason(String errorReason) {
        this.errorReason = errorReason;
    }
}
