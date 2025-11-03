package com.albunyaan.tube.dto;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * Import request containing content data to import.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ImportRequest {

    private ExportResponse.ExportMetadata metadata;
    private List<Category> categories;
    private List<Channel> channels;
    private List<Playlist> playlists;
    private List<Video> videos;

    @JsonIgnore
    private String mergeStrategy = "SKIP"; // SKIP, OVERWRITE, MERGE

    @JsonIgnore
    private String importedBy;

    public ImportRequest() {
    }

    public static ImportRequest fromJson(String json) throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        return mapper.readValue(json, ImportRequest.class);
    }

    // Getters and Setters
    public ExportResponse.ExportMetadata getMetadata() {
        return metadata;
    }

    public void setMetadata(ExportResponse.ExportMetadata metadata) {
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

    public String getMergeStrategy() {
        return mergeStrategy;
    }

    public void setMergeStrategy(String mergeStrategy) {
        this.mergeStrategy = mergeStrategy;
    }

    public String getImportedBy() {
        return importedBy;
    }

    public void setImportedBy(String importedBy) {
        this.importedBy = importedBy;
    }
}
