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

    // Import-specific counters (for TRIGGER_IMPORT runs)

    /**
     * Number of channels successfully imported
     */
    private int channelsImported;

    /**
     * Number of channels skipped (already exist in database)
     */
    private int channelsSkipped;

    /**
     * Number of channels that failed YouTube validation (not found/invalid)
     */
    private int channelsValidationFailed;

    /**
     * Number of channels that failed import (Firestore errors, etc.)
     */
    private int channelsFailed;

    /**
     * Number of playlists successfully imported
     */
    private int playlistsImported;

    /**
     * Number of playlists skipped (already exist in database)
     */
    private int playlistsSkipped;

    /**
     * Number of playlists that failed YouTube validation (not found/invalid)
     */
    private int playlistsValidationFailed;

    /**
     * Number of playlists that failed import (Firestore errors, etc.)
     */
    private int playlistsFailed;

    /**
     * Number of videos successfully imported
     */
    private int videosImported;

    /**
     * Number of videos skipped (already exist in database)
     */
    private int videosSkipped;

    /**
     * Number of videos that failed YouTube validation (not found/invalid)
     */
    private int videosValidationFailed;

    /**
     * Number of videos that failed import (Firestore errors, etc.)
     */
    private int videosFailed;

    /**
     * Total channels to check (for progress calculation)
     */
    private int totalChannelsToCheck;

    /**
     * Total playlists to check (for progress calculation)
     */
    private int totalPlaylistsToCheck;

    /**
     * Total videos to check (for progress calculation)
     */
    private int totalVideosToCheck;

    /**
     * Current phase of validation: CHANNELS, PLAYLISTS, VIDEOS, COMPLETE
     */
    private String currentPhase;

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
        // Initialize import counters
        this.channelsImported = 0;
        this.channelsSkipped = 0;
        this.channelsValidationFailed = 0;
        this.channelsFailed = 0;
        this.playlistsImported = 0;
        this.playlistsSkipped = 0;
        this.playlistsValidationFailed = 0;
        this.playlistsFailed = 0;
        this.videosImported = 0;
        this.videosSkipped = 0;
        this.videosValidationFailed = 0;
        this.videosFailed = 0;
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

    // Import counter getters/setters

    public int getChannelsImported() {
        return channelsImported;
    }

    public void setChannelsImported(int channelsImported) {
        this.channelsImported = channelsImported;
    }

    public int getChannelsSkipped() {
        return channelsSkipped;
    }

    public void setChannelsSkipped(int channelsSkipped) {
        this.channelsSkipped = channelsSkipped;
    }

    public int getChannelsValidationFailed() {
        return channelsValidationFailed;
    }

    public void setChannelsValidationFailed(int channelsValidationFailed) {
        this.channelsValidationFailed = channelsValidationFailed;
    }

    public int getChannelsFailed() {
        return channelsFailed;
    }

    public void setChannelsFailed(int channelsFailed) {
        this.channelsFailed = channelsFailed;
    }

    public int getPlaylistsImported() {
        return playlistsImported;
    }

    public void setPlaylistsImported(int playlistsImported) {
        this.playlistsImported = playlistsImported;
    }

    public int getPlaylistsSkipped() {
        return playlistsSkipped;
    }

    public void setPlaylistsSkipped(int playlistsSkipped) {
        this.playlistsSkipped = playlistsSkipped;
    }

    public int getPlaylistsValidationFailed() {
        return playlistsValidationFailed;
    }

    public void setPlaylistsValidationFailed(int playlistsValidationFailed) {
        this.playlistsValidationFailed = playlistsValidationFailed;
    }

    public int getPlaylistsFailed() {
        return playlistsFailed;
    }

    public void setPlaylistsFailed(int playlistsFailed) {
        this.playlistsFailed = playlistsFailed;
    }

    public int getVideosImported() {
        return videosImported;
    }

    public void setVideosImported(int videosImported) {
        this.videosImported = videosImported;
    }

    public int getVideosSkipped() {
        return videosSkipped;
    }

    public void setVideosSkipped(int videosSkipped) {
        this.videosSkipped = videosSkipped;
    }

    public int getVideosValidationFailed() {
        return videosValidationFailed;
    }

    public void setVideosValidationFailed(int videosValidationFailed) {
        this.videosValidationFailed = videosValidationFailed;
    }

    public int getVideosFailed() {
        return videosFailed;
    }

    public void setVideosFailed(int videosFailed) {
        this.videosFailed = videosFailed;
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

    // Import counter increment methods

    /**
     * Increment the channels imported count
     */
    public void incrementChannelsImported() {
        this.channelsImported++;
    }

    /**
     * Increment the channels skipped count
     */
    public void incrementChannelsSkipped() {
        this.channelsSkipped++;
    }

    /**
     * Increment the channels validation failed count
     */
    public void incrementChannelsValidationFailed() {
        this.channelsValidationFailed++;
    }

    /**
     * Increment the channels failed count
     */
    public void incrementChannelsFailed() {
        this.channelsFailed++;
    }

    /**
     * Increment the playlists imported count
     */
    public void incrementPlaylistsImported() {
        this.playlistsImported++;
    }

    /**
     * Increment the playlists skipped count
     */
    public void incrementPlaylistsSkipped() {
        this.playlistsSkipped++;
    }

    /**
     * Increment the playlists validation failed count
     */
    public void incrementPlaylistsValidationFailed() {
        this.playlistsValidationFailed++;
    }

    /**
     * Increment the playlists failed count
     */
    public void incrementPlaylistsFailed() {
        this.playlistsFailed++;
    }

    /**
     * Increment the videos imported count
     */
    public void incrementVideosImported() {
        this.videosImported++;
    }

    /**
     * Increment the videos skipped count
     */
    public void incrementVideosSkipped() {
        this.videosSkipped++;
    }

    /**
     * Increment the videos validation failed count
     */
    public void incrementVideosValidationFailed() {
        this.videosValidationFailed++;
    }

    /**
     * Increment the videos failed count
     */
    public void incrementVideosFailed() {
        this.videosFailed++;
    }

    // Progress tracking getters/setters

    public int getTotalChannelsToCheck() {
        return totalChannelsToCheck;
    }

    public void setTotalChannelsToCheck(int totalChannelsToCheck) {
        this.totalChannelsToCheck = totalChannelsToCheck;
    }

    public int getTotalPlaylistsToCheck() {
        return totalPlaylistsToCheck;
    }

    public void setTotalPlaylistsToCheck(int totalPlaylistsToCheck) {
        this.totalPlaylistsToCheck = totalPlaylistsToCheck;
    }

    public int getTotalVideosToCheck() {
        return totalVideosToCheck;
    }

    public void setTotalVideosToCheck(int totalVideosToCheck) {
        this.totalVideosToCheck = totalVideosToCheck;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    /**
     * Get the total items to check across all types
     */
    public int getTotalToCheck() {
        return totalChannelsToCheck + totalPlaylistsToCheck + totalVideosToCheck;
    }

    /**
     * Calculate progress percentage (0-100)
     */
    public int getProgressPercent() {
        int total = getTotalToCheck();
        if (total == 0) return 0;
        int checked = getTotalChecked();
        return Math.min(100, (int) ((checked * 100.0) / total));
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

    /**
     * Get total content imported across all types (for TRIGGER_IMPORT runs)
     */
    public int getTotalImported() {
        return channelsImported + playlistsImported + videosImported;
    }

    /**
     * Get total content skipped across all types (for TRIGGER_IMPORT runs)
     */
    public int getTotalSkipped() {
        return channelsSkipped + playlistsSkipped + videosSkipped;
    }

    /**
     * Get total content that failed YouTube validation across all types (for TRIGGER_IMPORT runs)
     */
    public int getTotalValidationFailed() {
        return channelsValidationFailed + playlistsValidationFailed + videosValidationFailed;
    }

    /**
     * Get total content that failed import across all types (for TRIGGER_IMPORT runs)
     */
    public int getTotalFailed() {
        return channelsFailed + playlistsFailed + videosFailed;
    }
}

