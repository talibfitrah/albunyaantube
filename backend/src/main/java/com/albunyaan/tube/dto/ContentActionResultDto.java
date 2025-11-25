package com.albunyaan.tube.dto;

import java.util.ArrayList;
import java.util.List;

/**
 * Content Validation: Content Action Result DTO
 *
 * Response for bulk action requests (delete/restore).
 */
public class ContentActionResultDto {

    private String action;
    private String type;
    private int successCount;
    private int failureCount;
    private List<String> failedIds;
    private String message;

    public ContentActionResultDto() {
        this.failedIds = new ArrayList<>();
    }

    public ContentActionResultDto(String action, String type) {
        this();
        this.action = action;
        this.type = type;
    }

    // Builder-style methods
    public ContentActionResultDto success(int count) {
        this.successCount = count;
        return this;
    }

    public ContentActionResultDto failure(int count, List<String> ids) {
        this.failureCount = count;
        this.failedIds = ids != null ? ids : new ArrayList<>();
        return this;
    }

    public ContentActionResultDto message(String message) {
        this.message = message;
        return this;
    }

    // Getters and Setters

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public int getSuccessCount() {
        return successCount;
    }

    public void setSuccessCount(int successCount) {
        this.successCount = successCount;
    }

    public int getFailureCount() {
        return failureCount;
    }

    public void setFailureCount(int failureCount) {
        this.failureCount = failureCount;
    }

    public List<String> getFailedIds() {
        return failedIds;
    }

    public void setFailedIds(List<String> failedIds) {
        this.failedIds = failedIds;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public boolean isPartialSuccess() {
        return successCount > 0 && failureCount > 0;
    }

    public boolean isFullSuccess() {
        return failureCount == 0 && successCount > 0;
    }
}
