package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.time.Instant;
import java.util.List;

/**
 * Export response containing all content data in JSON format.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ExportResponse {

    private ExportMetadata metadata;
    private List<Category> categories;
    private List<Channel> channels;
    private List<Playlist> playlists;
    private List<Video> videos;

    public ExportResponse() {
    }

    public ExportResponse(
            ExportMetadata metadata,
            List<Category> categories,
            List<Channel> channels,
            List<Playlist> playlists,
            List<Video> videos
    ) {
        this.metadata = metadata;
        this.categories = categories;
        this.channels = channels;
        this.playlists = playlists;
        this.videos = videos;
    }

    public String toJson() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);
        return mapper.writeValueAsString(this);
    }

    // Getters and Setters
    public ExportMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ExportMetadata metadata) {
        this.metadata = metadata;
    }

    public List<Category> getCategories() {
        return categories;
    }

    public void setCategories(List<Category> categories) {
        this.categories = categories;
    }

    public List<Channel> getChannels() {
        return channels;
    }

    public void setChannels(List<Channel> channels) {
        this.channels = channels;
    }

    public List<Playlist> getPlaylists() {
        return playlists;
    }

    public void setPlaylists(List<Playlist> playlists) {
        this.playlists = playlists;
    }

    public List<Video> getVideos() {
        return videos;
    }

    public void setVideos(List<Video> videos) {
        this.videos = videos;
    }

    /**
     * Metadata about the export
     */
    public static class ExportMetadata {
        private String version = "1.0";
        private String exportedAt;
        private String exportedBy;
        private int categoriesCount;
        private int channelsCount;
        private int playlistsCount;
        private int videosCount;

        public ExportMetadata() {
            this.exportedAt = Instant.now().toString();
        }

        public ExportMetadata(
                String exportedBy,
                int categoriesCount,
                int channelsCount,
                int playlistsCount,
                int videosCount
        ) {
            this();
            this.exportedBy = exportedBy;
            this.categoriesCount = categoriesCount;
            this.channelsCount = channelsCount;
            this.playlistsCount = playlistsCount;
            this.videosCount = videosCount;
        }

        // Getters and Setters
        public String getVersion() {
            return version;
        }

        public void setVersion(String version) {
            this.version = version;
        }

        public String getExportedAt() {
            return exportedAt;
        }

        public void setExportedAt(String exportedAt) {
            this.exportedAt = exportedAt;
        }

        public String getExportedBy() {
            return exportedBy;
        }

        public void setExportedBy(String exportedBy) {
            this.exportedBy = exportedBy;
        }

        public int getCategoriesCount() {
            return categoriesCount;
        }

        public void setCategoriesCount(int categoriesCount) {
            this.categoriesCount = categoriesCount;
        }

        public int getChannelsCount() {
            return channelsCount;
        }

        public void setChannelsCount(int channelsCount) {
            this.channelsCount = channelsCount;
        }

        public int getPlaylistsCount() {
            return playlistsCount;
        }

        public void setPlaylistsCount(int playlistsCount) {
            this.playlistsCount = playlistsCount;
        }

        public int getVideosCount() {
            return videosCount;
        }

        public void setVideosCount(int videosCount) {
            this.videosCount = videosCount;
        }
    }
}
