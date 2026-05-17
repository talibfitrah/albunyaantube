package com.albunyaan.tube.exception;

/**
 * Thrown when a content item exists in the registry but has been explicitly
 * removed, archived, or rejected by an admin. Maps to HTTP 410 Gone.
 *
 * Distinct from {@link ResourceNotFoundException} (404) which is used for
 * items that were never in the registry (e.g. channel-sourced videos that
 * are not individually registered). The Android client uses this distinction:
 * 404 = not in registry → fail-open and let NewPipe resolve;
 * 410 = explicitly blocked → show "Content not available".
 */
public class ContentGoneException extends RuntimeException {

    private final String resourceType;
    private final String resourceId;

    public ContentGoneException(String resourceType, String resourceId) {
        super(String.format("%s has been removed: %s", resourceType, resourceId));
        this.resourceType = resourceType;
        this.resourceId = resourceId;
    }

    public String getResourceType() {
        return resourceType;
    }

    public String getResourceId() {
        return resourceId;
    }
}
