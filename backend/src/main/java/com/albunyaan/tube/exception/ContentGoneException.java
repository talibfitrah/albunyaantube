package com.albunyaan.tube.exception;

/** Thrown when content is explicitly blocked by admin (REJECTED/ARCHIVED). Maps to HTTP 410 Gone. */
public class ContentGoneException extends RuntimeException {
    public ContentGoneException(String resourceType, String resourceId) {
        super(String.format("%s has been removed: %s", resourceType, resourceId));
    }
}
