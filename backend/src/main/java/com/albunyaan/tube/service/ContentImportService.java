package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.util.YouTubeUrlUtils;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Content Import Service (Async)
 *
 * Imports content from JSON files with YouTube validation.
 * REUSES existing validation infrastructure (ValidationRun, YouTubeService.batchValidate*).
 *
 * Import flow:
 * 1. Check which items already exist (skipped)
 * 2. Batch validate new items via YouTubeService (validationFailed if not found)
 * 3. Import valid items to Firestore (failed if Firestore error)
 * 4. Track all results in ValidationRun with detailed reason counts
 */
@Service
public class ContentImportService {

    private static final Logger logger = LoggerFactory.getLogger(ContentImportService.class);
    private static final int BATCH_SIZE = 500;
    /**
     * Save progress to Firestore every N items.
     * Set to 1 for real-time per-item progress updates for smooth UI experience.
     * With 500ms polling interval, this provides fluid progress bar animation.
     */
    private static final int PROGRESS_SAVE_INTERVAL = 1;

    private final YouTubeService youtubeService; // REUSE existing validation logic
    private final CategoryMappingService categoryMappingService;
    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final ValidationRunRepository validationRunRepository;

    public ContentImportService(
            YouTubeService youtubeService,
            CategoryMappingService categoryMappingService,
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            ValidationRunRepository validationRunRepository
    ) {
        this.youtubeService = youtubeService;
        this.categoryMappingService = categoryMappingService;
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.validationRunRepository = validationRunRepository;
    }

    /**
     * Import content from simple format asynchronously.
     * Updates ValidationRun with progress and results.
     *
     * @param run ValidationRun to track progress (must be saved before calling)
     * @param simpleData Array of 3 maps: channels, playlists, videos
     * @param defaultStatus Status to set for imported items (APPROVED or PENDING)
     * @param currentUserId Firebase UID of user performing import
     */
    public void importSimpleFormatAsync(
            ValidationRun run,
            List<Map<String, String>> simpleData,
            String defaultStatus,
            String currentUserId
    ) {
        logger.info("Starting async import - runId: {}, status: {}, user: {}",
                run.getId(), defaultStatus, currentUserId);

        // Reason tracking maps
        Map<String, Integer> reasonCounts = new HashMap<>();
        List<String> failedItemIds = new ArrayList<>();

        try {
            // Extract the 3 maps
            Map<String, String> channelsMap = simpleData.get(0);
            Map<String, String> playlistsMap = simpleData.get(1);
            Map<String, String> videosMap = simpleData.get(2);

            // Set totals for progress tracking
            run.setCurrentPhase("INITIALIZING");
            run.setTotalChannelsToCheck(channelsMap != null ? channelsMap.size() : 0);
            run.setTotalPlaylistsToCheck(playlistsMap != null ? playlistsMap.size() : 0);
            run.setTotalVideosToCheck(videosMap != null ? videosMap.size() : 0);
            validationRunRepository.save(run);

            logger.info("Import run {} initialized - channels: {}, playlists: {}, videos: {} (total: {})",
                    run.getId(),
                    run.getTotalChannelsToCheck(),
                    run.getTotalPlaylistsToCheck(),
                    run.getTotalVideosToCheck(),
                    run.getTotalToCheck());

            // Import channels
            if (channelsMap != null && !channelsMap.isEmpty()) {
                run.setCurrentPhase("CHANNELS");
                validationRunRepository.save(run);
                processChannels(run, channelsMap, defaultStatus, currentUserId, reasonCounts, failedItemIds);
            }

            // Import playlists
            if (playlistsMap != null && !playlistsMap.isEmpty()) {
                run.setCurrentPhase("PLAYLISTS");
                validationRunRepository.save(run);
                processPlaylists(run, playlistsMap, defaultStatus, currentUserId, reasonCounts, failedItemIds);
            }

            // Import videos
            if (videosMap != null && !videosMap.isEmpty()) {
                run.setCurrentPhase("VIDEOS");
                validationRunRepository.save(run);
                processVideos(run, videosMap, defaultStatus, currentUserId, reasonCounts, failedItemIds);
            }

            // Mark complete
            run.setCurrentPhase("COMPLETE");
            run.complete(ValidationRun.STATUS_COMPLETED);
            run.addDetail("reasonCounts", reasonCounts);
            run.addDetail("failedItemIds", failedItemIds);
            validationRunRepository.save(run);

            logger.info("Import run {} completed - imported: {}, skipped: {}, validationFailed: {}, failed: {}",
                    run.getId(),
                    run.getTotalImported(),
                    run.getTotalSkipped(),
                    run.getTotalValidationFailed(),
                    run.getTotalFailed());

        } catch (Exception e) {
            logger.error("Import run {} failed", run.getId(), e);
            run.complete(ValidationRun.STATUS_FAILED);
            run.addDetail("errorMessage", e.getMessage());
            run.addDetail("reasonCounts", reasonCounts);
            run.addDetail("failedItemIds", failedItemIds);
            try {
                validationRunRepository.save(run);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }
    }

    /**
     * Process channels: check existing, validate new, import valid.
     */
    private void processChannels(
            ValidationRun run,
            Map<String, String> channelsMap,
            String defaultStatus,
            String currentUserId,
            Map<String, Integer> reasonCounts,
            List<String> failedItemIds
    ) throws ExecutionException, InterruptedException, TimeoutException {
        List<String> youtubeIds = new ArrayList<>(channelsMap.keySet());

        // Process in batches
        for (int i = 0; i < youtubeIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, youtubeIds.size());
            List<String> batch = youtubeIds.subList(i, end);

            // Check which already exist in Firestore (query one by one)
            List<String> existingIds = new ArrayList<>();
            for (String id : batch) {
                try {
                    if (channelRepository.findByYoutubeId(id).isPresent()) {
                        existingIds.add(id);
                    }
                } catch (Exception e) {
                    logger.error("Error checking channel existence {}: {}", id, e.getMessage());
                }
            }

            // Mark existing as skipped
            for (String existingId : existingIds) {
                run.incrementChannelsChecked(); // Count as checked
                run.incrementChannelsSkipped();
                incrementReasonCount(reasonCounts, "CHANNEL_ALREADY_EXISTS");

                // Save progress periodically
                if (run.getChannelsChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                    validationRunRepository.save(run);
                }
            }

            // Get new IDs to validate
            List<String> newIds = batch.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            if (newIds.isEmpty()) {
                continue;
            }

            // REUSE existing YouTubeService validation logic
            BatchValidationResult<ChannelDetailsDto> validationResult =
                    youtubeService.batchValidateChannelsDtoWithDetails(newIds);

            // Import valid channels
            for (Map.Entry<String, ChannelDetailsDto> entry : validationResult.getValid().entrySet()) {
                String youtubeId = entry.getKey();
                ChannelDetailsDto dto = entry.getValue();

                try {
                    String value = channelsMap.get(youtubeId);
                    String[] parts = value.split("\\|", 2);
                    String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

                    // Create channel (following SimpleImportService pattern)
                    Channel channel = new Channel(youtubeId);
                    channel.setName(dto.getName());
                    if (dto.getDescription() != null && !dto.getDescription().isEmpty()) {
                        channel.setDescription(dto.getDescription());
                    }
                    if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isEmpty()) {
                        channel.setThumbnailUrl(dto.getThumbnailUrl());
                    }
                    if (dto.getSubscriberCount() != null && dto.getSubscriberCount() >= 0) {
                        channel.setSubscribers(dto.getSubscriberCount());
                    }

                    // Set categories
                    List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);
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

                    channelRepository.save(channel);

                    run.incrementChannelsChecked(); // Count as checked
                    run.incrementChannelsImported();
                    incrementReasonCount(reasonCounts, "CHANNEL_IMPORTED_SUCCESS");

                    // Save progress periodically
                    if (run.getChannelsChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                        validationRunRepository.save(run);
                    }
                } catch (Exception e) {
                    logger.error("Failed to import channel {}: {}", youtubeId, e.getMessage());
                    run.incrementChannelsChecked(); // Count as checked even if save failed
                    run.incrementChannelsFailed();
                    incrementReasonCount(reasonCounts, "CHANNEL_FIRESTORE_ERROR: " + e.getMessage());
                    failedItemIds.add("channel:" + youtubeId);
                }
            }

            // Mark not found as validation failed
            for (String notFoundId : validationResult.getNotFound()) {
                run.incrementChannelsChecked(); // Count as checked
                run.incrementChannelsValidationFailed();
                incrementReasonCount(reasonCounts, "CHANNEL_NOT_FOUND_ON_YOUTUBE");
                failedItemIds.add("channel:" + notFoundId);
            }

            // Mark errors as validation failed
            for (String errorId : validationResult.getErrors()) {
                String errorMsg = validationResult.getErrorMessages().get(errorId);
                run.incrementChannelsChecked(); // Count as checked
                run.incrementChannelsValidationFailed();
                incrementReasonCount(reasonCounts, "CHANNEL_YOUTUBE_ERROR: " + errorMsg);
                failedItemIds.add("channel:" + errorId);
            }

            // Save after each batch
            validationRunRepository.save(run);
        }
    }

    /**
     * Process playlists: check existing, validate new, import valid.
     */
    private void processPlaylists(
            ValidationRun run,
            Map<String, String> playlistsMap,
            String defaultStatus,
            String currentUserId,
            Map<String, Integer> reasonCounts,
            List<String> failedItemIds
    ) throws ExecutionException, InterruptedException, TimeoutException {
        List<String> youtubeIds = new ArrayList<>(playlistsMap.keySet());

        for (int i = 0; i < youtubeIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, youtubeIds.size());
            List<String> batch = youtubeIds.subList(i, end);

            // Check existing
            List<String> existingIds = new ArrayList<>();
            for (String id : batch) {
                try {
                    if (playlistRepository.findByYoutubeId(id).isPresent()) {
                        existingIds.add(id);
                    }
                } catch (Exception e) {
                    logger.error("Error checking playlist existence {}: {}", id, e.getMessage());
                }
            }

            for (String existingId : existingIds) {
                run.incrementPlaylistsChecked(); // Count as checked
                run.incrementPlaylistsSkipped();
                incrementReasonCount(reasonCounts, "PLAYLIST_ALREADY_EXISTS");

                if (run.getPlaylistsChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                    validationRunRepository.save(run);
                }
            }

            List<String> newIds = batch.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            if (newIds.isEmpty()) {
                continue;
            }

            // REUSE existing validation logic
            BatchValidationResult<PlaylistDetailsDto> validationResult =
                    youtubeService.batchValidatePlaylistsDtoWithDetails(newIds);

            // Import valid playlists
            for (Map.Entry<String, PlaylistDetailsDto> entry : validationResult.getValid().entrySet()) {
                String youtubeId = entry.getKey();
                PlaylistDetailsDto dto = entry.getValue();

                try {
                    String value = playlistsMap.get(youtubeId);
                    String[] parts = value.split("\\|", 2);
                    String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

                    // Create playlist (following SimpleImportService pattern)
                    Playlist playlist = new Playlist(youtubeId);
                    playlist.setTitle(dto.getName());
                    if (dto.getDescription() != null) {
                        playlist.setDescription(dto.getDescription());
                    }
                    if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isEmpty()) {
                        playlist.setThumbnailUrl(dto.getThumbnailUrl());
                    }
                    if (dto.getStreamCount() != null && dto.getStreamCount() >= 0) {
                        playlist.setItemCount(Math.toIntExact(dto.getStreamCount()));
                    }

                    // Set categories
                    List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);
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

                    playlistRepository.save(playlist);

                    run.incrementPlaylistsChecked(); // Count as checked
                    run.incrementPlaylistsImported();
                    incrementReasonCount(reasonCounts, "PLAYLIST_IMPORTED_SUCCESS");

                    if (run.getPlaylistsChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                        validationRunRepository.save(run);
                    }
                } catch (Exception e) {
                    logger.error("Failed to import playlist {}: {}", youtubeId, e.getMessage());
                    run.incrementPlaylistsChecked(); // Count as checked even if save failed
                    run.incrementPlaylistsFailed();
                    incrementReasonCount(reasonCounts, "PLAYLIST_FIRESTORE_ERROR: " + e.getMessage());
                    failedItemIds.add("playlist:" + youtubeId);
                }
            }

            for (String notFoundId : validationResult.getNotFound()) {
                run.incrementPlaylistsChecked(); // Count as checked
                run.incrementPlaylistsValidationFailed();
                incrementReasonCount(reasonCounts, "PLAYLIST_NOT_FOUND_ON_YOUTUBE");
                failedItemIds.add("playlist:" + notFoundId);
            }

            for (String errorId : validationResult.getErrors()) {
                String errorMsg = validationResult.getErrorMessages().get(errorId);
                run.incrementPlaylistsChecked(); // Count as checked
                run.incrementPlaylistsValidationFailed();
                incrementReasonCount(reasonCounts, "PLAYLIST_YOUTUBE_ERROR: " + errorMsg);
                failedItemIds.add("playlist:" + errorId);
            }

            validationRunRepository.save(run);
        }
    }

    /**
     * Process videos: check existing, validate new, import valid.
     */
    private void processVideos(
            ValidationRun run,
            Map<String, String> videosMap,
            String defaultStatus,
            String currentUserId,
            Map<String, Integer> reasonCounts,
            List<String> failedItemIds
    ) throws ExecutionException, InterruptedException, TimeoutException {
        List<String> youtubeIds = new ArrayList<>(videosMap.keySet());

        for (int i = 0; i < youtubeIds.size(); i += BATCH_SIZE) {
            int end = Math.min(i + BATCH_SIZE, youtubeIds.size());
            List<String> batch = youtubeIds.subList(i, end);

            // Check existing
            List<String> existingIds = new ArrayList<>();
            for (String id : batch) {
                try {
                    if (videoRepository.findByYoutubeId(id).isPresent()) {
                        existingIds.add(id);
                    }
                } catch (Exception e) {
                    logger.error("Error checking video existence {}: {}", id, e.getMessage());
                }
            }

            for (String existingId : existingIds) {
                run.incrementVideosChecked(); // Count as checked
                run.incrementVideosSkipped();
                incrementReasonCount(reasonCounts, "VIDEO_ALREADY_EXISTS");

                if (run.getVideosChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                    validationRunRepository.save(run);
                }
            }

            List<String> newIds = batch.stream()
                    .filter(id -> !existingIds.contains(id))
                    .collect(Collectors.toList());

            if (newIds.isEmpty()) {
                continue;
            }

            // REUSE existing validation logic
            BatchValidationResult<StreamDetailsDto> validationResult =
                    youtubeService.batchValidateVideosDtoWithDetails(newIds);

            // Import valid videos
            for (Map.Entry<String, StreamDetailsDto> entry : validationResult.getValid().entrySet()) {
                String youtubeId = entry.getKey();
                StreamDetailsDto dto = entry.getValue();

                try {
                    String value = videosMap.get(youtubeId);
                    String[] parts = value.split("\\|", 2);
                    String categoriesStr = parts.length > 1 ? parts[1].trim() : "";

                    // Create video (following SimpleImportService pattern)
                    Video video = new Video(youtubeId);
                    video.setTitle(dto.getName());
                    if (dto.getDescription() != null && !dto.getDescription().isEmpty()) {
                        video.setDescription(dto.getDescription());
                    }
                    video.setChannelId(YouTubeUrlUtils.extractYouTubeId(dto.getUploaderUrl()));
                    video.setChannelTitle(dto.getUploaderName());

                    if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isEmpty()) {
                        video.setThumbnailUrl(dto.getThumbnailUrl());
                    }

                    if (dto.getDuration() != null && dto.getDuration() >= 0) {
                        video.setDurationSeconds(dto.getDuration().intValue());
                    } else {
                        video.setDurationSeconds(0);
                    }

                    if (dto.getViewCount() != null && dto.getViewCount() >= 0) {
                        video.setViewCount(dto.getViewCount());
                    }

                    // Set categories
                    List<String> categoryIds = categoryMappingService.mapCategoryNamesToIds(categoriesStr);
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

                    videoRepository.save(video);

                    run.incrementVideosChecked(); // Count as checked
                    run.incrementVideosImported();
                    incrementReasonCount(reasonCounts, "VIDEO_IMPORTED_SUCCESS");

                    if (run.getVideosChecked() % PROGRESS_SAVE_INTERVAL == 0) {
                        validationRunRepository.save(run);
                    }
                } catch (Exception e) {
                    logger.error("Failed to import video {}: {}", youtubeId, e.getMessage());
                    run.incrementVideosChecked(); // Count as checked even if save failed
                    run.incrementVideosFailed();
                    incrementReasonCount(reasonCounts, "VIDEO_FIRESTORE_ERROR: " + e.getMessage());
                    failedItemIds.add("video:" + youtubeId);
                }
            }

            for (String notFoundId : validationResult.getNotFound()) {
                run.incrementVideosChecked(); // Count as checked
                run.incrementVideosValidationFailed();
                incrementReasonCount(reasonCounts, "VIDEO_NOT_FOUND_ON_YOUTUBE");
                failedItemIds.add("video:" + notFoundId);
            }

            for (String errorId : validationResult.getErrors()) {
                String errorMsg = validationResult.getErrorMessages().get(errorId);
                run.incrementVideosChecked(); // Count as checked
                run.incrementVideosValidationFailed();
                incrementReasonCount(reasonCounts, "VIDEO_YOUTUBE_ERROR: " + errorMsg);
                failedItemIds.add("video:" + errorId);
            }

            validationRunRepository.save(run);
        }
    }

    private void incrementReasonCount(Map<String, Integer> reasonCounts, String reason) {
        reasonCounts.put(reason, reasonCounts.getOrDefault(reason, 0) + 1);
    }
}
