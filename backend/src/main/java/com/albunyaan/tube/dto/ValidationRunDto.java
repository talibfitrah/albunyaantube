package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.ValidationRun;
import com.google.cloud.Timestamp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.Map;

/**
 * Content Validation: Validation Run DTO
 *
 * Response for validation run results.
 */
public class ValidationRunDto {

    private String id;
    private String status;
    private String triggerType;
    private String triggeredBy;
    private String triggeredByDisplayName;

    // Validation counts (for TRIGGER_MANUAL and TRIGGER_SCHEDULED)
    private int channelsChecked;
    private int channelsArchived;
    private int playlistsChecked;
    private int playlistsArchived;
    private int videosChecked;
    private int videosArchived;
    private int totalChecked;
    private int totalArchived;
    private int errorCount;

    // Import counts (for TRIGGER_IMPORT)
    private int channelsImported;
    private int channelsSkipped;
    private int channelsValidationFailed;
    private int channelsFailed;
    private int playlistsImported;
    private int playlistsSkipped;
    private int playlistsValidationFailed;
    private int playlistsFailed;
    private int videosImported;
    private int videosSkipped;
    private int videosValidationFailed;
    private int videosFailed;

    // Progress tracking
    private int totalChannelsToCheck;
    private int totalPlaylistsToCheck;
    private int totalVideosToCheck;
    private int totalToCheck;
    private int progressPercent;
    private String currentPhase;

    // Timestamps (ISO 8601 strings for JSON serialization)
    private String startedAt;
    private String completedAt;
    private Long durationMs;

    // Details map (includes reasonCounts, failedItemIds, etc.)
    private Map<String, Object> details;

    public ValidationRunDto() {
    }

    /**
     * Create from ValidationRun model.
     * Handles backward compatibility with legacy video validation runs that
     * only set videosMarkedUnavailable instead of videosMarkedArchived.
     */
    public static ValidationRunDto fromModel(ValidationRun run) {
        ValidationRunDto dto = new ValidationRunDto();
        dto.setId(run.getId());
        dto.setStatus(run.getStatus());
        dto.setTriggerType(run.getTriggerType());
        dto.setTriggeredBy(run.getTriggeredBy());
        dto.setTriggeredByDisplayName(run.getTriggeredByDisplayName());

        dto.setChannelsChecked(run.getChannelsChecked());
        dto.setChannelsArchived(run.getChannelsMarkedArchived());
        dto.setPlaylistsChecked(run.getPlaylistsChecked());
        dto.setPlaylistsArchived(run.getPlaylistsMarkedArchived());
        dto.setVideosChecked(run.getVideosChecked());

        // Backward compatibility: use max of new field (videosMarkedArchived)
        // and legacy field (videosMarkedUnavailable) for old validation runs
        int videosArchived = Math.max(run.getVideosMarkedArchived(), run.getVideosMarkedUnavailable());
        dto.setVideosArchived(videosArchived);

        dto.setTotalChecked(run.getTotalChecked());
        // Recalculate total archived with backward-compatible videos count
        int totalArchived = run.getChannelsMarkedArchived() + run.getPlaylistsMarkedArchived() + videosArchived;
        dto.setTotalArchived(totalArchived);
        dto.setErrorCount(run.getErrorCount());

        // Import counts (for TRIGGER_IMPORT runs)
        dto.setChannelsImported(run.getChannelsImported());
        dto.setChannelsSkipped(run.getChannelsSkipped());
        dto.setChannelsValidationFailed(run.getChannelsValidationFailed());
        dto.setChannelsFailed(run.getChannelsFailed());
        dto.setPlaylistsImported(run.getPlaylistsImported());
        dto.setPlaylistsSkipped(run.getPlaylistsSkipped());
        dto.setPlaylistsValidationFailed(run.getPlaylistsValidationFailed());
        dto.setPlaylistsFailed(run.getPlaylistsFailed());
        dto.setVideosImported(run.getVideosImported());
        dto.setVideosSkipped(run.getVideosSkipped());
        dto.setVideosValidationFailed(run.getVideosValidationFailed());
        dto.setVideosFailed(run.getVideosFailed());

        // Progress tracking
        dto.setTotalChannelsToCheck(run.getTotalChannelsToCheck());
        dto.setTotalPlaylistsToCheck(run.getTotalPlaylistsToCheck());
        dto.setTotalVideosToCheck(run.getTotalVideosToCheck());
        dto.setTotalToCheck(run.getTotalToCheck());
        dto.setProgressPercent(run.getProgressPercent());
        dto.setCurrentPhase(run.getCurrentPhase());

        dto.setStartedAt(formatTimestamp(run.getStartedAt()));
        dto.setCompletedAt(formatTimestamp(run.getCompletedAt()));
        dto.setDurationMs(run.getDurationMs());

        // Include details map (reasonCounts, failedItemIds, etc.)
        dto.setDetails(run.getDetails());

        return dto;
    }

    /**
     * Convert Google Cloud Timestamp to ISO 8601 string for JSON serialization
     */
    private static String formatTimestamp(Timestamp timestamp) {
        if (timestamp == null) {
            return null;
        }
        Instant instant = Instant.ofEpochSecond(timestamp.getSeconds(), timestamp.getNanos());
        return DateTimeFormatter.ISO_INSTANT.format(instant);
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
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

    public int getChannelsChecked() {
        return channelsChecked;
    }

    public void setChannelsChecked(int channelsChecked) {
        this.channelsChecked = channelsChecked;
    }

    public int getChannelsArchived() {
        return channelsArchived;
    }

    public void setChannelsArchived(int channelsArchived) {
        this.channelsArchived = channelsArchived;
    }

    public int getPlaylistsChecked() {
        return playlistsChecked;
    }

    public void setPlaylistsChecked(int playlistsChecked) {
        this.playlistsChecked = playlistsChecked;
    }

    public int getPlaylistsArchived() {
        return playlistsArchived;
    }

    public void setPlaylistsArchived(int playlistsArchived) {
        this.playlistsArchived = playlistsArchived;
    }

    public int getVideosChecked() {
        return videosChecked;
    }

    public void setVideosChecked(int videosChecked) {
        this.videosChecked = videosChecked;
    }

    public int getVideosArchived() {
        return videosArchived;
    }

    public void setVideosArchived(int videosArchived) {
        this.videosArchived = videosArchived;
    }

    public int getTotalChecked() {
        return totalChecked;
    }

    public void setTotalChecked(int totalChecked) {
        this.totalChecked = totalChecked;
    }

    public int getTotalArchived() {
        return totalArchived;
    }

    public void setTotalArchived(int totalArchived) {
        this.totalArchived = totalArchived;
    }

    public int getErrorCount() {
        return errorCount;
    }

    public void setErrorCount(int errorCount) {
        this.errorCount = errorCount;
    }

    public String getStartedAt() {
        return startedAt;
    }

    public void setStartedAt(String startedAt) {
        this.startedAt = startedAt;
    }

    public String getCompletedAt() {
        return completedAt;
    }

    public void setCompletedAt(String completedAt) {
        this.completedAt = completedAt;
    }

    public Long getDurationMs() {
        return durationMs;
    }

    public void setDurationMs(Long durationMs) {
        this.durationMs = durationMs;
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

    public int getTotalToCheck() {
        return totalToCheck;
    }

    public void setTotalToCheck(int totalToCheck) {
        this.totalToCheck = totalToCheck;
    }

    public int getProgressPercent() {
        return progressPercent;
    }

    public void setProgressPercent(int progressPercent) {
        this.progressPercent = progressPercent;
    }

    public String getCurrentPhase() {
        return currentPhase;
    }

    public void setCurrentPhase(String currentPhase) {
        this.currentPhase = currentPhase;
    }

    // Import counts getters/setters

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
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        this.details = details;
    }
}
