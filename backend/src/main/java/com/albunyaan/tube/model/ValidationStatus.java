package com.albunyaan.tube.model;

/**
 * Enum representing the validation status of a video.
 * Indicates whether the video still exists on YouTube.
 */
public enum ValidationStatus {
    /**
     * Video exists and is accessible on YouTube
     */
    VALID,

    /**
     * Video is no longer available on YouTube (deleted, private, etc.)
     */
    UNAVAILABLE,

    /**
     * Error occurred while validating the video
     */
    ERROR
}
