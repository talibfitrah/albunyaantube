package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.EnrichedSearchResult;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.SearchPageResponse;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.dto.StreamItemDto;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.playlist.PlaylistInfoItem;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;
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
 * Maintains backward compatibility for existing controllers and services.
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
     * Unified search for all content types (no pagination)
     * @deprecated Use searchAllEnrichedPaged for pagination support
     */
    @Deprecated
    public List<EnrichedSearchResult> searchAllEnriched(String query) throws IOException {
        return searchOrchestrator.searchAllEnriched(query);
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

    // ==================== Channel Operations (Legacy - use DTO methods) ====================

    /**
     * Get channel details by channel ID or URL
     * @deprecated Use {@link #getChannelDetailsDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public ChannelInfo getChannelDetails(String channelId) throws IOException {
        return channelOrchestrator.getChannelDetails(channelId);
    }

    /**
     * Get videos from a channel (with pagination)
     * @deprecated Use {@link #getChannelVideosDto(String, String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken) throws IOException {
        return channelOrchestrator.getChannelVideos(channelId, pageToken);
    }

    /**
     * Get videos from a channel with optional search filter
     * @deprecated Use {@link #getChannelVideosDto(String, String, String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public List<StreamInfoItem> getChannelVideos(String channelId, String pageToken, String searchQuery) throws IOException {
        return channelOrchestrator.getChannelVideos(channelId, pageToken, searchQuery);
    }

    /**
     * Get playlists from a channel
     * @deprecated Use {@link #getChannelPlaylistsDto(String, String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public List<PlaylistInfoItem> getChannelPlaylists(String channelId, String pageToken) throws IOException {
        return channelOrchestrator.getChannelPlaylists(channelId, pageToken);
    }

    // ==================== Playlist Operations (Legacy - use DTO methods) ====================

    /**
     * Get playlist details by playlist ID
     * @deprecated Use {@link #getPlaylistDetailsDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public PlaylistInfo getPlaylistDetails(String playlistId) throws IOException {
        return channelOrchestrator.getPlaylistDetails(playlistId);
    }

    /**
     * Get videos in a playlist (with pagination)
     * @deprecated Use {@link #getPlaylistVideosDto(String, String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken) throws IOException {
        return channelOrchestrator.getPlaylistVideos(playlistId, pageToken);
    }

    /**
     * Get videos in a playlist with optional search filter
     * @deprecated Use {@link #getPlaylistVideosDto(String, String, String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public List<StreamInfoItem> getPlaylistVideos(String playlistId, String pageToken, String searchQuery) throws IOException {
        return channelOrchestrator.getPlaylistVideos(playlistId, pageToken, searchQuery);
    }

    // ==================== Video Operations (Legacy - use DTO methods) ====================

    /**
     * Get video details by video ID
     * @deprecated Use {@link #getVideoDetailsDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public StreamInfo getVideoDetails(String videoId) throws IOException {
        return channelOrchestrator.getVideoDetails(videoId);
    }

    // ==================== Validation Operations (Legacy - use DTO methods) ====================

    /**
     * Validate and fetch channel by YouTube ID
     * @deprecated Use {@link #validateAndFetchChannelDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public ChannelInfo validateAndFetchChannel(String youtubeId) {
        return channelOrchestrator.validateAndFetchChannel(youtubeId);
    }

    /**
     * Validate and fetch playlist by YouTube ID
     * @deprecated Use {@link #validateAndFetchPlaylistDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public PlaylistInfo validateAndFetchPlaylist(String youtubeId) {
        return channelOrchestrator.validateAndFetchPlaylist(youtubeId);
    }

    /**
     * Validate and fetch video by YouTube ID
     * @deprecated Use {@link #validateAndFetchVideoDto(String)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public StreamInfo validateAndFetchVideo(String youtubeId) {
        return channelOrchestrator.validateAndFetchVideo(youtubeId);
    }

    // ==================== Batch Validation (Legacy - use DTO methods) ====================

    /**
     * Batch validate and fetch channels
     * @deprecated Use {@link #batchValidateChannelsDto(List)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public Map<String, ChannelInfo> batchValidateChannels(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateChannels(youtubeIds);
    }

    /**
     * Batch validate and fetch playlists
     * @deprecated Use {@link #batchValidatePlaylistsDto(List)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public Map<String, PlaylistInfo> batchValidatePlaylists(List<String> youtubeIds) {
        return channelOrchestrator.batchValidatePlaylists(youtubeIds);
    }

    /**
     * Batch validate and fetch videos
     * @deprecated Use {@link #batchValidateVideosDto(List)} instead to avoid NewPipe type coupling
     */
    @Deprecated
    public Map<String, StreamInfo> batchValidateVideos(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateVideos(youtubeIds);
    }

    // ==================== DTO-First Methods ====================

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

    /**
     * Get video details as DTO
     */
    public StreamDetailsDto getVideoDetailsDto(String videoId) throws IOException {
        return channelOrchestrator.getVideoDetailsDto(videoId);
    }

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

    /**
     * Batch validate channels and return as DTOs
     * @deprecated Use {@link #batchValidateChannelsDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, ChannelDetailsDto> batchValidateChannelsDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateChannelsDto(youtubeIds);
    }

    /**
     * Batch validate playlists and return as DTOs
     * @deprecated Use {@link #batchValidatePlaylistsDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, PlaylistDetailsDto> batchValidatePlaylistsDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidatePlaylistsDto(youtubeIds);
    }

    /**
     * Batch validate videos and return as DTOs
     * @deprecated Use {@link #batchValidateVideosDtoWithDetails(List)} for proper error handling
     */
    @Deprecated
    public Map<String, StreamDetailsDto> batchValidateVideosDto(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateVideosDto(youtubeIds);
    }

    /**
     * Batch validate channels and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube channel IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<ChannelDetailsDto> batchValidateChannelsDtoWithDetails(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateChannelsDtoWithDetails(youtubeIds);
    }

    /**
     * Batch validate playlists and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube playlist IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<PlaylistDetailsDto> batchValidatePlaylistsDtoWithDetails(List<String> youtubeIds) {
        return channelOrchestrator.batchValidatePlaylistsDtoWithDetails(youtubeIds);
    }

    /**
     * Batch validate videos and return as DTOs with detailed error categorization.
     * Properly distinguishes between content that doesn't exist vs transient errors.
     *
     * @param youtubeIds List of YouTube video IDs to validate
     * @return BatchValidationResult with valid DTOs, notFound IDs, and error IDs
     */
    public BatchValidationResult<StreamDetailsDto> batchValidateVideosDtoWithDetails(List<String> youtubeIds) {
        return channelOrchestrator.batchValidateVideosDtoWithDetails(youtubeIds);
    }

    // ==================== DTO Mapping Methods ====================

    /**
     * Map ChannelInfo to ChannelDetailsDto
     */
    public com.albunyaan.tube.dto.ChannelDetailsDto mapToChannelDetailsDto(ChannelInfo channel) {
        return channelOrchestrator.mapToChannelDetailsDto(channel);
    }

    /**
     * Map PlaylistInfo to PlaylistDetailsDto
     */
    public com.albunyaan.tube.dto.PlaylistDetailsDto mapToPlaylistDetailsDto(PlaylistInfo playlist) {
        return channelOrchestrator.mapToPlaylistDetailsDto(playlist);
    }

    /**
     * Map StreamInfo to StreamDetailsDto
     */
    public com.albunyaan.tube.dto.StreamDetailsDto mapToStreamDetailsDto(StreamInfo stream) {
        return channelOrchestrator.mapToStreamDetailsDto(stream);
    }

    /**
     * Map StreamInfoItem to StreamItemDto
     */
    public com.albunyaan.tube.dto.StreamItemDto mapToStreamItemDto(StreamInfoItem stream) {
        return channelOrchestrator.mapToStreamItemDto(stream);
    }

    /**
     * Map PlaylistInfoItem to PlaylistItemDto
     */
    public com.albunyaan.tube.dto.PlaylistItemDto mapToPlaylistItemDto(PlaylistInfoItem playlist) {
        return channelOrchestrator.mapToPlaylistItemDto(playlist);
    }
}
