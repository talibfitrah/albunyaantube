package com.albunyaan.tube.service;

import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * FIREBASE-MIGRATE-04: YouTube Data API Integration
 *
 * Provides search and metadata fetching from YouTube for admin interface.
 * Used for content preview and selection before adding to master list.
 */
@Service
public class YouTubeService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeService.class);
    private static final long MAX_RESULTS = 20L;

    private final YouTube youtube;
    private final String apiKey;

    public YouTubeService(
            @Value("${app.youtube.api-key}") String apiKey,
            @Value("${app.youtube.application-name}") String applicationName
    ) throws GeneralSecurityException, IOException {
        this.apiKey = apiKey;
        this.youtube = new YouTube.Builder(
                GoogleNetHttpTransport.newTrustedTransport(),
                JacksonFactory.getDefaultInstance(),
                null
        ).setApplicationName(applicationName).build();
    }

    /**
     * Search for channels by query
     */
    public List<SearchResult> searchChannels(String query) throws IOException {
        return search(query, "channel");
    }

    /**
     * Search for playlists by query
     */
    public List<SearchResult> searchPlaylists(String query) throws IOException {
        return search(query, "playlist");
    }

    /**
     * Search for videos by query
     */
    public List<SearchResult> searchVideos(String query) throws IOException {
        return search(query, "video");
    }

    /**
     * Generic search method
     */
    private List<SearchResult> search(String query, String type) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("YouTube API key not configured");
            return Collections.emptyList();
        }

        try {
            YouTube.Search.List search = youtube.search().list(List.of("snippet"));
            search.setKey(apiKey);
            search.setQ(query);
            search.setType(List.of(type));
            search.setMaxResults(MAX_RESULTS);
            search.setFields("items(id,snippet(title,description,thumbnails,channelId,channelTitle))");

            SearchListResponse response = search.execute();
            return response.getItems() != null ? response.getItems() : Collections.emptyList();

        } catch (IOException e) {
            logger.error("YouTube search failed for query '{}': {}", query, e.getMessage());
            throw e;
        }
    }

    /**
     * Get channel details by channel ID
     */
    public Channel getChannelDetails(String channelId) throws IOException {
        YouTube.Channels.List request = youtube.channels().list(List.of("snippet", "statistics", "contentDetails"));
        request.setKey(apiKey);
        request.setId(List.of(channelId));

        ChannelListResponse response = request.execute();
        List<Channel> channels = response.getItems();

        return channels != null && !channels.isEmpty() ? channels.get(0) : null;
    }

    /**
     * Get videos from a channel
     */
    public List<SearchResult> getChannelVideos(String channelId, String pageToken) throws IOException {
        YouTube.Search.List request = youtube.search().list(List.of("snippet"));
        request.setKey(apiKey);
        request.setChannelId(channelId);
        request.setType(List.of("video"));
        request.setOrder("date");
        request.setMaxResults(MAX_RESULTS);

        if (pageToken != null && !pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }

        SearchListResponse response = request.execute();
        return response.getItems() != null ? response.getItems() : Collections.emptyList();
    }

    /**
     * Get playlists from a channel
     */
    public List<Playlist> getChannelPlaylists(String channelId, String pageToken) throws IOException {
        YouTube.Playlists.List request = youtube.playlists().list(List.of("snippet", "contentDetails"));
        request.setKey(apiKey);
        request.setChannelId(channelId);
        request.setMaxResults(MAX_RESULTS);

        if (pageToken != null && !pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }

        PlaylistListResponse response = request.execute();
        return response.getItems() != null ? response.getItems() : Collections.emptyList();
    }

    /**
     * Get playlist details and videos
     */
    public Playlist getPlaylistDetails(String playlistId) throws IOException {
        YouTube.Playlists.List request = youtube.playlists().list(List.of("snippet", "contentDetails"));
        request.setKey(apiKey);
        request.setId(List.of(playlistId));

        PlaylistListResponse response = request.execute();
        List<Playlist> playlists = response.getItems();

        return playlists != null && !playlists.isEmpty() ? playlists.get(0) : null;
    }

    /**
     * Get videos in a playlist
     */
    public List<PlaylistItem> getPlaylistVideos(String playlistId, String pageToken) throws IOException {
        YouTube.PlaylistItems.List request = youtube.playlistItems().list(List.of("snippet", "contentDetails"));
        request.setKey(apiKey);
        request.setPlaylistId(playlistId);
        request.setMaxResults(MAX_RESULTS);

        if (pageToken != null && !pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }

        PlaylistItemListResponse response = request.execute();
        return response.getItems() != null ? response.getItems() : Collections.emptyList();
    }

    /**
     * Get video details
     */
    public Video getVideoDetails(String videoId) throws IOException {
        YouTube.Videos.List request = youtube.videos().list(List.of("snippet", "statistics", "contentDetails"));
        request.setKey(apiKey);
        request.setId(List.of(videoId));

        VideoListResponse response = request.execute();
        List<Video> videos = response.getItems();

        return videos != null && !videos.isEmpty() ? videos.get(0) : null;
    }
}
