package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

/**
 * Import response containing summary of imported items and any errors.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportResponse {

    private boolean success;
    private String message;
    private ImportCounts counts;
    private List<ImportError> errors;
    private String importedAt;

    public ImportResponse() {
        this.importedAt = Instant.now().toString();
        this.errors = new ArrayList<>();
    }

    public static ImportResponse success(ImportCounts counts) {
        ImportResponse response = new ImportResponse();
        response.success = true;
        response.message = "Import completed successfully";
        response.counts = counts;
        return response;
    }

    public static ImportResponse error(String message) {
        ImportResponse response = new ImportResponse();
        response.success = false;
        response.message = message;
        return response;
    }

    public void addError(String type, String id, String error) {
        this.errors.add(new ImportError(type, id, error));
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

    public ImportCounts getCounts() {
        return counts;
    }

    public void setCounts(ImportCounts counts) {
        this.counts = counts;
    }

    public List<ImportError> getErrors() {
        return errors;
    }

    public void setErrors(List<ImportError> errors) {
        this.errors = errors;
    }

    public String getImportedAt() {
        return importedAt;
    }

    public void setImportedAt(String importedAt) {
        this.importedAt = importedAt;
    }

    /**
     * Counts of imported items
     */
    public static class ImportCounts {
        private int categoriesImported;
        private int categoriesSkipped;
        private int channelsImported;
        private int channelsSkipped;
        private int playlistsImported;
        private int playlistsSkipped;
        private int videosImported;
        private int videosSkipped;
        private int totalErrors;

        public ImportCounts() {
        }

        // Getters and Setters
        public int getCategoriesImported() {
            return categoriesImported;
        }

        public void setCategoriesImported(int categoriesImported) {
            this.categoriesImported = categoriesImported;
        }

        public int getCategoriesSkipped() {
            return categoriesSkipped;
        }

        public void setCategoriesSkipped(int categoriesSkipped) {
            this.categoriesSkipped = categoriesSkipped;
        }

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

        public void incrementCategoriesImported() {
            this.categoriesImported++;
        }

        public void incrementCategoriesSkipped() {
            this.categoriesSkipped++;
        }

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

    /**
     * Individual import error
     */
    public static class ImportError {
        private String type; // CATEGORY, CHANNEL, PLAYLIST, VIDEO
        private String id;
        private String error;

        public ImportError() {
        }

        public ImportError(String type, String id, String error) {
            this.type = type;
            this.id = id;
            this.error = error;
        }

        // Getters and Setters
        public String getType() {
            return type;
        }

        public void setType(String type) {
            this.type = type;
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String getError() {
            return error;
        }

        public void setError(String error) {
            this.error = error;
        }
    }
}
