package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.EnrichedSearchResult;
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
import java.util.Map;
import java.util.stream.Collectors;

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
     * Search for channels by query with full statistics
     */
    public List<EnrichedSearchResult> searchChannelsEnriched(String query) throws IOException {
        return searchEnriched(query, "channel");
    }

    /**
     * Search for playlists by query with full details
     */
    public List<EnrichedSearchResult> searchPlaylistsEnriched(String query) throws IOException {
        return searchEnriched(query, "playlist");
    }

    /**
     * Search for videos by query with full statistics
     */
    public List<EnrichedSearchResult> searchVideosEnriched(String query) throws IOException {
        return searchEnriched(query, "video");
    }

    /**
     * Enriched search method that fetches full statistics
     */
    private List<EnrichedSearchResult> searchEnriched(String query, String type) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("YouTube API key not configured");
            return Collections.emptyList();
        }

        try {
            // First, do the search
            YouTube.Search.List search = youtube.search().list(List.of("snippet"));
            search.setKey(apiKey);
            search.setQ(query);
            search.setType(List.of(type));
            search.setMaxResults(MAX_RESULTS);

            SearchListResponse response = search.execute();
            List<SearchResult> results = response.getItems() != null ? response.getItems() : Collections.emptyList();

            // Convert to enriched results
            List<EnrichedSearchResult> enrichedResults = results.stream()
                    .map(r -> EnrichedSearchResult.fromSearchResult(r, type))
                    .collect(Collectors.toList());

            // Enrich with additional data based on type
            if ("channel".equals(type)) {
                enrichChannelData(enrichedResults);
            } else if ("playlist".equals(type)) {
                enrichPlaylistData(enrichedResults);
            } else if ("video".equals(type)) {
                enrichVideoData(enrichedResults);
            }

            return enrichedResults;

        } catch (IOException e) {
            logger.error("YouTube search failed for query '{}': {}", query, e.getMessage());
            throw e;
        }
    }

    /**
     * Generic search method
     * For videos and channels, we need to make additional API calls to get statistics
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
            search.setFields("items(id,snippet(title,description,thumbnails,channelId,channelTitle,publishedAt))");

            SearchListResponse response = search.execute();
            List<SearchResult> results = response.getItems() != null ? response.getItems() : Collections.emptyList();

            // Enrich results with statistics based on type
            if ("channel".equals(type)) {
                enrichChannelResults(results);
            } else if ("playlist".equals(type)) {
                enrichPlaylistResults(results);
            } else if ("video".equals(type)) {
                enrichVideoResults(results);
            }

            return results;

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

    /**
     * Enrich channel data with statistics
     */
    private void enrichChannelData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> channelIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .collect(Collectors.toList());

        YouTube.Channels.List request = youtube.channels().list(List.of("statistics"));
        request.setKey(apiKey);
        request.setId(channelIds);

        ChannelListResponse response = request.execute();
        List<Channel> channels = response.getItems();

        if (channels != null) {
            Map<String, Channel> channelMap = channels.stream()
                    .collect(Collectors.toMap(Channel::getId, ch -> ch));

            for (EnrichedSearchResult result : results) {
                Channel channel = channelMap.get(result.getId());
                if (channel != null && channel.getStatistics() != null) {
                    result.setSubscriberCount(channel.getStatistics().getSubscriberCount() != null ?
                            channel.getStatistics().getSubscriberCount().longValue() : 0L);
                    result.setVideoCount(channel.getStatistics().getVideoCount() != null ?
                            channel.getStatistics().getVideoCount().longValue() : 0L);
                }
            }
        }
    }

    /**
     * Enrich playlist data with content details
     */
    private void enrichPlaylistData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> playlistIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .collect(Collectors.toList());

        YouTube.Playlists.List request = youtube.playlists().list(List.of("contentDetails"));
        request.setKey(apiKey);
        request.setId(playlistIds);

        PlaylistListResponse response = request.execute();
        List<Playlist> playlists = response.getItems();

        if (playlists != null) {
            Map<String, Playlist> playlistMap = playlists.stream()
                    .collect(Collectors.toMap(Playlist::getId, pl -> pl));

            for (EnrichedSearchResult result : results) {
                Playlist playlist = playlistMap.get(result.getId());
                if (playlist != null && playlist.getContentDetails() != null) {
                    result.setItemCount(playlist.getContentDetails().getItemCount() != null ?
                            playlist.getContentDetails().getItemCount().longValue() : 0L);
                }
            }
        }
    }

    /**
     * Enrich video data with statistics and content details
     */
    private void enrichVideoData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> videoIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .collect(Collectors.toList());

        YouTube.Videos.List request = youtube.videos().list(List.of("statistics", "contentDetails"));
        request.setKey(apiKey);
        request.setId(videoIds);

        VideoListResponse response = request.execute();
        List<Video> videos = response.getItems();

        if (videos != null) {
            Map<String, Video> videoMap = videos.stream()
                    .collect(Collectors.toMap(Video::getId, v -> v));

            for (EnrichedSearchResult result : results) {
                Video video = videoMap.get(result.getId());
                if (video != null) {
                    if (video.getStatistics() != null) {
                        result.setViewCount(video.getStatistics().getViewCount() != null ?
                                video.getStatistics().getViewCount().longValue() : 0L);
                    }
                    if (video.getContentDetails() != null) {
                        result.setDuration(video.getContentDetails().getDuration());
                    }
                }
            }
        }
    }

    /**
     * Enrich channel search results with statistics (old method for backward compatibility)
     */
    private void enrichChannelResults(List<SearchResult> results) throws IOException {
        // Keep for backward compatibility
    }

    /**
     * Enrich playlist search results with content details (old method for backward compatibility)
     */
    private void enrichPlaylistResults(List<SearchResult> results) throws IOException {
        // Keep for backward compatibility
    }

    /**
     * Enrich video search results with statistics and content details (old method for backward compatibility)
     */
    private void enrichVideoResults(List<SearchResult> results) throws IOException {
        // Keep for backward compatibility
    }
}
