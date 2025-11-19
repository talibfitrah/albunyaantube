package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.SearchPageResponse;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.dto.StreamItemDto;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.List;
import java.util.Map;

/**
 * P2-T3: YouTubeService Facade
 * <p>
 * Thin facade that delegates to SearchOrchestrator and ChannelOrchestrator.
 * All methods return DTOs to avoid NewPipe type coupling.
 * <p>
 * This class replaces the original 886-line monolithic service with delegation
 * to specialized orchestrators, improving maintainability and testability.
 */
@Service
public class YouTubeService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeService.class);

    private final SearchOrchestrator searchOrchestrator;
    private final ChannelOrchestrator channelOrchestrator;

    public YouTubeService(SearchOrchestrator searchOrchestrator, ChannelOrchestrator channelOrchestrator) {
        this.searchOrchestrator = searchOrchestrator;
        this.channelOrchestrator = channelOrchestrator;
        logger.info("YouTubeService initialized with orchestrators");
    }

    // ==================== Search Operations ====================

    /**
     * Unified search for all content types with pagination
     */
    public SearchPageResponse searchAllEnrichedPaged(String query, String pageToken) throws IOException {
        return searchOrchestrator.searchAllEnrichedPaged(query, pageToken);
    }

    /**
     * Search for channels by query with full statistics
     */
    public List<EnrichedSearchResult> searchChannelsEnriched(String query) throws IOException {
        return searchOrchestrator.searchChannelsEnriched(query);
    }

    /**
     * Search for playlists by query with full details
     */
    public List<EnrichedSearchResult> searchPlaylistsEnriched(String query) throws IOException {
        return searchOrchestrator.searchPlaylistsEnriched(query);
    }

    /**
     * Search for videos by query with full statistics
     */
    public List<EnrichedSearchResult> searchVideosEnriched(String query) throws IOException {
        return searchOrchestrator.searchVideosEnriched(query);
    }

    // ==================== Channel Operations ====================

    /**
     * Get channel details as DTO
     */
    public ChannelDetailsDto getChannelDetailsDto(String channelId) throws IOException {
        return channelOrchestrator.getChannelDetailsDto(channelId);
    }

    /**
     * Get channel videos as DTOs
     */
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken) throws IOException {
        return channelOrchestrator.getChannelVideosDto(channelId, pageToken);
    }

    /**
     * Get channel videos as DTOs with optional search filter
     */
    public List<StreamItemDto> getChannelVideosDto(String channelId, String pageToken, String searchQuery) throws IOException {
        return channelOrchestrator.getChannelVideosDto(channelId, pageToken, searchQuery);
    }

    /**
     * Get channel playlists as DTOs
     */
    public List<PlaylistItemDto> getChannelPlaylistsDto(String channelId, String pageToken) throws IOException {
        return channelOrchestrator.getChannelPlaylistsDto(channelId, pageToken);
    }

    // ==================== Playlist Operations ====================

    /**
     * Get playlist details as DTO
     */
    public PlaylistDetailsDto getPlaylistDetailsDto(String playlistId) throws IOException {
        return channelOrchestrator.getPlaylistDetailsDto(playlistId);
    }

    /**
     * Get playlist videos as DTOs
     */
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken) throws IOException {
        return channelOrchestrator.getPlaylistVideosDto(playlistId, pageToken);
    }

    /**
     * Get playlist videos as DTOs with optional search filter
     */
    public List<StreamItemDto> getPlaylistVideosDto(String playlistId, String pageToken, String searchQuery) throws IOException {
        return channelOrchestrator.getPlaylistVideosDto(playlistId, pageToken, searchQuery);
    }

    // ==================== Video Operations ====================

    /**
     * Get video details as DTO
     */
    public StreamDetailsDto getVideoDetailsDto(String videoId) throws IOException {
        return channelOrchestrator.getVideoDetailsDto(videoId);
    }

    // ==================== Validation Operations ====================

    /**
     * Validate and fetch channel as DTO
     */
    public ChannelDetailsDto validateAndFetchChannelDto(String youtubeId) {
        return channelOrchestrator.validateAndFetchChannelDto(youtubeId);
    }

    /**
     * Validate and fetch playlist as DTO
     */
    public PlaylistDetailsDto validateAndFetchPlaylistDto(String youtubeId) {
        return channelOrchestrator.validateAndFetchPlaylistDto(youtubeId);
    }

    /**
     * Validate and fetch video as DTO
     */
    public StreamDetailsDto validateAndFetchVideoDto(String youtubeId) {
        return channelOrchestrator.validateAndFetchVideoDto(youtubeId);
    }

    // ==================== Batch Validation ====================

    /**
     * Batch validate channels and return as DTOs
     */
    public Map<String, ChannelDetailsDto> batchValidateChannelsDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateChannelsDto(youtubeIds);
    }

    /**
     * Batch validate playlists and return as DTOs
     */
    public Map<String, PlaylistDetailsDto> batchValidatePlaylistsDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidatePlaylistsDto(youtubeIds);
    }

    /**
     * Batch validate videos and return as DTOs
     */
    public Map<String, StreamDetailsDto> batchValidateVideosDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateVideosDto(youtubeIds);
    }
}
