package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.ValidationRun;
import com.google.cloud.Timestamp;

import java.time.Instant;
import java.time.format.DateTimeFormatter;

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

    // Counts
    private int channelsChecked;
    private int channelsArchived;
    private int playlistsChecked;
    private int playlistsArchived;
    private int videosChecked;
    private int videosArchived;
    private int totalChecked;
    private int totalArchived;
    private int errorCount;

    // Timestamps (ISO 8601 strings for JSON serialization)
    private String startedAt;
    private String completedAt;
    private Long durationMs;

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

        dto.setStartedAt(formatTimestamp(run.getStartedAt()));
        dto.setCompletedAt(formatTimestamp(run.getCompletedAt()));
        dto.setDurationMs(run.getDurationMs());

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
}
