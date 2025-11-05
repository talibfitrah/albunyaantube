package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.SimpleImportItemResult;
import com.albunyaan.tube.dto.SimpleImportResponse;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.api.services.youtube.model.PlaylistSnippet;
import com.google.api.services.youtube.model.VideoSnippet;
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
 * Format: [{channelId: "Title|Cat1,Cat2"}, {playlistId: "Title|Cat1,Cat2"}, {videoId: "Title|Cat1,Cat2"}]
 *
 * Import flow per item:
 * 1. Check if YouTube ID already exists in Firestore → Skip if exists
 * 2. Validate YouTube ID still exists via YouTube API → Skip if 404
 * 3. Fetch full metadata from YouTube API (title, description, thumbnail)
 * 4. Parse comma-separated categories → Map names to IDs
 * 5. Create Firestore document with status=APPROVED by default
 * 6. Track result (success/failed/skipped with reason)
 */
@Service
public class SimpleImportService {

    private static final Logger logger = LoggerFactory.getLogger(SimpleImportService.class);

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
     * Import channels from map of {youtubeId: "Title|Categories"}
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

            // Parse title and categories from "Title|Cat1,Cat2" format
            String[] parts = value.split("\\|", 2);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

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
                com.google.api.services.youtube.model.Channel ytChannel =
                        youTubeService.validateAndFetchChannel(youtubeId);

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

                // 4. Create channel document (if not validate-only)
                if (!validateOnly) {
                    Channel channel = new Channel(youtubeId);

                    // Set metadata from YouTube
                    if (ytChannel.getSnippet() != null) {
                        channel.setName(ytChannel.getSnippet().getTitle());
                        channel.setDescription(ytChannel.getSnippet().getDescription());

                        if (ytChannel.getSnippet().getThumbnails() != null &&
                            ytChannel.getSnippet().getThumbnails().getDefault() != null) {
                            channel.setThumbnailUrl(ytChannel.getSnippet().getThumbnails().getDefault().getUrl());
                        }
                    }

                    if (ytChannel.getStatistics() != null) {
                        if (ytChannel.getStatistics().getSubscriberCount() != null) {
                            channel.setSubscribers(ytChannel.getStatistics().getSubscriberCount().longValue());
                        }
                        if (ytChannel.getStatistics().getVideoCount() != null) {
                            channel.setVideoCount(ytChannel.getStatistics().getVideoCount().intValue());
                        }
                    }

                    // Set categories
                    channel.setCategoryIds(categoryIds);

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
                        ytChannel.getSnippet() != null ? ytChannel.getSnippet().getTitle() : titleFromFile,
                        "CHANNEL"
                ));

            } catch (ExecutionException | InterruptedException e) {
                logger.error("Failed to import channel {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "CHANNEL",
                        "Database error: " + e.getMessage()
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
     * Import playlists from map of {youtubeId: "Title|Categories"}
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

            // Parse title and categories
            String[] parts = value.split("\\|", 2);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

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
                com.google.api.services.youtube.model.Playlist ytPlaylist =
                        youTubeService.validateAndFetchPlaylist(youtubeId);

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

                // 4. Create playlist document
                if (!validateOnly) {
                    Playlist playlist = new Playlist(youtubeId);

                    // Set metadata from YouTube
                    PlaylistSnippet snippet = ytPlaylist.getSnippet();
                    if (snippet != null) {
                        playlist.setTitle(snippet.getTitle());
                        playlist.setDescription(snippet.getDescription());

                        if (snippet.getThumbnails() != null &&
                            snippet.getThumbnails().getDefault() != null) {
                            playlist.setThumbnailUrl(snippet.getThumbnails().getDefault().getUrl());
                        }
                    }

                    if (ytPlaylist.getContentDetails() != null &&
                        ytPlaylist.getContentDetails().getItemCount() != null) {
                        playlist.setItemCount(ytPlaylist.getContentDetails().getItemCount().intValue());
                    }

                    // Set categories
                    playlist.setCategoryIds(categoryIds);

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
                        ytPlaylist.getSnippet() != null ? ytPlaylist.getSnippet().getTitle() : titleFromFile,
                        "PLAYLIST"
                ));

            } catch (ExecutionException | InterruptedException e) {
                logger.error("Failed to import playlist {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "PLAYLIST",
                        "Database error: " + e.getMessage()
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
     * Import videos from map of {youtubeId: "Title|Categories"}
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

            // Parse title and categories
            String[] parts = value.split("\\|", 2);
            String titleFromFile = parts[0].trim();
            String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

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
                com.google.api.services.youtube.model.Video ytVideo =
                        youTubeService.validateAndFetchVideo(youtubeId);

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

                // 4. Create video document
                if (!validateOnly) {
                    Video video = new Video(youtubeId);

                    // Set metadata from YouTube
                    VideoSnippet snippet = ytVideo.getSnippet();
                    if (snippet != null) {
                        video.setTitle(snippet.getTitle());
                        video.setDescription(snippet.getDescription());
                        video.setChannelId(snippet.getChannelId());
                        video.setChannelTitle(snippet.getChannelTitle());

                        if (snippet.getThumbnails() != null &&
                            snippet.getThumbnails().getDefault() != null) {
                            video.setThumbnailUrl(snippet.getThumbnails().getDefault().getUrl());
                        }
                    }

                    if (ytVideo.getContentDetails() != null && ytVideo.getContentDetails().getDuration() != null) {
                        // Convert ISO 8601 duration to seconds
                        String durationString = ytVideo.getContentDetails().getDuration();
                        try {
                            if (durationString != null && !durationString.trim().isEmpty()) {
                                Duration duration = Duration.parse(durationString);
                                long durationSeconds = duration.toSeconds();
                                video.setDurationSeconds((int) durationSeconds);
                                logger.debug("Parsed video duration: {} -> {} seconds", durationString, durationSeconds);
                            } else {
                                video.setDurationSeconds(0);
                            }
                        } catch (DateTimeParseException e) {
                            logger.warn("Failed to parse video duration '{}' for video {}: {}. Defaulting to 0.",
                                    durationString, youtubeId, e.getMessage());
                            video.setDurationSeconds(0);
                        }
                    } else {
                        video.setDurationSeconds(0);
                    }

                    if (ytVideo.getStatistics() != null &&
                        ytVideo.getStatistics().getViewCount() != null) {
                        video.setViewCount(ytVideo.getStatistics().getViewCount().longValue());
                    }

                    // Set categories
                    video.setCategoryIds(categoryIds);

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
                        ytVideo.getSnippet() != null ? ytVideo.getSnippet().getTitle() : titleFromFile,
                        "VIDEO"
                ));

            } catch (ExecutionException | InterruptedException e) {
                logger.error("Failed to import video {}: {}", youtubeId, e.getMessage());
                response.addResult(SimpleImportItemResult.failed(
                        youtubeId,
                        titleFromFile,
                        "VIDEO",
                        "Database error: " + e.getMessage()
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
}
