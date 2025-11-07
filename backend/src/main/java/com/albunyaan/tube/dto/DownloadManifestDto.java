package com.albunyaan.tube.dto;

import java.util.ArrayList;
import java.util.List;

public class DownloadManifestDto {
    private String videoId;
    private String title;
    private long expiresAt;
    private List<StreamOption> videoStreams;
    private List<StreamOption> audioStreams;

    public DownloadManifestDto() {
        this.videoStreams = new ArrayList<>();
        this.audioStreams = new ArrayList<>();
    }

    public DownloadManifestDto(String videoId, String title, long expiresAt) {
        this();
        this.videoId = videoId;
        this.title = title;
        this.expiresAt = expiresAt;
    }

    public static class StreamOption {
        private String quality;
        private String url;
        private String format;
        private long fileSize;
        private int bitrate;

        public StreamOption() {}

        public StreamOption(String quality, String url, String format, long fileSize, int bitrate) {
            this.quality = quality;
            this.url = url;
            this.format = format;
            this.fileSize = fileSize;
            this.bitrate = bitrate;
        }

        public String getQuality() { return quality; }
        public void setQuality(String quality) { this.quality = quality; }
        public String getUrl() { return url; }
        public void setUrl(String url) { this.url = url; }
        public String getFormat() { return format; }
        public void setFormat(String format) { this.format = format; }
        public long getFileSize() { return fileSize; }
        public void setFileSize(long fileSize) { this.fileSize = fileSize; }
        public int getBitrate() { return bitrate; }
        public void setBitrate(int bitrate) { this.bitrate = bitrate; }
    }

    public String getVideoId() { return videoId; }
    public void setVideoId(String videoId) { this.videoId = videoId; }
    public String getTitle() { return title; }
    public void setTitle(String title) { this.title = title; }
    public long getExpiresAt() { return expiresAt; }
    public void setExpiresAt(long expiresAt) { this.expiresAt = expiresAt; }
    public List<StreamOption> getVideoStreams() { return videoStreams; }
    public void setVideoStreams(List<StreamOption> videoStreams) { this.videoStreams = videoStreams; }
    public List<StreamOption> getAudioStreams() { return audioStreams; }
    public void setAudioStreams(List<StreamOption> audioStreams) { this.audioStreams = audioStreams; }
}

