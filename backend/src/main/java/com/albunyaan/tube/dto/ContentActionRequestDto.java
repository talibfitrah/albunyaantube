package com.albunyaan.tube.dto;

import java.util.List;

/**
 * Content Validation: Content Action Request DTO
 *
 * Request body for bulk actions on archived content (delete or restore).
 */
public class ContentActionRequestDto {

    /**
     * Action to perform: "DELETE" or "RESTORE"
     */
    private String action;

    /**
     * Content type: "CHANNEL", "PLAYLIST", or "VIDEO"
     */
    private String type;

    /**
     * List of content IDs to act on
     */
    private List<String> ids;

    /**
     * Optional reason for the action (for audit logging)
     */
    private String reason;

    public ContentActionRequestDto() {
    }

    public ContentActionRequestDto(String action, String type, List<String> ids) {
        this.action = action;
        this.type = type;
        this.ids = ids;
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

    public List<String> getIds() {
        return ids;
    }

    public void setIds(List<String> ids) {
        this.ids = ids;
    }

    public String getReason() {
        return reason;
    }

    public void setReason(String reason) {
        this.reason = reason;
    }

    /**
     * Validate the request
     */
    public boolean isValid() {
        if (action == null || (!action.equalsIgnoreCase("DELETE") && !action.equalsIgnoreCase("RESTORE"))) {
            return false;
        }
        if (type == null || (!type.equalsIgnoreCase("CHANNEL") && !type.equalsIgnoreCase("PLAYLIST") && !type.equalsIgnoreCase("VIDEO"))) {
            return false;
        }
        return ids != null && !ids.isEmpty();
    }
}
