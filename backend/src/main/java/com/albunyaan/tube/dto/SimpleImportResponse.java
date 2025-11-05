package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Response for simple format import containing counts and detailed results per item.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SimpleImportResponse {

    private boolean success;
    private String message;
    private SimpleCounts counts;
    private List<SimpleImportItemResult> results;
    private String importedAt;

    public SimpleImportResponse() {
        this.importedAt = Instant.now().toString();
        this.results = new ArrayList<>();
        this.counts = new SimpleCounts();
    }

    public static SimpleImportResponse success(SimpleCounts counts, List<SimpleImportItemResult> results) {
        SimpleImportResponse response = new SimpleImportResponse();
        response.success = true;
        response.message = "Simple format import completed";
        response.counts = counts;
        response.results = results;
        return response;
    }

    public static SimpleImportResponse error(String message) {
        SimpleImportResponse response = new SimpleImportResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    public void addResult(SimpleImportItemResult result) {
        this.results.add(result);

        // Update counts based on result status
        switch (result.getStatus()) {
            case "SUCCESS":
                switch (result.getType()) {
                    case "CHANNEL":
                        counts.incrementChannelsImported();
                        break;
                    case "PLAYLIST":
                        counts.incrementPlaylistsImported();
                        break;
                    case "VIDEO":
                        counts.incrementVideosImported();
                        break;
                }
                break;
            case "SKIPPED":
                switch (result.getType()) {
                    case "CHANNEL":
                        counts.incrementChannelsSkipped();
                        break;
                    case "PLAYLIST":
                        counts.incrementPlaylistsSkipped();
                        break;
                    case "VIDEO":
                        counts.incrementVideosSkipped();
                        break;
                }
                break;
            case "FAILED":
                counts.incrementTotalErrors();
                break;
        }
    }

    // Getters and Setters
    public boolean isSuccess() {
        return success;
    }

    public void setSuccess(boolean success) {
        this.success = success;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public SimpleCounts getCounts() {
        return counts;
    }

    public void setCounts(SimpleCounts counts) {
        this.counts = counts;
    }

    public List<SimpleImportItemResult> getResults() {
        return results;
    }

    public void setResults(List<SimpleImportItemResult> results) {
        this.results = results;
    }

    public String getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(String importedAt) {
        this.importedAt = importedAt;
    }

    /**
     * Counts for simple format import
     */
    public static class SimpleCounts {
        private int channelsImported;
        private int channelsSkipped;
        private int playlistsImported;
        private int playlistsSkipped;
        private int videosImported;
        private int videosSkipped;
        private int totalErrors;
        private int totalProcessed;

        public SimpleCounts() {
        }

        public int getTotalProcessed() {
            return channelsImported + channelsSkipped + playlistsImported + playlistsSkipped +
                   videosImported + videosSkipped + totalErrors;
        }

        // Getters and Setters
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

        public int getTotalErrors() {
            return totalErrors;
        }

        public void setTotalErrors(int totalErrors) {
            this.totalErrors = totalErrors;
        }

        public void setTotalProcessed(int totalProcessed) {
            this.totalProcessed = totalProcessed;
        }

        // Increment methods
        public void incrementChannelsImported() {
            this.channelsImported++;
        }

        public void incrementChannelsSkipped() {
            this.channelsSkipped++;
        }

        public void incrementPlaylistsImported() {
            this.playlistsImported++;
        }

        public void incrementPlaylistsSkipped() {
            this.playlistsSkipped++;
        }

        public void incrementVideosImported() {
            this.videosImported++;
        }

        public void incrementVideosSkipped() {
            this.videosSkipped++;
        }

        public void incrementTotalErrors() {
            this.totalErrors++;
        }
    }
}
