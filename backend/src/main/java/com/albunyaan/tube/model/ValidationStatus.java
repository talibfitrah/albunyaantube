package com.albunyaan.tube.model;

/**
 * Enum representing the validation status of content (channels, playlists, videos).
 * Indicates whether the content still exists on YouTube.
 */
public enum ValidationStatus {
    /**
     * Content exists and is accessible on YouTube
     */
    VALID,

    /**
     * Content is no longer available on YouTube (deleted, private, etc.)
     */
    UNAVAILABLE,

    /**
     * Error occurred while validating the content
     */
    ERROR,

    /**
     * Content has been archived by admin (hidden from app, kept in database).
     * Used when validation detects unavailable content - auto-archived for review.
     */
    ARCHIVED;

    /**
     * Stage 9 B23/B26: a hold nothing but Restore lifts: ARCHIVED (a report's take-down, or validation), or a video's
     * legacy UNAVAILABLE. An import never writes over it, nor beside it under another doc id; nor does a bulk re-add.
     */
    public static boolean holds(ValidationStatus status) {
        return status == ARCHIVED || status == UNAVAILABLE;
    }
}

