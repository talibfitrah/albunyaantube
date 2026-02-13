package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.SimpleImportItemResult;
import com.albunyaan.tube.dto.SimpleImportResponse;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.util.YouTubeUrlUtils;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.*;
import java.util.concurrent.ExecutionException;

/**
 * Service for importing content in simple JSON format.
 * Format: [{channelId: "Title|Cat1,Cat2|keyword1,keyword2"}, ...]
 *
 * The format supports both legacy (Title|Categories) and new (Title|Categories|Keywords) formats.
 * Keywords section is optional for backward compatibility.
 *
 * Import flow per item:
 * 1. Check if YouTube ID already exists in Firestore → Skip if exists
 * 2. Validate YouTube ID still exists via YouTube API → Skip if 404
 * 3. Fetch full metadata from YouTube API (title, description, thumbnail)
 * 4. Parse comma-separated categories → Map names to IDs
 * 5. Parse comma-separated keywords (if present)
 * 6. Create Firestore document with status=APPROVED by default
 * 7. Track result (success/failed/skipped with reason)
 */
@Service
public class SimpleImportService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleImportService.class);

    /**
     * Keywords bounds - matching ContentLibraryController validation.
     * Applied during both manual entry and YouTube tag enrichment.
     */
    private static final int MAX_KEYWORDS = 50;
    private static final int MAX_KEYWORD_LENGTH = 100;

    private final YouTubeService youTubeService;
    private final CategoryMappingService categoryMappingService;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;

    public SimpleImportService(
            YouTubeService youTubeService,
            CategoryMappingService categoryMappingService,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository
    ) {
        this.youTubeService = youTubeService;
        this.categoryMappingService = categoryMappingService;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
    }

    /**
     * Import content from simple format.
     *
     * @param simpleData Array of 3 maps: channels, playlists, videos
     * @param defaultStatus Status to set for imported items (APPROVED or PENDING)
     * @param currentUserId Firebase UID of user performing import
     * @param validateOnly If true, only validate without importing (dry-run)
     * @return Import response with counts and detailed results
     */
    public SimpleImportResponse importSimpleFormat(
            List<Map<String, String>> simpleData,
            String defaultStatus,
            String currentUserId,
            boolean validateOnly
    ) {
        SimpleImportResponse response = new SimpleImportResponse();

        if (simpleData == null || simpleData.size() != 3) {
            return SimpleImportResponse.error("Invalid format: expected array of 3 objects [channels, playlists, videos]");
        }

        // Validate element types
        for (int i = 0; i < 3; i++) {
            if (simpleData.get(i) != null && !(simpleData.get(i) instanceof Map)) {
                return SimpleImportResponse.error(
                    "Invalid format: element at index " + i + " must be a Map");
            }
        }

        // Validate status
        if (defaultStatus != null && !defaultStatus.isEmpty()) {
            String normalizedStatus = defaultStatus.toUpperCase();
            if (!normalizedStatus.equals("PENDING") &&
                !normalizedStatus.equals("APPROVED") &&
                !normalizedStatus.equals("REJECTED")) {
                return SimpleImportResponse.error(
                    "Invalid status: must be one of PENDING, APPROVED, or REJECTED");
            }
            // Normalize to uppercase for consistency
            defaultStatus = normalizedStatus;
        } else {
            // Default to PENDING if not specified
            defaultStatus = "PENDING";
        }

        // Extract the 3 maps
        Map<String, String> channelsMap = simpleData.get(0);
        Map<String, String> playlistsMap = simpleData.get(1);
        Map<String, String> videosMap = simpleData.get(2);

        // Import channels
        if (channelsMap != null && !channelsMap.isEmpty()) {
            importChannels(channelsMap, defaultStatus, currentUserId, validateOnly, response);
        }

        // Import playlists
        if (playlistsMap != null && !playlistsMap.isEmpty()) {
            importPlaylists(playlistsMap, defaultStatus, currentUserId, validateOnly, response);
        }

        // Import videos
        if (videosMap != null && !videosMap.isEmpty()) {
            importVideos(videosMap, defaultStatus, currentUserId, validateOnly, response);
        }

        response.setSuccess(true);
        response.setMessage(validateOnly ?
                "Validation completed" :
                "Simple format import completed");

        return response;
    }

    /**
     * Import channels from map of {youtubeId: "Title|Categories|Keywords"}
     */
    private void importChannels(
            Map<String, String> channelsMap,
            String defaultStatus,
            String currentUserId,
            boolean validateOnly,
            SimpleImportResponse response
    ) {
        for (Map.Entry<String, String> entry : channelsMap.entrySet()) {
            String youtubeId = entry.getKey();
            String value = entry.getValue();

            // Parse title, categories, and keywords from "Title|Cat1,Cat2|keyword1,keyword2" format
            String[] parts = value.split("\\|", 3);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";
            String keywordsStr = parts.length > 2 ? parts[2].trim() : "";

            try {
                // 1. Check if already exists in Firestore
                Optional<Channel> existing = channelRepository.findByYoutubeId(youtubeId);
                if (existing.isPresent()) {
                    response.addResult(SimpleImportItemResult.skipped(
                            youtubeId,
                            titleFromFile,
                            "CHANNEL",
                            "Already exists in database"
                    ));
                    continue;
                }

                // 2. Validate YouTube ID still exists and fetch metadata
                ChannelDetailsDto ytChannel = youTubeService.validateAndFetchChannelDto(youtubeId);

                if (ytChannel == null) {
                    response.addResult(SimpleImportItemResult.failed(
                            youtubeId,
                            titleFromFile,
                            "CHANNEL",
                            "YouTube channel not found or deleted (404)"
                    ));
                    continue;
                }

                // 3. Parse categories (optional)
                List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);

                // 4. Parse keywords (optional) - enrich from YouTube tags if not provided
                List<String> keywords = parseKeywords(keywordsStr);
                if ((keywords == null || keywords.isEmpty()) && ytChannel.getTags() != null && !ytChannel.getTags().isEmpty()) {
                    keywords = normalizeKeywords(ytChannel.getTags());
                    logger.debug("Enriched channel {} keywords from YouTube tags: {} (normalized)", youtubeId, keywords != null ? keywords.size() : 0);
                }

                // 5. Create channel document (if not validate-only)
                if (!validateOnly) {
                    Channel channel = new Channel(youtubeId);

                    // Set metadata from YouTube (NewPipe provides direct access)
                    channel.setName(ytChannel.getName());
                    if (ytChannel.getDescription() != null && !ytChannel.getDescription().isEmpty()) {
                        channel.setDescription(ytChannel.getDescription());
                    }

                    if (ytChannel.getThumbnailUrl() != null && !ytChannel.getThumbnailUrl().isEmpty()) {
                        channel.setThumbnailUrl(ytChannel.getThumbnailUrl());
                    }

                    if (ytChannel.getSubscriberCount() != null && ytChannel.getSubscriberCount() >= 0) {
                        channel.setSubscribers(ytChannel.getSubscriberCount());
                    }
                    // Note: Channel video count not available in ChannelInfo

                    // Set categories
                    channel.setCategoryIds(categoryIds);

                    // Set keywords (if provided)
                    if (keywords != null && !keywords.isEmpty()) {
                        channel.setKeywords(keywords);
                    }

                    // Set approval status
                    channel.setStatus(defaultStatus);
                    channel.setApproved("APPROVED".equals(defaultStatus));
                    channel.setPending("PENDING".equals(defaultStatus));

                    // Set metadata
                    channel.setSubmittedBy(currentUserId);
                    if ("APPROVED".equals(defaultStatus)) {
                        channel.setApprovedBy(currentUserId);
                    }
                    channel.setCreatedAt(Timestamp.now());
                    channel.setUpdatedAt(Timestamp.now());

                    // Save to Firestore
                    channelRepository.save(channel);

                    logger.info("Imported channel: {} ({})", channel.getName(), youtubeId);
                }

                response.addResult(SimpleImportItemResult.success(
                        youtubeId,
                        ytChannel.getName() != null ? ytChannel.getName() : titleFromFile,
                        "CHANNEL"
                ));

            } catch (InterruptedException e) {
                logger.error("Import interrupted for channel {}: {}", youtubeId, e.getMessage());
                Thread.currentThread().interrupt();
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "CHANNEL",
                        "Operation interrupted: " + e.getMessage()
                ));
                // Exit immediately after interrupt - do not continue processing
                return;
            } catch (ExecutionException e) {
                logger.error("Failed to import channel {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "CHANNEL",
                        "Database operation failed: " + e.getMessage()
                ));
            } catch (Exception e) {
                logger.error("Unexpected error importing channel {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "CHANNEL",
                        "Error: " + e.getMessage()
                ));
            }
        }
    }

    /**
     * Import playlists from map of {youtubeId: "Title|Categories|Keywords"}
     */
    private void importPlaylists(
            Map<String, String> playlistsMap,
            String defaultStatus,
            String currentUserId,
            boolean validateOnly,
            SimpleImportResponse response
    ) {
        for (Map.Entry<String, String> entry : playlistsMap.entrySet()) {
            String youtubeId = entry.getKey();
            String value = entry.getValue();

            // Parse title, categories, and keywords
            String[] parts = value.split("\\|", 3);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";
            String keywordsStr = parts.length > 2 ? parts[2].trim() : "";

            try {
                // 1. Check if already exists
                Optional<Playlist> existing = playlistRepository.findByYoutubeId(youtubeId);
                if (existing.isPresent()) {
                    response.addResult(SimpleImportItemResult.skipped(
                            youtubeId,
                            titleFromFile,
                            "PLAYLIST",
                            "Already exists in database"
                    ));
                    continue;
                }

                // 2. Validate YouTube ID and fetch metadata
                PlaylistDetailsDto ytPlaylist = youTubeService.validateAndFetchPlaylistDto(youtubeId);

                if (ytPlaylist == null) {
                    response.addResult(SimpleImportItemResult.failed(
                            youtubeId,
                            titleFromFile,
                            "PLAYLIST",
                            "YouTube playlist not found or deleted (404)"
                    ));
                    continue;
                }

                // 3. Parse categories
                List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);

                // 4. Parse keywords (optional)
                List<String> keywords = parseKeywords(keywordsStr);

                // 5. Create playlist document
                if (!validateOnly) {
                    Playlist playlist = new Playlist(youtubeId);

                    // Set metadata from YouTube (NewPipe provides direct access)
                    playlist.setTitle(ytPlaylist.getName());
                    if (ytPlaylist.getDescription() != null) {
                        playlist.setDescription(ytPlaylist.getDescription());
                    }

                    if (ytPlaylist.getThumbnailUrl() != null && !ytPlaylist.getThumbnailUrl().isEmpty()) {
                        playlist.setThumbnailUrl(ytPlaylist.getThumbnailUrl());
                    }

                    if (ytPlaylist.getStreamCount() != null && ytPlaylist.getStreamCount() >= 0) {
                        playlist.setItemCount(Math.toIntExact(ytPlaylist.getStreamCount()));
                    }

                    // Set categories
                    playlist.setCategoryIds(categoryIds);

                    // Set keywords (if provided)
                    if (keywords != null && !keywords.isEmpty()) {
                        playlist.setKeywords(keywords);
                    }

                    // Set approval status
                    playlist.setStatus(defaultStatus);

                    // Set metadata
                    playlist.setSubmittedBy(currentUserId);
                    if ("APPROVED".equals(defaultStatus)) {
                        playlist.setApprovedBy(currentUserId);
                    }
                    playlist.setCreatedAt(Timestamp.now());
                    playlist.setUpdatedAt(Timestamp.now());

                    // Save to Firestore
                    playlistRepository.save(playlist);

                    logger.info("Imported playlist: {} ({})", playlist.getTitle(), youtubeId);
                }

                response.addResult(SimpleImportItemResult.success(
                        youtubeId,
                        ytPlaylist.getName() != null ? ytPlaylist.getName() : titleFromFile,
                        "PLAYLIST"
                ));

            } catch (InterruptedException e) {
                logger.error("Import interrupted for playlist {}: {}", youtubeId, e.getMessage());
                Thread.currentThread().interrupt();
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "PLAYLIST",
                        "Operation interrupted: " + e.getMessage()
                ));
                // Exit immediately after interrupt - do not continue processing
                return;
            } catch (ExecutionException e) {
                logger.error("Failed to import playlist {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "PLAYLIST",
                        "Database operation failed: " + e.getMessage()
                ));
            } catch (Exception e) {
                logger.error("Unexpected error importing playlist {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "PLAYLIST",
                        "Error: " + e.getMessage()
                ));
            }
        }
    }

    /**
     * Import videos from map of {youtubeId: "Title|Categories|Keywords"}
     */
    private void importVideos(
            Map<String, String> videosMap,
            String defaultStatus,
            String currentUserId,
            boolean validateOnly,
            SimpleImportResponse response
    ) {
        for (Map.Entry<String, String> entry : videosMap.entrySet()) {
            String youtubeId = entry.getKey();
            String value = entry.getValue();

            // Parse title, categories, and keywords
            String[] parts = value.split("\\|", 3);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";
            String keywordsStr = parts.length > 2 ? parts[2].trim() : "";

            try {
                // 1. Check if already exists
                Optional<Video> existing = videoRepository.findByYoutubeId(youtubeId);
                if (existing.isPresent()) {
                    response.addResult(SimpleImportItemResult.skipped(
                            youtubeId,
                            titleFromFile,
                            "VIDEO",
                            "Already exists in database"
                    ));
                    continue;
                }

                // 2. Validate YouTube ID and fetch metadata
                StreamDetailsDto ytVideo = youTubeService.validateAndFetchVideoDto(youtubeId);

                if (ytVideo == null) {
                    response.addResult(SimpleImportItemResult.failed(
                            youtubeId,
                            titleFromFile,
                            "VIDEO",
                            "YouTube video not found or deleted (404)"
                    ));
                    continue;
                }

                // 3. Parse categories
                List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);

                // 4. Parse keywords (optional) - enrich from YouTube tags if not provided
                List<String> keywords = parseKeywords(keywordsStr);
                if ((keywords == null || keywords.isEmpty()) && ytVideo.getTags() != null && !ytVideo.getTags().isEmpty()) {
                    keywords = normalizeKeywords(ytVideo.getTags());
                    logger.debug("Enriched video {} keywords from YouTube tags: {} (normalized)", youtubeId, keywords != null ? keywords.size() : 0);
                }

                // 5. Create video document
                if (!validateOnly) {
                    Video video = new Video(youtubeId);

                    // Set metadata from YouTube (NewPipe provides direct access)
                    video.setTitle(ytVideo.getName());
                    if (ytVideo.getDescription() != null && !ytVideo.getDescription().isEmpty()) {
                        video.setDescription(ytVideo.getDescription());
                    }
                    video.setChannelId(YouTubeUrlUtils.extractYouTubeId(ytVideo.getUploaderUrl()));
                    video.setChannelTitle(ytVideo.getUploaderName());

                    if (ytVideo.getThumbnailUrl() != null && !ytVideo.getThumbnailUrl().isEmpty()) {
                        video.setThumbnailUrl(ytVideo.getThumbnailUrl());
                    }

                    if (ytVideo.getDuration() != null && ytVideo.getDuration() >= 0) {
                        video.setDurationSeconds(ytVideo.getDuration().intValue());
                        logger.debug("Video duration: {} seconds", ytVideo.getDuration());
                    } else {
                        video.setDurationSeconds(0);
                    }

                    if (ytVideo.getViewCount() != null && ytVideo.getViewCount() >= 0) {
                        video.setViewCount(ytVideo.getViewCount());
                    }

                    // Set categories
                    video.setCategoryIds(categoryIds);

                    // Set keywords (if provided)
                    if (keywords != null && !keywords.isEmpty()) {
                        video.setKeywords(keywords);
                    }

                    // Set approval status
                    video.setStatus(defaultStatus);

                    // Set metadata
                    video.setSubmittedBy(currentUserId);
                    if ("APPROVED".equals(defaultStatus)) {
                        video.setApprovedBy(currentUserId);
                    }
                    video.setCreatedAt(Timestamp.now());
                    video.setUpdatedAt(Timestamp.now());

                    // Save to Firestore
                    videoRepository.save(video);

                    logger.info("Imported video: {} ({})", video.getTitle(), youtubeId);
                }

                response.addResult(SimpleImportItemResult.success(
                        youtubeId,
                        ytVideo.getName() != null ? ytVideo.getName() : titleFromFile,
                        "VIDEO"
                ));

            } catch (InterruptedException e) {
                logger.error("Import interrupted for video {}: {}", youtubeId, e.getMessage());
                Thread.currentThread().interrupt();
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "VIDEO",
                        "Operation interrupted: " + e.getMessage()
                ));
                // Exit immediately after interrupt - do not continue processing
                return;
            } catch (ExecutionException e) {
                logger.error("Failed to import video {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "VIDEO",
                        "Database operation failed: " + e.getMessage()
                ));
            } catch (Exception e) {
                logger.error("Unexpected error importing video {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "VIDEO",
                        "Error: " + e.getMessage()
                ));
            }
        }
    }

    /**
     * Parse comma-separated keywords string into a list.
     * @param keywordsStr Comma-separated keywords (can be null or empty)
     * @return List of trimmed keywords, or null if input is empty/null
     */
    private List<String> parseKeywords(String keywordsStr) {
        if (keywordsStr == null || keywordsStr.isEmpty()) {
            return null;
        }
        List<String> keywords = new ArrayList<>();
        for (String keyword : keywordsStr.split(",")) {
            String trimmed = keyword.trim();
            if (!trimmed.isEmpty()) {
                keywords.add(trimmed);
            }
        }
        return keywords.isEmpty() ? null : normalizeKeywords(keywords);
    }

    /**
     * Normalize keywords list: trim, filter blanks, dedupe, truncate long keywords,
     * and enforce maximum count. Matches ContentLibraryController validation.
     *
     * @param keywords Raw keywords list (can be null)
     * @return Normalized keywords list, or null if result is empty
     */
    private List<String> normalizeKeywords(List<String> keywords) {
        if (keywords == null || keywords.isEmpty()) {
            return null;
        }

        Set<String> seen = new LinkedHashSet<>(); // Preserve order while deduping
        for (String keyword : keywords) {
            if (keyword == null) continue;
            String trimmed = keyword.trim();
            if (trimmed.isEmpty()) continue;

            // Truncate keywords that exceed max length
            if (trimmed.length() > MAX_KEYWORD_LENGTH) {
                trimmed = trimmed.substring(0, MAX_KEYWORD_LENGTH);
                logger.debug("Truncated keyword to {} chars", MAX_KEYWORD_LENGTH);
            }

            seen.add(trimmed);

            // Stop once we hit the max
            if (seen.size() >= MAX_KEYWORDS) {
                logger.debug("Keywords truncated to max {} items", MAX_KEYWORDS);
                break;
            }
        }

        return seen.isEmpty() ? null : new ArrayList<>(seen);
    }
}

