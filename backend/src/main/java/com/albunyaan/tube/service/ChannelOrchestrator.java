package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.dto.StreamItemDto;
import com.albunyaan.tube.util.YouTubeUrlUtils;
import org.schabi.newpipe.extractor.InfoItem;
import org.schabi.newpipe.extractor.ListExtractor;
import org.schabi.newpipe.extractor.Page;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.channel.tabs.ChannelTabExtractor;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.linkhandler.ListLinkHandler;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.stream.Collectors;

/**
 * P2-T3: Channel Orchestrator
 *
 * Coordinates channel, playlist, and video operations using the YouTubeGateway.
 * Handles:
 * - Fetching channel/playlist/video details
 * - Getting nested content (videos from channel, playlists from channel)
 * - Validation operations
 * - Batch validation
 * - DTO mapping
 */
@Service
public class ChannelOrchestrator {

    private static final Logger logger = LoggerFactory.getLogger(ChannelOrchestrator.class);
    private static final int DEFAULT_RESULTS = 20;

    private final YouTubeGateway gateway;

    public ChannelOrchestrator(YouTubeGateway gateway) {
        this.gateway = gateway;
        logger.info("ChannelOrchestrator initialized");
    }

    // ==================== Channel Operations ====================

    /**
     * Get channel details by channel ID or URL
     */
    @Cacheable(value = "newpipeChannelInfo", key = "#channelId", unless = "#result == null")
    public ChannelInfo getChannelDetails(String channelId) throws IOException {
        try {
            logger.debug("Fetching channel details for: {}", channelId);
            ChannelInfo info = gateway.fetchChannelInfo(channelId);
            logger.debug("Channel '{}' has {} subscribers", info.getName(), info.getSubscriberCount());
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

        try {
            // Find the videos tab
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

            // Fetch the tab
            ChannelTabExtractor tabExtractor = gateway.createChannelTabExtractor(videosTab);
            tabExtractor.fetchPage();

            // Get requested page or initial page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = tabExtractor.getPage(requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Process pages with early termination
            boolean hasSearchQuery = searchQuery != null && !searchQuery.trim().isEmpty();
            String lowerQuery = hasSearchQuery ? searchQuery.trim().toLowerCase() : null;

            while (page != null && videos.size() < DEFAULT_RESULTS) {
                for (InfoItem item : page.getItems()) {
                    if (item instanceof StreamInfoItem) {
                        StreamInfoItem streamItem = (StreamInfoItem) item;

                        if (hasSearchQuery) {
                            if (streamItem.getName().toLowerCase().contains(lowerQuery)) {
                                videos.add(streamItem);
                            }
                        } else {
                            videos.add(streamItem);
                        }

                        if (videos.size() >= DEFAULT_RESULTS) {
                            break;
                        }
                    }
                }

                if (videos.size() >= DEFAULT_RESULTS) {
                    break;
                }

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
            ChannelTabExtractor tabExtractor = gateway.createChannelTabExtractor(playlistsTab);
            tabExtractor.fetchPage();

            // Get initial page or requested page
            ListExtractor.InfoItemsPage<InfoItem> page;
            if (pageToken != null && !pageToken.isEmpty()) {
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    page = tabExtractor.getPage(requestedPage);
                } else {
                    page = tabExtractor.getInitialPage();
                }
            } else {
                page = tabExtractor.getInitialPage();
            }

            // Extract playlist items
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

    // ==================== Playlist Operations ====================

    /**
     * Get playlist details by playlist ID
     */
    @Cacheable(value = "newpipePlaylistInfo", key = "#playlistId", unless = "#result == null")
    public PlaylistInfo getPlaylistDetails(String playlistId) throws IOException {
        try {
            logger.debug("Fetching playlist details for: {}", playlistId);
            PlaylistInfo info = gateway.fetchPlaylistInfo(playlistId);
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
                Page requestedPage = gateway.decodePageToken(pageToken);
                if (requestedPage != null) {
                    ListExtractor.InfoItemsPage<StreamInfoItem> page = gateway.getPlaylistMoreItems(playlistId, requestedPage);
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
        return videos.stream().limit(DEFAULT_RESULTS).collect(Collectors.toList());
    }

    // ==================== Video Operations ====================

    /**
     * Get video details by video ID
     */
    @Cacheable(value = "newpipeVideoInfo", key = "#videoId", unless = "#result == null")
    public StreamInfo getVideoDetails(String videoId) throws IOException {
        try {
            logger.debug("Fetching video details for: {}", videoId);
            StreamInfo info = gateway.fetchStreamInfo(videoId);
            logger.debug("Video '{}' has {} views, duration: {}s",
                    info.getName(), info.getViewCount(), info.getDuration());
            return info;
        } catch (ExtractionException e) {
            logger.error("Failed to fetch video details for '{}': {}", videoId, e.getMessage());
            throw new IOException("Video fetch failed: " + e.getMessage(), e);
        }
    }

    // ==================== Validation Operations ====================

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

    // ==================== Batch Validation ====================

    /**
     * Batch validate and fetch channels
     */
    public Map<String, ChannelInfo> batchValidateChannels(List<String> youtubeIds) {
        if (youtubeIds == null || youtubeIds.isEmpty()) {
            return Collections.emptyMap();
        }

        logger.debug("Batch validating {} channels", youtubeIds.size());

        Map<String, ChannelInfo> channelMap = Collections.synchronizedMap(new HashMap<>());
        List<CompletableFuture<Void>> futures = new ArrayList<>();

        for (String channelId : youtubeIds) {
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    ChannelInfo info = getChannelDetails(channelId);
                    if (info != null) {
                        channelMap.put(channelId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate channel {}: {}", channelId, e.getMessage());
                }
            });
            futures.add(future);
        }

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
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    PlaylistInfo info = getPlaylistDetails(playlistId);
                    if (info != null) {
                        playlistMap.put(playlistId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate playlist {}: {}", playlistId, e.getMessage());
                }
            });
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
            CompletableFuture<Void> future = gateway.runAsync(() -> {
                try {
                    StreamInfo info = getVideoDetails(videoId);
                    if (info != null) {
                        videoMap.put(videoId, info);
                    }
                } catch (IOException e) {
                    logger.warn("Failed to validate video {}: {}", videoId, e.getMessage());
                }
            });
            futures.add(future);
        }

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();

        logger.debug("Batch validation completed: {}/{} videos found", videoMap.size(), youtubeIds.size());
        return videoMap;
    }

    // ==================== DTO-First Methods ====================

    /**
     * Get channel details as DTO
     */
    public ChannelDetailsDto getChannelDetailsDto(String channelId) throws IOException {
        ChannelInfo channel = getChannelDetails(channelId);
        return mapToChannelDetailsDto(channel);
    }

    /**
     * Get channel videos as DTOs
     */
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken) throws IOException {
        return getChannelVideos(channelId, pageToken).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get channel videos as DTOs with optional search filter
     */
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken, String searchQuery) throws IOException {
        return getChannelVideos(channelId, pageToken, searchQuery).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get channel playlists as DTOs
     */
    public List<PlaylistItemDto> getChannelPlaylistsDto(String channelId, String pageToken) throws IOException {
        return getChannelPlaylists(channelId, pageToken).stream()
                .map(this::mapToPlaylistItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get playlist details as DTO
     */
    public PlaylistDetailsDto getPlaylistDetailsDto(String playlistId) throws IOException {
        PlaylistInfo playlist = getPlaylistDetails(playlistId);
        return mapToPlaylistDetailsDto(playlist);
    }

    /**
     * Get playlist videos as DTOs
     */
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken) throws IOException {
        return getPlaylistVideos(playlistId, pageToken).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get playlist videos as DTOs with optional search filter
     */
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken, String searchQuery) throws IOException {
        return getPlaylistVideos(playlistId, pageToken, searchQuery).stream()
                .map(this::mapToStreamItemDto)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Get video details as DTO
     */
    public StreamDetailsDto getVideoDetailsDto(String videoId) throws IOException {
        StreamInfo video = getVideoDetails(videoId);
        return mapToStreamDetailsDto(video);
    }

    /**
     * Validate and fetch channel as DTO
     */
    public ChannelDetailsDto validateAndFetchChannelDto(String youtubeId) {
        ChannelInfo info = validateAndFetchChannel(youtubeId);
        return info != null ? mapToChannelDetailsDto(info) : null;
    }

    /**
     * Validate and fetch playlist as DTO
     */
    public PlaylistDetailsDto validateAndFetchPlaylistDto(String youtubeId) {
        PlaylistInfo info = validateAndFetchPlaylist(youtubeId);
        return info != null ? mapToPlaylistDetailsDto(info) : null;
    }

    /**
     * Validate and fetch video as DTO
     */
    public StreamDetailsDto validateAndFetchVideoDto(String youtubeId) {
        StreamInfo info = validateAndFetchVideo(youtubeId);
        return info != null ? mapToStreamDetailsDto(info) : null;
    }

    /**
     * Batch validate channels and return as DTOs
     */
    public Map<String, ChannelDetailsDto> batchValidateChannelsDto(List<String> youtubeIds) {
        return batchValidateChannels(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToChannelDetailsDto(e.getValue())
                ));
    }

    /**
     * Batch validate playlists and return as DTOs
     */
    public Map<String, PlaylistDetailsDto> batchValidatePlaylistsDto(List<String> youtubeIds) {
        return batchValidatePlaylists(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToPlaylistDetailsDto(e.getValue())
                ));
    }

    /**
     * Batch validate videos and return as DTOs
     */
    public Map<String, StreamDetailsDto> batchValidateVideosDto(List<String> youtubeIds) {
        return batchValidateVideos(youtubeIds).entrySet().stream()
                .collect(Collectors.toMap(
                        Map.Entry::getKey,
                        e -> mapToStreamDetailsDto(e.getValue())
                ));
    }

    // ==================== DTO Mapping Methods ====================

    /**
     * Map ChannelInfo to ChannelDetailsDto
     */
    public ChannelDetailsDto mapToChannelDetailsDto(ChannelInfo channel) {
        if (channel == null) {
            return null;
        }

        ChannelDetailsDto dto = new ChannelDetailsDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(channel.getUrl()));
        dto.setName(channel.getName());
        dto.setUrl(channel.getUrl());
        dto.setDescription(channel.getDescription());
        dto.setSubscriberCount(channel.getSubscriberCount());
        dto.setStreamCount(null);
        dto.setTags(channel.getTags());

        if (channel.getAvatars() != null && !channel.getAvatars().isEmpty()) {
            dto.setThumbnailUrl(channel.getAvatars().get(0).getUrl());
        }

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

        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        if (playlist.getRelatedItems() != null) {
            List<StreamItemDto> streamItems = playlist.getRelatedItems().stream()
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

        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

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
    public StreamItemDto mapToStreamItemDto(StreamInfoItem stream) {
        if (stream == null) {
            return null;
        }

        StreamItemDto dto = new StreamItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(stream.getUrl()));
        dto.setName(stream.getName());
        dto.setUrl(stream.getUrl());
        dto.setUploaderName(stream.getUploaderName());
        dto.setUploaderUrl(stream.getUploaderUrl());
        dto.setViewCount(stream.getViewCount());
        dto.setDuration(stream.getDuration());

        if (stream.getThumbnails() != null && !stream.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(stream.getThumbnails().get(0).getUrl());
        }

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
    public PlaylistItemDto mapToPlaylistItemDto(PlaylistInfoItem playlist) {
        if (playlist == null) {
            return null;
        }

        PlaylistItemDto dto = new PlaylistItemDto();
        dto.setId(YouTubeUrlUtils.extractYouTubeId(playlist.getUrl()));
        dto.setName(playlist.getName());
        dto.setUrl(playlist.getUrl());
        dto.setUploaderName(playlist.getUploaderName());
        dto.setStreamCount(playlist.getStreamCount());

        if (playlist.getThumbnails() != null && !playlist.getThumbnails().isEmpty()) {
            dto.setThumbnailUrl(playlist.getThumbnails().get(0).getUrl());
        }

        return dto;
    }
}
