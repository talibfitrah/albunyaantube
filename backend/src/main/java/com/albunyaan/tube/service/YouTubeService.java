package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.SearchPageResponse;
import com.albunyaan.tube.util.YouTubeUrlUtils;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.NewPipe;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.StreamingService;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.ChannelInfoItem;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.search.SearchExtractor;
import org.schabi.newpipe.extractor.search.SearchInfo;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeChannelLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubePlaylistLinkHandlerFactory;
import org.schabi.newpipe.extractor.services.youtube.linkHandler.YoutubeStreamLinkHandlerFactory;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * NewPipeExtractor YouTube Integration
 * <p>
 * Provides search and metadata fetching from YouTube for admin interface
 * using NewPipeExtractor (no API key required).
 * <p>
 * This service replaces the YouTube Data API v3 implementation with
 * NewPipeExtractor, which scrapes YouTube content directly without
 * quota limits or API key requirements.
 */
@Service
public class YouTubeService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeService.class);
    private static final int DEFAULT_SEARCH_RESULTS = 20;
    private static final ExecutorService executorService = Executors.newFixedThreadPool(3);

    private final StreamingService youtube;
    private final YoutubeChannelLinkHandlerFactory channelLinkHandlerFactory;
    private final YoutubePlaylistLinkHandlerFactory playlistLinkHandlerFactory;
    private final YoutubeStreamLinkHandlerFactory streamLinkHandlerFactory;

    @Autowired
    public YouTubeService(@Qualifier("newPipeYouTubeService") StreamingService youTubeService) {
        this.youtube = youTubeService;
        this.channelLinkHandlerFactory = YoutubeChannelLinkHandlerFactory.getInstance();
        this.playlistLinkHandlerFactory = YoutubePlaylistLinkHandlerFactory.getInstance();
        this.streamLinkHandlerFactory = YoutubeStreamLinkHandlerFactory.getInstance();

        logger.info("YouTubeService initialized with NewPipeExtractor");
        logger.info("Service: {}, ID: {}", youtube.getServiceInfo().getName(), youtube.getServiceId());
    }

    /**
     * Shutdown the executor service when the bean is destroyed
     */
    @PreDestroy
    public void shutdown() {
        logger.info("Shutting down YouTubeService executor service...");
        executorService.shutdown();

        try {
            // Wait for termination with a reasonable timeout (10 seconds)
            if (!executorService.awaitTermination(10, TimeUnit.SECONDS)) {
                logger.warn("Executor service did not terminate in time, forcing shutdown...");
                executorService.shutdownNow();

                // Wait a bit more after forceful shutdown
                if (!executorService.awaitTermination(5, TimeUnit.SECONDS)) {
                    logger.error("Executor service did not terminate even after forced shutdown");
                }
            } else {
                logger.info("Executor service shut down successfully");
            }
        } catch (InterruptedException e) {
            logger.error("Interrupted while waiting for executor service shutdown", e);
            // Restore interrupt flag
            Thread.currentThread().interrupt();
            // Force shutdown
            executorService.shutdownNow();
        }
    }

    /**
     * Unified search for all content types with pagination
     * Returns mixed results (channels, playlists, videos) like YouTube's native search
     */
    public SearchPageResponse searchAllEnrichedPaged(String query, String pageToken) throws IOException {
        try {
            logger.debug("Searching YouTube for: '{}' with pageToken: {}", query, pageToken);

            // Create search extractor
            SearchExtractor extractor = youtube.getSearchExtractor(query);

            // Fetch initial page
            extractor.fetchPage();

            List<InfoItem> allItems = new ArrayList<>(extractor.getInitialPage().getItems());
            Page nextPage = extractor.getInitialPage().getNextPage();

            // If pageToken provided, navigate to that page
            if (pageToken != null && !pageToken.isEmpty()) {
                // NewPipe uses Page objects for pagination
                // We'll encode/decode pageToken as needed
                try {
                    Page requestedPage = decodePageToken(pageToken);
                    if (requestedPage != null) {
                        ListExtractor.InfoItemsPage<InfoItem> page = extractor.getPage(requestedPage);
                        allItems = new ArrayList<>(page.getItems());
                        nextPage = page.getNextPage();
                    }
                } catch (Exception e) {
                    logger.warn("Failed to decode page token, using initial page: {}", e.getMessage());
                }
            }

            // Convert to enriched results
            List<EnrichedSearchResult> enrichedResults = new ArrayList<>();
            for (InfoItem item : allItems) {
                EnrichedSearchResult result = convertToEnrichedResult(item);
                if (result != null) {
                    enrichedResults.add(result);
                }
            }

            // Encode next page token
            String nextPageToken = nextPage != null ? encodePageToken(nextPage) : null;

            logger.debug("Search returned {} results, nextPageToken: {}", enrichedResults.size(), nextPageToken != null ? "present" : "null");

            return new SearchPageResponse(
                    enrichedResults,
                    nextPageToken,
                    enrichedResults.size() // NewPipe doesn't provide total results
            );

        } catch (ExtractionException e) {
            logger.error("NewPipe extraction failed for query '{}': {}", query, e.getMessage(), e);
            throw new IOException("YouTube search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Unified search for all content types (no pagination)
     * @deprecated Use searchAllEnrichedPaged for pagination support
     */
    @Cacheable(value = "newpipeSearchResults", key = "'all:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchAllEnriched(String query) throws IOException {
        SearchPageResponse response = searchAllEnrichedPaged(query, null);
        return response.getItems();
    }

    /**
     * Search for channels by query with full statistics (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'channel:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchChannelsEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.CHANNEL));
    }

    /**
     * Search for playlists by query with full details (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'playlist:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchPlaylistsEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.PLAYLIST));
    }

    /**
     * Search for videos by query with full statistics (with caching)
     */
    @Cacheable(value = "newpipeSearchResults", key = "'video:' + #query", unless = "#result == null || #result.isEmpty()")
    public List<EnrichedSearchResult> searchVideosEnriched(String query) throws IOException {
        return searchByType(query, Collections.singletonList(InfoItem.InfoType.STREAM));
    }

    /**
     * Search by specific type(s)
     */
    private List<EnrichedSearchResult> searchByType(String query, List<InfoItem.InfoType> types) throws IOException {
        try {
            logger.debug("Searching YouTube for type {} with query: '{}'", types, query);

            SearchExtractor extractor = youtube.getSearchExtractor(query);
            extractor.fetchPage();

            List<InfoItem> items = extractor.getInitialPage().getItems();
            List<EnrichedSearchResult> results = new ArrayList<>();

            // Filter by requested types
            for (InfoItem item : items) {
                if (types.contains(item.getInfoType())) {
                    EnrichedSearchResult result = convertToEnrichedResult(item);
                    if (result != null) {
                        results.add(result);
                        if (results.size() >= DEFAULT_SEARCH_RESULTS) {
                            break;
                        }
                    }
                }
            }

            logger.debug("Search returned {} results for type {}", results.size(), types);
            return results;

        } catch (ExtractionException e) {
            logger.error("NewPipe search failed for query '{}': {}", query, e.getMessage(), e);
            throw new IOException("YouTube search failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get channel details by channel ID or URL
     */
    @Cacheable(value = "newpipeChannelInfo", key = "#channelId", unless = "#result == null")
    public ChannelInfo getChannelDetails(String channelId) throws IOException {
        try {
            logger.debug("Fetching channel details for: {}", channelId);

            // Create URL from channel ID
            String url = channelLinkHandlerFactory.getUrl(channelId);
            ChannelInfo info = ChannelInfo.getInfo(youtube, url);

            logger.debug("Channel '{}' has {} subscribers",
                    info.getName(), info.getSubscriberCount());

            return info;

        } catch (ExtractionException e) {
            logger.error("Failed to fetch channel details for '{}': {}", channelId, e.getMessage());
            throw new IOException("Channel fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get videos from a channel (with pagination)
     */
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken) throws IOException {
        return getChannelVideos(channelId, pageToken, null);
    }

    /**
     * Get videos from a channel with optional search filter
     */
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken, String searchQuery) throws IOException {
        logger.debug("Fetching videos for channel: {}, pageToken: {}, search: {}", channelId, pageToken, searchQuery);

        ChannelInfo channelInfo = getChannelDetails(channelId);
        List<StreamInfoItem> videos = new ArrayList<>();

        // Get videos tab from channel tabs
        try {
            // Find the videos tab (usually the first tab or labeled as "Videos")
            ListLinkHandler videosTab = null;
            for (ListLinkHandler tab : channelInfo.getTabs()) {
                if (tab.getContentFilters().isEmpty() ||
                    tab.getContentFilters().contains("videos") ||
                    tab.getContentFilters().contains("uploads")) {
                    videosTab = tab;
                    break;
                }
            }

            if (videosTab == null) {
                logger.warn("No videos tab found for channel: {}", channelId);
                return videos;
            }

            // Fetch the tab to get videos
            ChannelTabExtractor tabExtractor = youtube.getChannelTabExtractor(videosTab);
            tabExtractor.fetchPage();

            // Start with the requested page or initial page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = tabExtractor.getPage(requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Iterate through pages with early termination
            boolean hasSearchQuery = searchQuery != null && !searchQuery.trim().isEmpty();
            String lowerQuery = hasSearchQuery ? searchQuery.trim().toLowerCase() : null;

            while (page != null && videos.size() < DEFAULT_SEARCH_RESULTS) {
                // Process items from current page
                for (InfoItem item : page.getItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem streamItem = (StreamInfoItem) item;

                        // Apply search filter if provided
                        if (hasSearchQuery) {
                            if (streamItem.getName().toLowerCase().contains(lowerQuery)) {
                                videos.add(streamItem);
                            }
                        } else {
                            videos.add(streamItem);
                        }

                        // Early termination when we have enough results
                        if (videos.size() >= DEFAULT_SEARCH_RESULTS) {
                            break;
                        }
                    }
                }

                // Stop if we have enough results
                if (videos.size() >= DEFAULT_SEARCH_RESULTS) {
                    break;
                }

                // Fetch next page if available
                if (page.hasNextPage()) {
                    try {
                        page = tabExtractor.getPage(page.getNextPage());
                    } catch (Exception e) {
                        logger.warn("Failed to fetch next page of videos: {}", e.getMessage());
                        break;
                    }
                } else {
                    break;
                }
            }

        } catch (Exception e) {
            logger.warn("Failed to fetch channel videos: {}", e.getMessage());
        }

        logger.debug("Fetched {} videos from channel", videos.size());
        return videos;
    }

    /**
     * Get playlists from a channel
     */
    public List<PlaylistInfoItem> getChannelPlaylists(String channelId, String pageToken) throws IOException {
        logger.debug("Fetching playlists for channel: {}, pageToken: {}", channelId, pageToken);

        ChannelInfo channelInfo = getChannelDetails(channelId);

        if (channelInfo == null) {
            logger.warn("Channel info is null for channel: {}", channelId);
            return Collections.emptyList();
        }

        // Get channel tabs and find playlists tab
        List<ListLinkHandler> tabs = channelInfo.getTabs();
        List<PlaylistInfoItem> playlists = new ArrayList<>();

        if (tabs == null || tabs.isEmpty()) {
            logger.warn("No tabs found for channel: {}", channelId);
            return playlists;
        }

        logger.debug("Channel has {} tabs available", tabs.size());

        try {
            // Find the playlists tab
            ListLinkHandler playlistsTab = null;
            for (ListLinkHandler tab : tabs) {
                if (tab.getContentFilters().contains("playlists")) {
                    playlistsTab = tab;
                    break;
                }
            }

            if (playlistsTab == null) {
                logger.debug("No playlists tab found for channel: {}", channelId);
                return playlists;
            }

            // Fetch the playlists tab
            ChannelTabExtractor tabExtractor = youtube.getChannelTabExtractor(playlistsTab);
            tabExtractor.fetchPage();

            // Get initial page or requested page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = tabExtractor.getPage(requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Extract playlist items from the page
            for (InfoItem item : page.getItems()) {
                if (item instanceof PlaylistInfoItem) {
                    playlists.add((PlaylistInfoItem) item);
                }
            }

            logger.debug("Fetched {} playlists from channel", playlists.size());

        } catch (ExtractionException e) {
            String errorMsg = "Failed to fetch playlists for channel " + channelId + ": " + e.getMessage();
            logger.error(errorMsg, e);
            throw new IOException(errorMsg, e);
        } catch (Exception e) {
            String errorMsg = "Unexpected error fetching playlists for channel " + channelId + ": " + e.getMessage();
            logger.error(errorMsg, e);
            throw new IOException(errorMsg, e);
        }

        return playlists;
    }

    /**
     * Get playlist details by playlist ID
     */
    @Cacheable(value = "newpipePlaylistInfo", key = "#playlistId", unless = "#result == null")
    public PlaylistInfo getPlaylistDetails(String playlistId) throws IOException {
        try {
            logger.debug("Fetching playlist details for: {}", playlistId);

            String url = playlistLinkHandlerFactory.getUrl(playlistId);
            PlaylistInfo info = PlaylistInfo.getInfo(youtube, url);

            logger.debug("Playlist '{}' has {} videos", info.getName(), info.getStreamCount());
            return info;

        } catch (ExtractionException e) {
            logger.error("Failed to fetch playlist details for '{}': {}", playlistId, e.getMessage());
            throw new IOException("Playlist fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Get videos in a playlist (with pagination)
     */
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken) throws IOException {
        return getPlaylistVideos(playlistId, pageToken, null);
    }

    /**
     * Get videos in a playlist with optional search filter
     */
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken, String searchQuery) throws IOException {
        logger.debug("Fetching videos for playlist: {}, pageToken: {}, search: {}", playlistId, pageToken, searchQuery);

        PlaylistInfo playlistInfo = getPlaylistDetails(playlistId);
        List<StreamInfoItem> videos = new ArrayList<>();

        // Get initial items from playlist
        List<StreamInfoItem> relatedItems = playlistInfo.getRelatedItems();
        if (relatedItems != null) {
            videos.addAll(relatedItems);
        }

        // If pageToken provided, fetch that page
        if (pageToken != null && !pageToken.isEmpty()) {
            try {
                Page requestedPage = decodePageToken(pageToken);
                if (requestedPage != null) {
                    String url = playlistLinkHandlerFactory.getUrl(playlistId);
                    ListExtractor.InfoItemsPage<StreamInfoItem> page = PlaylistInfo.getMoreItems(youtube, url, requestedPage);
                    videos = new ArrayList<>(page.getItems());
                }
            } catch (Exception e) {
                logger.warn("Failed to fetch page, using initial items: {}", e.getMessage());
            }
        }

        // Filter by search query if provided
        if (searchQuery != null && !searchQuery.trim().isEmpty()) {
            String lowerQuery = searchQuery.trim().toLowerCase();
            videos = videos.stream()
                    .filter(v -> v.getName().toLowerCase().contains(lowerQuery))
                    .collect(Collectors.toList());
        }

        logger.debug("Fetched {} videos from playlist", videos.size());
        return videos.stream().limit(DEFAULT_SEARCH_RESULTS).collect(Collectors.toList());
    }

    /**
     * Get video details by video ID
     */
    @Cacheable(value = "newpipeVideoInfo", key = "#videoId", unless = "#result == null")
    public StreamInfo getVideoDetails(String videoId) throws IOException {
        try {
            logger.debug("Fetching video details for: {}", videoId);

            String url = streamLinkHandlerFactory.getUrl(videoId);
            StreamInfo info = StreamInfo.getInfo(youtube, url);

            logger.debug("Video '{}' has {} views, duration: {}s",
                    info.getName(), info.getViewCount(), info.getDuration());

            return info;

        } catch (ExtractionException e) {
            logger.error("Failed to fetch video details for '{}': {}", videoId, e.getMessage());
            throw new IOException("Video fetch failed: " + e.getMessage(), e);
        }
    }

    /**
     * Validate and fetch channel by YouTube ID
     */
    @Cacheable(value = "newpipeChannelValidation", key = "#youtubeId", unless = "#result == null")
    public ChannelInfo validateAndFetchChannel(String youtubeId) {
        try {
            return getChannelDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Channel validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch playlist by YouTube ID
     */
    @Cacheable(value = "newpipePlaylistValidation", key = "#youtubeId", unless = "#result == null")
    public PlaylistInfo validateAndFetchPlaylist(String youtubeId) {
        try {
            return getPlaylistDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Playlist validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Validate and fetch video by YouTube ID
     */
    @Cacheable(value = "newpipeVideoValidation", key = "#youtubeId", unless = "#result == null")
    public StreamInfo validateAndFetchVideo(String youtubeId) {
        try {
            return getVideoDetails(youtubeId);
        } catch (IOException e) {
            logger.warn("Video validation failed for {}: {}", youtubeId, e.getMessage());
            return null;
        }
    }

    /**
     * Batch validate and fetch channels
     * More efficient than individual calls when validating multiple channels
     */
    public Map<String, ChannelInfo> batchValidateChannels(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} channels", youtubeIds.size());

        Map<String, ChannelInfo> channelMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String channelId : youtubeIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    ChannelInfo info = getChannelDetails(channelId);
                    if (info != null) {
                        channelMap.put(channelId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate channel {}: {}", channelId, e.getMessage());
                }
            }, executorService);
            futures.add(future);
        }

        // Wait for all validations to complete
        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} channels found", channelMap.size(), youtubeIds.size());
        return channelMap;
    }

    /**
     * Batch validate and fetch playlists
     */
    public Map<String, PlaylistInfo> batchValidatePlaylists(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} playlists", youtubeIds.size());

        Map<String, PlaylistInfo> playlistMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String playlistId : youtubeIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    PlaylistInfo info = getPlaylistDetails(playlistId);
                    if (info != null) {
                        playlistMap.put(playlistId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate playlist {}: {}", playlistId, e.getMessage());
                }
            }, executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} playlists found", playlistMap.size(), youtubeIds.size());
        return playlistMap;
    }

    /**
     * Batch validate and fetch videos
     */
    public Map<String, StreamInfo> batchValidateVideos(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} videos", youtubeIds.size());

        Map<String, StreamInfo> videoMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String videoId : youtubeIds) {
            CompletableFuture<Void> future = CompletableFuture.runAsync(() -> {
                try {
                    StreamInfo info = getVideoDetails(videoId);
                    if (info != null) {
                        videoMap.put(videoId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate video {}: {}", videoId, e.getMessage());
                }
            }, executorService);
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} videos found", videoMap.size(), youtubeIds.size());
        return videoMap;
    }

    // ==================== Helper Methods ====================

    /**
     * Convert NewPipe InfoItem to EnrichedSearchResult
     */
    private EnrichedSearchResult convertToEnrichedResult(InfoItem item) {
        try {
            if (item instanceof ChannelInfoItem) {
                return EnrichedSearchResult.fromChannelInfoItem((ChannelInfoItem) item);
            } else if (item instanceof PlaylistInfoItem) {
                return EnrichedSearchResult.fromPlaylistInfoItem((PlaylistInfoItem) item);
            } else if (item instanceof StreamInfoItem) {
                return EnrichedSearchResult.fromStreamInfoItem((StreamInfoItem) item);
            }
            return null;
        } catch (Exception e) {
            logger.warn("Failed to convert InfoItem to EnrichedSearchResult: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Encode NewPipe Page object to string token
     */
    private String encodePageToken(Page page) {
        if (page == null) {
            return null;
        }
        // Simple encoding: just use the page URL
        // More sophisticated encoding could use Base64
        try {
            return page.getUrl();
        } catch (Exception e) {
            logger.warn("Failed to encode page token: {}", e.getMessage());
            return null;
        }
    }

    /**
     * Decode string token to NewPipe Page object
     */
    private Page decodePageToken(String token) {
        if (token == null || token.isEmpty()) {
            return null;
        }
        try {
            // Simple decoding: create Page from URL
            return new Page(token);
        } catch (Exception e) {
            logger.warn("Failed to decode page token: {}", e.getMessage());
            return null;
        }
    }

    // ==================== DTO Mapping Methods ====================

    /**
     * Map ChannelInfo to ChannelDetailsDto
     */
    public com.albunyaan.tube.dto.ChannelDetailsDto mapToChannelDetailsDto(ChannelInfo channel) {
        if (channel == null) {
            return null;
        }

        com.albunyaan.tube.dto.ChannelDetailsDto dto = new com.albunyaan.tube.dto.ChannelDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(channel.getUrl()));
        dto.setName(channel.getName());
        dto.setUrl(channel.getUrl());
        dto.setDescription(channel.getDescription());
        dto.setSubscriberCount(channel.getSubscriberCount());
        dto.setStreamCount(null); // Not available in ChannelInfo
        dto.setTags(channel.getTags());

        // Handle avatars (channels have avatars, not thumbnails)
        if (channel.getAvatars() != null && !channel.getAvatars().isEmpty()) {
            dto.setThumbnailUrl(channel.getAvatars().get(0).getUrl());
        }

        // Handle banners
        if (channel.getBanners() != null && !channel.getBanners().isEmpty()) {
            dto.setBannerUrl(channel.getBanners().get(0).getUrl());
        }

        return dto;
    }

    /**
     * Map PlaylistInfo to PlaylistDetailsDto
     */
    public com.albunyaan.tube.dto.PlaylistDetailsDto mapToPlaylistDetailsDto(PlaylistInfo playlist) {
        if (playlist == null) {
            return null;
        }

        com.albunyaan.tube.dto.PlaylistDetailsDto dto = new com.albunyaan.tube.dto.PlaylistDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(playlist.getUrl()));
        dto.setName(playlist.getName());
        dto.setUrl(playlist.getUrl());
        dto.setDescription(playlist.getDescription() != null ? playlist.getDescription().getContent() : "");
        dto.setUploaderName(playlist.getUploaderName());
        dto.setUploaderUrl(playlist.getUploaderUrl());
        dto.setStreamCount(playlist.getStreamCount());

        // Handle thumbnail
        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        // Map related streams
        if (playlist.getRelatedItems() != null) {
            List<com.albunyaan.tube.dto.StreamItemDto> streamItems = playlist.getRelatedItems().stream()
                    .map(this::mapToStreamItemDto)
                    .filter(Objects::nonNull)
                    .collect(Collectors.toList());
            dto.setRelatedStreams(streamItems);
        }

        return dto;
    }

    /**
     * Map StreamInfo to StreamDetailsDto
     */
    public com.albunyaan.tube.dto.StreamDetailsDto mapToStreamDetailsDto(StreamInfo stream) {
        if (stream == null) {
            return null;
        }

        com.albunyaan.tube.dto.StreamDetailsDto dto = new com.albunyaan.tube.dto.StreamDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(stream.getUrl()));
        dto.setName(stream.getName());
        dto.setUrl(stream.getUrl());
        dto.setDescription(stream.getDescription() != null ? stream.getDescription().getContent() : "");
        dto.setUploaderName(stream.getUploaderName());
        dto.setUploaderUrl(stream.getUploaderUrl());
        dto.setViewCount(stream.getViewCount());
        dto.setDuration(stream.getDuration());
        dto.setLikeCount(stream.getLikeCount());
        dto.setDislikeCount(stream.getDislikeCount());
        dto.setCategory(stream.getCategory());
        dto.setTags(stream.getTags());

        // Handle thumbnail
        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

        // Format upload date
        if (stream.getUploadDate() != null) {
            try {
                dto.setUploadDate(stream.getUploadDate()
                        .offsetDateTime()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (Exception e) {
                logger.warn("Failed to format upload date: {}", e.getMessage());
            }
        }

        return dto;
    }

    /**
     * Map StreamInfoItem to StreamItemDto
     */
    public com.albunyaan.tube.dto.StreamItemDto mapToStreamItemDto(StreamInfoItem stream) {
        if (stream == null) {
            return null;
        }

        com.albunyaan.tube.dto.StreamItemDto dto = new com.albunyaan.tube.dto.StreamItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(stream.getUrl()));
        dto.setName(stream.getName());
        dto.setUrl(stream.getUrl());
        dto.setUploaderName(stream.getUploaderName());
        dto.setUploaderUrl(stream.getUploaderUrl());
        dto.setViewCount(stream.getViewCount());
        dto.setDuration(stream.getDuration());

        // Handle thumbnail
        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

        // Format upload date
        if (stream.getUploadDate() != null) {
            try {
                dto.setUploadDate(stream.getUploadDate()
                        .offsetDateTime()
                        .format(java.time.format.DateTimeFormatter.ISO_OFFSET_DATE_TIME));
            } catch (Exception e) {
                logger.warn("Failed to format upload date: {}", e.getMessage());
            }
        }

        return dto;
    }

    /**
     * Map PlaylistInfoItem to PlaylistItemDto
     */
    public com.albunyaan.tube.dto.PlaylistItemDto mapToPlaylistItemDto(PlaylistInfoItem playlist) {
        if (playlist == null) {
            return null;
        }

        com.albunyaan.tube.dto.PlaylistItemDto dto = new com.albunyaan.tube.dto.PlaylistItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(playlist.getUrl()));
        dto.setName(playlist.getName());
        dto.setUrl(playlist.getUrl());
        dto.setUploaderName(playlist.getUploaderName());
        dto.setStreamCount(playlist.getStreamCount());

        // Handle thumbnail
        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        return dto;
    }
}
