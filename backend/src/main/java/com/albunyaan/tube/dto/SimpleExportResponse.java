package com.albunyaan.tube.dto;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Simple export response in the format:
 * [
 *   {"UCxxx": "Channel Name|Category1,Category2", ...},  // Channels
 *   {"PLxxx": "Playlist Name|Category1,Category2", ...}, // Playlists
 *   {"xxx": "Video Title|Category1,Category2", ...}      // Videos
 * ]
 */
public class SimpleExportResponse {

    private Map<String, String> channels;
    private Map<String, String> playlists;
    private Map<String, String> videos;

    public SimpleExportResponse() {
        this.channels = new LinkedHashMap<>();
        this.playlists = new LinkedHashMap<>();
        this.videos = new LinkedHashMap<>();
    }

    /**
     * Add a channel to the export.
     * @param youtubeId YouTube channel ID (UCxxx)
     * @param title Channel name
     * @param categories Comma-separated category names
     */
    public void addChannel(String youtubeId, String title, String categories) {
        this.channels.put(youtubeId, formatValue(title, categories));
    }

    /**
     * Add a playlist to the export.
     * @param youtubeId YouTube playlist ID (PLxxx)
     * @param title Playlist title
     * @param categories Comma-separated category names
     */
    public void addPlaylist(String youtubeId, String title, String categories) {
        this.playlists.put(youtubeId, formatValue(title, categories));
    }

    /**
     * Add a video to the export.
     * @param youtubeId YouTube video ID
     * @param title Video title
     * @param categories Comma-separated category names
     */
    public void addVideo(String youtubeId, String title, String categories) {
        this.videos.put(youtubeId, formatValue(title, categories));
    }

    /**
     * Format value as "Title|Categories"
     */
    private String formatValue(String title, String categories) {
        if (categories == null || categories.isEmpty()) {
            return title + "|";
        }
        return title + "|" + categories;
    }

    /**
     * Convert to JSON array format: [{channels}, {playlists}, {videos}]
     */
    public String toJson() throws JsonProcessingException {
        ObjectMapper mapper = new ObjectMapper();
        mapper.enable(SerializationFeature.INDENT_OUTPUT);

        List<Map<String, String>> exportArray = new ArrayList<>();
        exportArray.add(channels);
        exportArray.add(playlists);
        exportArray.add(videos);

        return mapper.writeValueAsString(exportArray);
    }

    /**
     * Get as array structure for serialization
     */
    public List<Map<String, String>> toArray() {
        List<Map<String, String>> exportArray = new ArrayList<>();
        exportArray.add(channels);
        exportArray.add(playlists);
        exportArray.add(videos);
        return exportArray;
    }

    // Getters
    public Map<String, String> getChannels() {
        return channels;
    }

    public Map<String, String> getPlaylists() {
        return playlists;
    }

    public Map<String, String> getVideos() {
        return videos;
    }

    // Setters (for Jackson deserialization if needed)
    public void setChannels(Map<String, String> channels) {
        this.channels = channels;
    }

    public void setPlaylists(Map<String, String> playlists) {
        this.playlists = playlists;
    }

    public void setVideos(Map<String, String> videos) {
        this.videos = videos;
    }
}

