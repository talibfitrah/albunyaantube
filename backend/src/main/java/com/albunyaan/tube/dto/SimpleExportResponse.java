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
 *   {"UCxxx": "Channel Name|Category1,Category2|keyword1,keyword2", ...},  // Channels
 *   {"PLxxx": "Playlist Name|Category1,Category2|keyword1,keyword2", ...}, // Playlists
 *   {"xxx": "Video Title|Category1,Category2|keyword1,keyword2", ...}      // Videos
 * ]
 *
 * The keywords section (after the second pipe) is optional for backward compatibility.
 * Format: "Title|Categories|Keywords" or "Title|Categories" (legacy)
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
        this.channels.put(youtubeId, formatValue(title, categories, null));
    }

    /**
     * Add a channel to the export with keywords.
     * @param youtubeId YouTube channel ID (UCxxx)
     * @param title Channel name
     * @param categories Comma-separated category names
     * @param keywords Comma-separated keywords (optional)
     */
    public void addChannel(String youtubeId, String title, String categories, String keywords) {
        this.channels.put(youtubeId, formatValue(title, categories, keywords));
    }

    /**
     * Add a playlist to the export.
     * @param youtubeId YouTube playlist ID (PLxxx)
     * @param title Playlist title
     * @param categories Comma-separated category names
     */
    public void addPlaylist(String youtubeId, String title, String categories) {
        this.playlists.put(youtubeId, formatValue(title, categories, null));
    }

    /**
     * Add a playlist to the export with keywords.
     * @param youtubeId YouTube playlist ID (PLxxx)
     * @param title Playlist title
     * @param categories Comma-separated category names
     * @param keywords Comma-separated keywords (optional)
     */
    public void addPlaylist(String youtubeId, String title, String categories, String keywords) {
        this.playlists.put(youtubeId, formatValue(title, categories, keywords));
    }

    /**
     * Add a video to the export.
     * @param youtubeId YouTube video ID
     * @param title Video title
     * @param categories Comma-separated category names
     */
    public void addVideo(String youtubeId, String title, String categories) {
        this.videos.put(youtubeId, formatValue(title, categories, null));
    }

    /**
     * Add a video to the export with keywords.
     * @param youtubeId YouTube video ID
     * @param title Video title
     * @param categories Comma-separated category names
     * @param keywords Comma-separated keywords (optional)
     */
    public void addVideo(String youtubeId, String title, String categories, String keywords) {
        this.videos.put(youtubeId, formatValue(title, categories, keywords));
    }

    /**
     * Format value as "Title|Categories|Keywords"
     * Keywords are optional - if empty/null, format is "Title|Categories" for backward compatibility
     *
     * Input validation:
     * - Title must not be null (required field)
     * - Pipe characters in inputs are replaced with hyphen to preserve format integrity
     */
    private String formatValue(String title, String categories, String keywords) {
        if (title == null) {
            throw new IllegalArgumentException("Title cannot be null");
        }

        // Sanitize inputs: replace pipe characters to preserve format integrity
        String sanitizedTitle = title.replace("|", "-");
        String sanitizedCategories = (categories != null) ? categories.replace("|", "-") : "";
        String sanitizedKeywords = (keywords != null) ? keywords.replace("|", "-") : null;

        // Build output: "Title|Categories" or "Title|Categories|Keywords"
        // Categories is always present (may be empty string)
        // Keywords section only added if non-null and non-empty
        StringBuilder sb = new StringBuilder();
        sb.append(sanitizedTitle);
        sb.append("|");
        sb.append(sanitizedCategories);

        if (sanitizedKeywords != null && !sanitizedKeywords.isEmpty()) {
            sb.append("|").append(sanitizedKeywords);
        }
        return sb.toString();
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

