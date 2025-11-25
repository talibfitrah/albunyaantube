package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * Validation Run Model (Firestore)
 *
 * Records each video validation job execution for tracking and metrics.
 *
 * Collection: validation_runs
 * Document ID: Auto-generated
 */
public class ValidationRun {

    // Trigger type constants
    public static final String TRIGGER_SCHEDULED = "SCHEDULED";
    public static final String TRIGGER_MANUAL = "MANUAL";
    public static final String TRIGGER_IMPORT = "IMPORT";
    public static final String TRIGGER_EXPORT = "EXPORT";

    // Status constants
    public static final String STATUS_RUNNING = "RUNNING";
    public static final String STATUS_COMPLETED = "COMPLETED";
    public static final String STATUS_FAILED = "FAILED";

    @DocumentId
    private String id;

    /**
     * Trigger type: TRIGGER_SCHEDULED, TRIGGER_MANUAL, TRIGGER_IMPORT, TRIGGER_EXPORT
     */
    private String triggerType;

    /**
     * Actor UID for manual triggers (null for scheduled runs)
     */
    private String triggeredBy;

    /**
     * Actor's display name (cached for easier display)
     */
    private String triggeredByDisplayName;

    /**
     * Status: STATUS_RUNNING, STATUS_COMPLETED, STATUS_FAILED
     */
    private String status;

    /**
     * Number of channels checked in this run
     */
    private int channelsChecked;

    /**
     * Number of channels marked as archived (unavailable on YouTube)
     */
    private int channelsMarkedArchived;

    /**
     * Number of playlists checked in this run
     */
    private int playlistsChecked;

    /**
     * Number of playlists marked as archived (unavailable on YouTube)
     */
    private int playlistsMarkedArchived;

    /**
     * Number of videos checked in this run
     */
    private int videosChecked;

    /**
     * Number of videos marked as archived (unavailable on YouTube)
     */
    private int videosMarkedArchived;

    /**
     * Legacy field for backward compatibility
     * @deprecated Use videosMarkedArchived instead
     */
    private int videosMarkedUnavailable;

    /**
     * Number of errors encountered during validation
     */
    private int errorCount;

    /**
     * Additional details about the run (JSON-like map)
     * Can include: videoIds, error messages, configuration, etc.
     */
    private Map<String, Object> details;

    /**
     * Timestamp when the validation started
     */
    private Timestamp startedAt;

    /**
     * Timestamp when the validation completed
     */
    private Timestamp completedAt;

    /**
     * Duration in milliseconds
     */
    private Long durationMs;

    public ValidationRun() {
        this.startedAt = Timestamp.now();
        this.status = STATUS_RUNNING;
        this.channelsChecked = 0;
        this.channelsMarkedArchived = 0;
        this.playlistsChecked = 0;
        this.playlistsMarkedArchived = 0;
        this.videosChecked = 0;
        this.videosMarkedArchived = 0;
        this.videosMarkedUnavailable = 0; // Legacy field
        this.errorCount = 0;
        this.details = new HashMap<>();
    }

    public ValidationRun(String triggerType) {
        this();
        this.triggerType = triggerType;
    }

    public ValidationRun(String triggerType, String triggeredBy, String triggeredByDisplayName) {
        this(triggerType);
        this.triggeredBy = triggeredBy;
        this.triggeredByDisplayName = triggeredByDisplayName;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getTriggerType() {
        return triggerType;
    }

    public void setTriggerType(String triggerType) {
        this.triggerType = triggerType;
    }

    public String getTriggeredBy() {
        return triggeredBy;
    }

    public void setTriggeredBy(String triggeredBy) {
        this.triggeredBy = triggeredBy;
    }

    public String getTriggeredByDisplayName() {
        return triggeredByDisplayName;
    }

    public void setTriggeredByDisplayName(String triggeredByDisplayName) {
        this.triggeredByDisplayName = triggeredByDisplayName;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public int getChannelsChecked() {
        return channelsChecked;
    }

    public void setChannelsChecked(int channelsChecked) {
        this.channelsChecked = channelsChecked;
    }

    public int getChannelsMarkedArchived() {
        return channelsMarkedArchived;
    }

    public void setChannelsMarkedArchived(int channelsMarkedArchived) {
        this.channelsMarkedArchived = channelsMarkedArchived;
    }

    public int getPlaylistsChecked() {
        return playlistsChecked;
    }

    public void setPlaylistsChecked(int playlistsChecked) {
        this.playlistsChecked = playlistsChecked;
    }

    public int getPlaylistsMarkedArchived() {
        return playlistsMarkedArchived;
    }

    public void setPlaylistsMarkedArchived(int playlistsMarkedArchived) {
        this.playlistsMarkedArchived = playlistsMarkedArchived;
    }

    public int getVideosChecked() {
        return videosChecked;
    }

    public void setVideosChecked(int videosChecked) {
        this.videosChecked = videosChecked;
    }

    public int getVideosMarkedArchived() {
        return videosMarkedArchived;
    }

    public void setVideosMarkedArchived(int videosMarkedArchived) {
        this.videosMarkedArchived = videosMarkedArchived;
    }

    /**
     * @deprecated Use getVideosMarkedArchived() instead
     */
    public int getVideosMarkedUnavailable() {
        return videosMarkedUnavailable;
    }

    /**
     * @deprecated Use setVideosMarkedArchived() instead
     */
    public void setVideosMarkedUnavailable(int videosMarkedUnavailable) {
        this.videosMarkedUnavailable = videosMarkedUnavailable;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public Map<String, Object> getDetails() {
        return Collections.unmodifiableMap(details);
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }

    public Timestamp getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(Timestamp startedAt) {
        this.startedAt = startedAt;
    }

    public Timestamp getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(Timestamp completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
    }

    public void addDetail(String key, Object value) {
        this.details.put(key, value);
    }

    /**
     * Mark the validation run as completed and calculate duration
     */
    public void complete(String finalStatus) {
        this.status = finalStatus;
        this.completedAt = Timestamp.now();
        if (this.startedAt != null && this.completedAt != null) {
            this.durationMs = (completedAt.toSqlTimestamp().getTime() -
                              startedAt.toSqlTimestamp().getTime());
        }
    }

    /**
     * Increment the channels checked count
     */
    public void incrementChannelsChecked() {
        this.channelsChecked++;
    }

    /**
     * Increment the channels archived count
     */
    public void incrementChannelsArchived() {
        this.channelsMarkedArchived++;
    }

    /**
     * Increment the playlists checked count
     */
    public void incrementPlaylistsChecked() {
        this.playlistsChecked++;
    }

    /**
     * Increment the playlists archived count
     */
    public void incrementPlaylistsArchived() {
        this.playlistsMarkedArchived++;
    }

    /**
     * Increment the videos checked count
     * @deprecated Use incrementVideosChecked() for clarity
     */
    public void incrementChecked() {
        this.videosChecked++;
    }

    /**
     * Increment the videos checked count
     */
    public void incrementVideosChecked() {
        this.videosChecked++;
    }

    /**
     * Increment the videos archived count
     */
    public void incrementVideosArchived() {
        this.videosMarkedArchived++;
        this.videosMarkedUnavailable++; // Keep legacy field in sync
    }

    /**
     * Increment the unavailable count
     * @deprecated Use incrementVideosArchived() instead
     */
    public void incrementUnavailable() {
        this.videosMarkedUnavailable++;
        this.videosMarkedArchived++; // Keep new field in sync
    }

    /**
     * Increment the error count
     */
    public void incrementError() {
        this.errorCount++;
    }

    /**
     * Get total content checked across all types
     */
    public int getTotalChecked() {
        return channelsChecked + playlistsChecked + videosChecked;
    }

    /**
     * Get total content archived across all types
     */
    public int getTotalArchived() {
        return channelsMarkedArchived + playlistsMarkedArchived + videosMarkedArchived;
    }
}

