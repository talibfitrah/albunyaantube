package com.albunyaan.tube.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Video download manifest with stream options.
 *
 * <p>Contains available stream quality options for download. Streams are categorized as:
 * <ul>
 *   <li>Progressive (≤480p): Single URL with combined audio+video</li>
 *   <li>Split (>480p): Separate audio and video URLs requiring FFmpeg merge</li>
 * </ul>
 */
public class DownloadManifestDto {
    private String videoId;
    private String title;
    /** Manifest expiration time in milliseconds since epoch (UTC). URLs become invalid after this. */
    private long expiresAtMillis;
    private List<StreamOption> videoStreams;
    private List<StreamOption> audioStreams;

    public DownloadManifestDto() {
        this.videoStreams = new ArrayList<>();
        this.audioStreams = new ArrayList<>();
    }

    public DownloadManifestDto(String videoId, String title, long expiresAtMillis) {
        this();
        this.videoId = videoId;
        this.title = title;
        this.expiresAtMillis = expiresAtMillis;
    }

    /**
     * Stream quality option for downloads.
     *
     * For progressive streams (≤480p): progressiveUrl is non-null, requiresMerging is false.
     * For split streams (>480p): videoUrl and audioUrl are non-null, requiresMerging is true.
     */
    public static class StreamOption {
        private String id;
        private String qualityLabel;
        private String mimeType;
        private boolean requiresMerging;
        /** Progressive stream URL (non-null when requiresMerging == false) */
        private String progressiveUrl;
        /** Video-only stream URL (non-null when requiresMerging == true) */
        private String videoUrl;
        /** Audio-only stream URL (non-null when requiresMerging == true) */
        private String audioUrl;
        private long fileSize;
        private int bitrate;

        public StreamOption() {}

        /**
         * Constructor for progressive streams (no merging required).
         */
        public static StreamOption progressive(String id, String qualityLabel, String mimeType,
                String progressiveUrl, long fileSize, int bitrate) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(qualityLabel, "qualityLabel must not be null");
            Objects.requireNonNull(mimeType, "mimeType must not be null");
            Objects.requireNonNull(progressiveUrl, "progressiveUrl must not be null for progressive streams");
            if (fileSize < 0) {
                throw new IllegalArgumentException("fileSize must be >= 0, got: " + fileSize);
            }
            if (bitrate <= 0) {
                throw new IllegalArgumentException("bitrate must be > 0, got: " + bitrate);
            }
            StreamOption option = new StreamOption();
            option.id = id;
            option.qualityLabel = qualityLabel;
            option.mimeType = mimeType;
            option.requiresMerging = false;
            option.progressiveUrl = progressiveUrl;
            option.fileSize = fileSize;
            option.bitrate = bitrate;
            return option;
        }

        /**
         * Constructor for split streams (merging required).
         */
        public static StreamOption split(String id, String qualityLabel, String mimeType,
                String videoUrl, String audioUrl, long fileSize, int bitrate) {
            Objects.requireNonNull(id, "id must not be null");
            Objects.requireNonNull(qualityLabel, "qualityLabel must not be null");
            Objects.requireNonNull(mimeType, "mimeType must not be null");
            Objects.requireNonNull(videoUrl, "videoUrl must not be null for split streams");
            Objects.requireNonNull(audioUrl, "audioUrl must not be null for split streams");
            if (fileSize < 0) {
                throw new IllegalArgumentException("fileSize must be >= 0, got: " + fileSize);
            }
            if (bitrate <= 0) {
                throw new IllegalArgumentException("bitrate must be > 0, got: " + bitrate);
            }
            StreamOption option = new StreamOption();
            option.id = id;
            option.qualityLabel = qualityLabel;
            option.mimeType = mimeType;
            option.requiresMerging = true;
            option.videoUrl = videoUrl;
            option.audioUrl = audioUrl;
            option.fileSize = fileSize;
            option.bitrate = bitrate;
            return option;
        }

        public String getId() { return id; }
        public void setId(String id) { this.id = id; }
        public String getQualityLabel() { return qualityLabel; }
        public void setQualityLabel(String qualityLabel) { this.qualityLabel = qualityLabel; }
        public String getMimeType() { return mimeType; }
        public void setMimeType(String mimeType) { this.mimeType = mimeType; }
        public boolean isRequiresMerging() { return requiresMerging; }
        public void setRequiresMerging(boolean requiresMerging) { this.requiresMerging = requiresMerging; }
        public String getProgressiveUrl() { return progressiveUrl; }
        public void setProgressiveUrl(String progressiveUrl) { this.progressiveUrl = progressiveUrl; }
        public String getVideoUrl() { return videoUrl; }
        public void setVideoUrl(String videoUrl) { this.videoUrl = videoUrl; }
        public String getAudioUrl() { return audioUrl; }
        public void setAudioUrl(String audioUrl) { this.audioUrl = audioUrl; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public int getBitrate() { return bitrate; }
        public void setBitrate(int bitrate) { this.bitrate = bitrate; }
    }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    /** @return Manifest expiration time in milliseconds since epoch (UTC) */
    public long getExpiresAtMillis() { return expiresAtMillis; }
    public void setExpiresAtMillis(long expiresAtMillis) { this.expiresAtMillis = expiresAtMillis; }
    public List<StreamOption> getVideoStreams() { return videoStreams; }
    public void setVideoStreams(List<StreamOption> videoStreams) { this.videoStreams = videoStreams; }
    public List<StreamOption> getAudioStreams() { return audioStreams; }
    public void setAudioStreams(List<StreamOption> audioStreams) { this.audioStreams = audioStreams; }
}

