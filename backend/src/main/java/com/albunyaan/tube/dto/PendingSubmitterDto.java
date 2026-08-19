package com.albunyaan.tube.dto;

/**
 * One row of the by-user approvals tab: a person who has imported content that is still waiting
 * for review, and how much of it there is.
 */
public class PendingSubmitterDto {

    private String uid;
    private String displayName;
    private String email;
    private long pendingCount;

    public PendingSubmitterDto() {
    }

    public PendingSubmitterDto(String uid, String displayName, String email, long pendingCount) {
        this.uid = uid;
        this.displayName = displayName;
        this.email = email;
        this.pendingCount = pendingCount;
    }

    /**
     * What to show for this person: display name, else email, else the raw uid. A uid alone tells
     * a reviewer nothing, but it is better than an empty row for an account that no longer exists.
     */
    public String getLabel() {
        if (displayName != null && !displayName.isBlank()) {
            return displayName;
        }
        if (email != null && !email.isBlank()) {
            return email;
        }
        return uid;
    }

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public long getPendingCount() {
        return pendingCount;
    }

    public void setPendingCount(long pendingCount) {
        this.pendingCount = pendingCount;
    }
}
