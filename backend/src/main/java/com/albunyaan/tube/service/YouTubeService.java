package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.SearchPageResponse;
import com.google.api.client.googleapis.javanet.GoogleNetHttpTransport;
import com.google.api.client.json.jackson2.JacksonFactory;
import com.google.api.services.youtube.YouTube;
import com.google.api.services.youtube.model.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.security.GeneralSecurityException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
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
    private static final int BATCH_SIZE = 50; // YouTube API allows up to 50 IDs per batch request
    private static final ExecutorService executorService = Executors.newFixedThreadPool(3); // Thread pool for parallel requests

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
     * Unified search for all content types with pagination - single API call, mixed results like YouTube
     * MUCH faster than 3 separate calls! Supports infinite scroll with nextPageToken
     */
    public SearchPageResponse searchAllEnrichedPaged(String query, String pageToken) throws IOException {
        if (apiKey == null || apiKey.isEmpty()) {
            logger.warn("YouTube API key not configured");
            return new SearchPageResponse(Collections.emptyList(), null, 0);
        }

        // Single API call for all content types
        YouTube.Search.List request = youtube.search().list(List.of("id", "snippet"));
        request.setKey(apiKey);
        request.setQ(query);
        request.setType(List.of("video", "channel", "playlist"));
        request.setMaxResults(50L);
        request.setFields("items(id,snippet),nextPageToken,pageInfo/totalResults");

        if (pageToken != null && !pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }

        SearchListResponse response = request.execute();
        List<SearchResult> items = response.getItems();

        if (items == null || items.isEmpty()) {
            return new SearchPageResponse(Collections.emptyList(), null, 0);
        }

        // Separate by type for batch enrichment (but preserve order)
        List<EnrichedSearchResult> channels = new ArrayList<>();
        List<EnrichedSearchResult> playlists = new ArrayList<>();
        List<EnrichedSearchResult> videos = new ArrayList<>();
        List<EnrichedSearchResult> allResults = new ArrayList<>();

        for (SearchResult item : items) {
            EnrichedSearchResult result;
            if (item.getId().getChannelId() != null) {
                result = EnrichedSearchResult.fromSearchResult(item, "channel");
                channels.add(result);
            } else if (item.getId().getPlaylistId() != null) {
                result = EnrichedSearchResult.fromSearchResult(item, "playlist");
                playlists.add(result);
            } else if (item.getId().getVideoId() != null) {
                result = EnrichedSearchResult.fromSearchResult(item, "video");
                videos.add(result);
            } else {
                continue;
            }
            allResults.add(result);
        }

        // Enrich with metadata
        if (!channels.isEmpty()) enrichChannelData(channels);
        if (!playlists.isEmpty()) enrichPlaylistData(playlists);
        if (!videos.isEmpty()) enrichVideoData(videos);

        // Return with pagination info
        return new SearchPageResponse(
            allResults,
            response.getNextPageToken(),
            response.getPageInfo() != null ? response.getPageInfo().getTotalResults() : 0
        );
    }

    /**
     * Unified search for all content types - single API call, mixed results like YouTube
     * MUCH faster than 3 separate calls!
     * @deprecated Use searchAllEnrichedPaged for pagination support
     */
    @Cacheable(value = "youtubeUnifiedSearch", key = "#query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchAllEnriched(String query) throws IOException {
        SearchPageResponse response = searchAllEnrichedPaged(query, null);
        return response.getItems();
    }

    /**
     * Search for channels by query with full statistics (with caching)
     */
    @Cacheable(value = "youtubeChannelSearch", key = "#query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchChannelsEnriched(String query) throws IOException {
        return searchEnriched(query, "channel");
    }

    /**
     * Search for playlists by query with full details (with caching)
     */
    @Cacheable(value = "youtubePlaylistSearch", key = "#query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchPlaylistsEnriched(String query) throws IOException {
        return searchEnriched(query, "playlist");
    }

    /**
     * Search for videos by query with full statistics (with caching)
     */
    @Cacheable(value = "youtubeVideoSearch", key = "#query", unless = "#result == null || #result.isEmpty()")
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
        return getChannelVideos(channelId, pageToken, null);
    }

    /**
     * Get videos from a channel with optional search query
     * @param channelId The YouTube channel ID
     * @param pageToken Pagination token (can be null)
     * @param searchQuery Optional search query to filter videos (can be null)
     */
    public List<SearchResult> getChannelVideos(String channelId, String pageToken, String searchQuery) throws IOException {
        YouTube.Search.List request = youtube.search().list(List.of("snippet"));
        request.setKey(apiKey);
        request.setChannelId(channelId);
        request.setType(List.of("video"));
        request.setOrder("date");
        request.setMaxResults(MAX_RESULTS);

        // Add search query if provided (YouTube API supports this natively)
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            request.setQ(searchQuery.trim());
        }

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
        return getPlaylistVideos(playlistId, pageToken, null);
    }

    /**
     * Get videos in a playlist with optional search query (client-side filtering)
     * Note: YouTube API doesn't support search within playlists, so we filter results client-side
     * @param playlistId The YouTube playlist ID
     * @param pageToken Pagination token (can be null)
     * @param searchQuery Optional search query to filter videos by title/description (can be null)
     */
    public List<PlaylistItem> getPlaylistVideos(String playlistId, String pageToken, String searchQuery) throws IOException {
        YouTube.PlaylistItems.List request = youtube.playlistItems().list(List.of("snippet", "contentDetails"));
        request.setKey(apiKey);
        request.setPlaylistId(playlistId);
        request.setMaxResults(MAX_RESULTS);

        if (pageToken != null && !pageToken.isEmpty()) {
            request.setPageToken(pageToken);
        }

        PlaylistItemListResponse response = request.execute();
        List<PlaylistItem> items = response.getItems() != null ? response.getItems() : Collections.emptyList();

        // If search query provided, filter results client-side
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String lowerQuery = searchQuery.trim().toLowerCase();
            return items.stream()
                    .filter(item -> {
                        if (item.getSnippet() == null) return false;
                        String title = item.getSnippet().getTitle();
                        String desc = item.getSnippet().getDescription();
                        return (title != null && title.toLowerCase().contains(lowerQuery)) ||
                               (desc != null && desc.toLowerCase().contains(lowerQuery));
                    })
                    .collect(Collectors.toList());
        }

        return items;
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
     * Enrich channel data with statistics using batch processing
     */
    private void enrichChannelData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> channelIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toList());

        if (channelIds.isEmpty()) {
            return;
        }

        // Process in batches for better performance
        Map<String, Channel> channelMap = Collections.synchronizedMap(new java.util.HashMap<>());
        List<List<String>> batches = partitionList(channelIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            YouTube.Channels.List request = youtube.channels().list(List.of("statistics"));
            request.setKey(apiKey);
            request.setId(batch);
            request.setFields("items(id,statistics(subscriberCount,videoCount))"); // Request only needed fields

            ChannelListResponse response = request.execute();
            List<Channel> channels = response.getItems();

            if (channels != null) {
                channels.stream()
                    .filter(ch -> ch.getId() != null)
                    .forEach(ch -> channelMap.put(ch.getId(), ch));
            }
        }

        // Enrich results with fetched data
        results.parallelStream().forEach(result -> {
            Channel channel = channelMap.get(result.getId());
            if (channel != null && channel.getStatistics() != null) {
                result.setSubscriberCount(channel.getStatistics().getSubscriberCount() != null ?
                        channel.getStatistics().getSubscriberCount().longValue() : 0L);
                result.setVideoCount(channel.getStatistics().getVideoCount() != null ?
                        channel.getStatistics().getVideoCount().longValue() : 0L);
            }
        });
    }

    /**
     * Enrich playlist data with content details and video thumbnails using batch processing
     */
    private void enrichPlaylistData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> playlistIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toList());

        if (playlistIds.isEmpty()) {
            return;
        }

        // Process in batches for better performance
        Map<String, Playlist> playlistMap = Collections.synchronizedMap(new java.util.HashMap<>());
        List<List<String>> batches = partitionList(playlistIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            YouTube.Playlists.List request = youtube.playlists().list(List.of("contentDetails"));
            request.setKey(apiKey);
            request.setId(batch);
            request.setFields("items(id,contentDetails/itemCount)"); // Request only needed fields

            PlaylistListResponse response = request.execute();
            List<Playlist> playlists = response.getItems();

            if (playlists != null) {
                playlists.stream()
                    .filter(pl -> pl.getId() != null)
                    .forEach(pl -> playlistMap.put(pl.getId(), pl));
            }
        }

        // Enrich results with fetched data and video thumbnails
        results.parallelStream().forEach(result -> {
            Playlist playlist = playlistMap.get(result.getId());
            if (playlist != null && playlist.getContentDetails() != null) {
                result.setItemCount(playlist.getContentDetails().getItemCount() != null ?
                        playlist.getContentDetails().getItemCount().longValue() : 0L);
            }

            // Fetch first 4 video thumbnails for playlist
            try {
                List<String> thumbnails = fetchPlaylistVideoThumbnails(result.getId(), 4);
                result.setVideoThumbnails(thumbnails);
            } catch (IOException e) {
                logger.warn("Failed to fetch video thumbnails for playlist {}: {}", result.getId(), e.getMessage());
                result.setVideoThumbnails(Collections.emptyList());
            }
        });
    }

    /**
     * Fetch first N video thumbnails from a playlist
     */
    private List<String> fetchPlaylistVideoThumbnails(String playlistId, int count) throws IOException {
        YouTube.PlaylistItems.List request = youtube.playlistItems().list(List.of("snippet"));
        request.setKey(apiKey);
        request.setPlaylistId(playlistId);
        request.setMaxResults((long) count);
        request.setFields("items(snippet/thumbnails)");

        PlaylistItemListResponse response = request.execute();
        List<PlaylistItem> items = response.getItems();

        if (items == null || items.isEmpty()) {
            return Collections.emptyList();
        }

        return items.stream()
                .filter(item -> item.getSnippet() != null && item.getSnippet().getThumbnails() != null)
                .map(item -> {
                    ThumbnailDetails thumbnails = item.getSnippet().getThumbnails();
                    if (thumbnails.getMedium() != null) {
                        return thumbnails.getMedium().getUrl();
                    } else if (thumbnails.getDefault() != null) {
                        return thumbnails.getDefault().getUrl();
                    }
                    return null;
                })
                .filter(url -> url != null)
                .limit(count)
                .collect(Collectors.toList());
    }

    /**
     * Enrich video data with statistics and content details using batch processing
     */
    private void enrichVideoData(List<EnrichedSearchResult> results) throws IOException {
        if (results == null || results.isEmpty()) {
            return;
        }

        List<String> videoIds = results.stream()
                .map(EnrichedSearchResult::getId)
                .filter(id -> id != null && !id.isEmpty())
                .collect(Collectors.toList());

        if (videoIds.isEmpty()) {
            return;
        }

        // Process in batches for better performance
        Map<String, Video> videoMap = Collections.synchronizedMap(new java.util.HashMap<>());
        List<List<String>> batches = partitionList(videoIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            YouTube.Videos.List request = youtube.videos().list(List.of("statistics", "contentDetails"));
            request.setKey(apiKey);
            request.setId(batch);
            request.setFields("items(id,statistics/viewCount,contentDetails/duration)"); // Request only needed fields

            VideoListResponse response = request.execute();
            List<Video> videos = response.getItems();

            if (videos != null) {
                videos.stream()
                    .filter(v -> v.getId() != null)
                    .forEach(v -> videoMap.put(v.getId(), v));
            }
        }

        // Enrich results with fetched data
        results.parallelStream().forEach(result -> {
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
        });
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

    /**
     * Utility method to partition a list into batches
     */
    private <T> List<List<T>> partitionList(List<T> list, int batchSize) {
        List<List<T>> partitions = new ArrayList<>();
        for (int i = 0; i < list.size(); i += batchSize) {
            partitions.add(list.subList(i, Math.min(i + batchSize, list.size())));
        }
        return partitions;
    }

    /**
     * Validate and fetch channel by YouTube ID.
     * Returns channel details if exists, null if not found (404) or deleted.
     * Used for import validation to check if YouTube content still exists.
     */
    @Cacheable(value = "youtubeChannelValidation", key = "#youtubeId", unless = "#result == null")
    public Channel validateAndFetchChannel(String youtubeId) {
        try {
            return getChannelDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Channel validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch playlist by YouTube ID.
     * Returns playlist details if exists, null if not found (404) or deleted.
     * Used for import validation to check if YouTube content still exists.
     */
    @Cacheable(value = "youtubePlaylistValidation", key = "#youtubeId", unless = "#result == null")
    public Playlist validateAndFetchPlaylist(String youtubeId) {
        try {
            return getPlaylistDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Playlist validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch video by YouTube ID.
     * Returns video details if exists, null if not found (404) or deleted.
     * Used for import validation to check if YouTube content still exists.
     */
    @Cacheable(value = "youtubeVideoValidation", key = "#youtubeId", unless = "#result == null")
    public Video validateAndFetchVideo(String youtubeId) {
        try {
            return getVideoDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Video validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Batch validate and fetch channels.
     * More efficient than individual calls when validating multiple channels.
     * Returns map of youtubeId -> Channel (only includes found channels).
     */
    public Map<String, Channel> batchValidateChannels(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Channel> channelMap = new java.util.HashMap<>();
        List<List<String>> batches = partitionList(youtubeIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            try {
                YouTube.Channels.List request = youtube.channels().list(List.of("snippet", "statistics"));
                request.setKey(apiKey);
                request.setId(batch);

                ChannelListResponse response = request.execute();
                List<Channel> channels = response.getItems();

                if (channels != null) {
                    channels.stream()
                        .filter(ch -> ch.getId() != null)
                        .forEach(ch -> channelMap.put(ch.getId(), ch));
                }
            } catch (IOException e) {
                logger.warn("Batch channel validation failed for batch: {}", e.getMessage());
            }
        }

        return channelMap;
    }

    /**
     * Batch validate and fetch playlists.
     * More efficient than individual calls when validating multiple playlists.
     * Returns map of youtubeId -> Playlist (only includes found playlists).
     */
    public Map<String, Playlist> batchValidatePlaylists(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Playlist> playlistMap = new java.util.HashMap<>();
        List<List<String>> batches = partitionList(youtubeIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            try {
                YouTube.Playlists.List request = youtube.playlists().list(List.of("snippet", "contentDetails"));
                request.setKey(apiKey);
                request.setId(batch);

                PlaylistListResponse response = request.execute();
                List<Playlist> playlists = response.getItems();

                if (playlists != null) {
                    playlists.stream()
                        .filter(pl -> pl.getId() != null)
                        .forEach(pl -> playlistMap.put(pl.getId(), pl));
                }
            } catch (IOException e) {
                logger.warn("Batch playlist validation failed for batch: {}", e.getMessage());
            }
        }

        return playlistMap;
    }

    /**
     * Batch validate and fetch videos.
     * More efficient than individual calls when validating multiple videos.
     * Returns map of youtubeId -> Video (only includes found videos).
     */
    public Map<String, Video> batchValidateVideos(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        Map<String, Video> videoMap = new java.util.HashMap<>();
        List<List<String>> batches = partitionList(youtubeIds, BATCH_SIZE);

        for (List<String> batch : batches) {
            try {
                YouTube.Videos.List request = youtube.videos().list(List.of("snippet", "statistics", "contentDetails"));
                request.setKey(apiKey);
                request.setId(batch);

                VideoListResponse response = request.execute();
                List<Video> videos = response.getItems();

                if (videos != null) {
                    videos.stream()
                        .filter(v -> v.getId() != null)
                        .forEach(v -> videoMap.put(v.getId(), v));
                }
            } catch (IOException e) {
                logger.warn("Batch video validation failed for batch: {}", e.getMessage());
            }
        }

        return videoMap;
    }
}

