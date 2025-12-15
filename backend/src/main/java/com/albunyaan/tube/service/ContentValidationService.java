package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.google.cloud.Timestamp;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;

/**
 * Content Validation Service
 *
 * Unified service for validating channels, playlists, and videos against YouTube
 * to detect removed/unavailable content. Auto-archives unavailable content.
 */
@Service
public class ContentValidationService {

    private static final Logger logger = LoggerFactory.getLogger(ContentValidationService.class);

    private final ChannelRepository channelRepository;
    private final PlaylistRepository playlistRepository;
    private final VideoRepository videoRepository;
    private final YouTubeService youtubeService;
    private final AuditLogService auditLogService;
    private final ValidationRunRepository validationRunRepository;
    private final ValidationProperties validationProperties;

    public ContentValidationService(
            ChannelRepository channelRepository,
            PlaylistRepository playlistRepository,
            VideoRepository videoRepository,
            YouTubeService youtubeService,
            AuditLogService auditLogService,
            ValidationRunRepository validationRunRepository,
            ValidationProperties validationProperties
    ) {
        this.channelRepository = channelRepository;
        this.playlistRepository = playlistRepository;
        this.videoRepository = videoRepository;
        this.youtubeService = youtubeService;
        this.auditLogService = auditLogService;
        this.validationRunRepository = validationRunRepository;
        this.validationProperties = validationProperties;
    }

    // ==================== Validation Triggers ====================

    /**
     * Validate all content types (channels, playlists, videos).
     * Auto-archives unavailable content.
     */
    public ValidationRun validateAllContent(
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxItems
    ) {
        logger.info("Starting full content validation - triggerType: {}, triggeredBy: {}", triggerType, triggeredBy);

        ValidationRun validationRun = new ValidationRun(triggerType, triggeredBy, triggeredByDisplayName);

        try {
            // Use sensible default limit to prevent OOM/timeout (500 total items = ~167 per type)
            int limit = maxItems != null ? maxItems : validationProperties.getVideo().getMaxItemsPerRun();
            int perTypeLimit = (int) Math.ceil(limit / 3.0);

            // Calculate totals upfront for progress tracking
            validationRun.setCurrentPhase("INITIALIZING");
            List<Channel> channelsToValidate = getChannelsForValidation(perTypeLimit);
            List<Playlist> playlistsToValidate = getPlaylistsForValidation(perTypeLimit);
            List<Video> videosToValidate = getVideosForValidation(perTypeLimit);

            validationRun.setTotalChannelsToCheck(channelsToValidate.size());
            validationRun.setTotalPlaylistsToCheck(playlistsToValidate.size());
            validationRun.setTotalVideosToCheck(videosToValidate.size());

            // Save initial state with totals
            validationRunRepository.save(validationRun);

            logger.info("Validation run {} started - channels: {}, playlists: {}, videos: {} (total: {})",
                    validationRun.getId(),
                    channelsToValidate.size(),
                    playlistsToValidate.size(),
                    videosToValidate.size(),
                    validationRun.getTotalToCheck());

            // Validate channels
            validationRun.setCurrentPhase("CHANNELS");
            validationRunRepository.save(validationRun);
            validateChannelsInternalWithItems(validationRun, triggeredByDisplayName, channelsToValidate);

            // Validate playlists
            validationRun.setCurrentPhase("PLAYLISTS");
            validationRunRepository.save(validationRun);
            validatePlaylistsInternalWithItems(validationRun, triggeredByDisplayName, playlistsToValidate);

            // Validate videos
            validationRun.setCurrentPhase("VIDEOS");
            validationRunRepository.save(validationRun);
            validateVideosInternalWithItems(validationRun, triggeredByDisplayName, videosToValidate);

            validationRun.setCurrentPhase("COMPLETE");
            validationRun.complete(ValidationRun.STATUS_COMPLETED);
            validationRunRepository.save(validationRun);

            logger.info("Full content validation completed - channels: {}/{} archived, playlists: {}/{} archived, videos: {}/{} archived",
                    validationRun.getChannelsMarkedArchived(), validationRun.getChannelsChecked(),
                    validationRun.getPlaylistsMarkedArchived(), validationRun.getPlaylistsChecked(),
                    validationRun.getVideosMarkedArchived(), validationRun.getVideosChecked());

        } catch (Exception e) {
            logger.error("Full content validation failed", e);
            validationRun.complete(ValidationRun.STATUS_FAILED);
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }

        return validationRun;
    }

    /**
     * Get a validation run by ID.
     */
    public ValidationRun getValidationRunById(String runId) throws ExecutionException, InterruptedException, TimeoutException {
        return validationRunRepository.findById(runId).orElse(null);
    }

    /**
     * Save a validation run (used to create initial run before async execution).
     */
    public ValidationRun saveValidationRun(ValidationRun run) throws ExecutionException, InterruptedException, TimeoutException {
        return validationRunRepository.save(run);
    }

    /**
     * Validate all content types asynchronously (continues an existing run).
     * This is called from an async thread after the initial run has been created.
     */
    public void validateAllContentAsync(
            String runId,
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxItems
    ) {
        logger.info("Starting async content validation - runId: {}, triggerType: {}, triggeredBy: {}",
                runId, triggerType, triggeredBy);

        ValidationRun validationRun;
        try {
            validationRun = validationRunRepository.findById(runId).orElse(null);
            if (validationRun == null) {
                logger.error("Validation run not found: {}", runId);
                return;
            }
        } catch (Exception e) {
            logger.error("Failed to load validation run {}", runId, e);
            return;
        }

        try {
            // Use sensible default limit to prevent OOM/timeout (500 total items = ~167 per type)
            int limit = maxItems != null ? maxItems : validationProperties.getVideo().getMaxItemsPerRun();
            int perTypeLimit = (int) Math.ceil(limit / 3.0);

            // Calculate totals upfront for progress tracking
            validationRun.setCurrentPhase("INITIALIZING");
            List<Channel> channelsToValidate = getChannelsForValidation(perTypeLimit);
            List<Playlist> playlistsToValidate = getPlaylistsForValidation(perTypeLimit);
            List<Video> videosToValidate = getVideosForValidation(perTypeLimit);

            validationRun.setTotalChannelsToCheck(channelsToValidate.size());
            validationRun.setTotalPlaylistsToCheck(playlistsToValidate.size());
            validationRun.setTotalVideosToCheck(videosToValidate.size());

            // Save with totals
            validationRunRepository.save(validationRun);

            logger.info("Validation run {} - channels: {}, playlists: {}, videos: {} (total: {})",
                    runId,
                    channelsToValidate.size(),
                    playlistsToValidate.size(),
                    videosToValidate.size(),
                    validationRun.getTotalToCheck());

            // Validate channels
            validationRun.setCurrentPhase("CHANNELS");
            validationRunRepository.save(validationRun);
            validateChannelsInternalCore(validationRun, triggeredByDisplayName, channelsToValidate);

            // Validate playlists
            validationRun.setCurrentPhase("PLAYLISTS");
            validationRunRepository.save(validationRun);
            validatePlaylistsInternalCore(validationRun, triggeredByDisplayName, playlistsToValidate);

            // Validate videos
            validationRun.setCurrentPhase("VIDEOS");
            validationRunRepository.save(validationRun);
            validateVideosInternalCore(validationRun, triggeredByDisplayName, videosToValidate);

            validationRun.setCurrentPhase("COMPLETE");
            validationRun.complete(ValidationRun.STATUS_COMPLETED);
            validationRunRepository.save(validationRun);

            logger.info("Async validation completed - runId: {}, channels: {}/{} archived, playlists: {}/{} archived, videos: {}/{} archived",
                    runId,
                    validationRun.getChannelsMarkedArchived(), validationRun.getChannelsChecked(),
                    validationRun.getPlaylistsMarkedArchived(), validationRun.getPlaylistsChecked(),
                    validationRun.getVideosMarkedArchived(), validationRun.getVideosChecked());

        } catch (Exception e) {
            logger.error("Async content validation failed - runId: {}", runId, e);
            validationRun.complete(ValidationRun.STATUS_FAILED);
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }
    }

    /**
     * Validate channels only. Auto-archives unavailable channels.
     */
    public ValidationRun validateChannels(
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxItems
    ) {
        logger.info("Starting channel validation - triggerType: {}, triggeredBy: {}", triggerType, triggeredBy);

        ValidationRun validationRun = new ValidationRun(triggerType, triggeredBy, triggeredByDisplayName);

        try {
            validationRunRepository.save(validationRun);

            int limit = maxItems != null ? maxItems : validationProperties.getVideo().getMaxItemsPerRun();
            validateChannelsInternal(validationRun, triggeredByDisplayName, limit);

            validationRun.complete(ValidationRun.STATUS_COMPLETED);
            validationRunRepository.save(validationRun);

            logger.info("Channel validation completed - checked: {}, archived: {}",
                    validationRun.getChannelsChecked(), validationRun.getChannelsMarkedArchived());

        } catch (Exception e) {
            logger.error("Channel validation failed", e);
            validationRun.complete(ValidationRun.STATUS_FAILED);
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }

        return validationRun;
    }

    /**
     * Validate playlists only. Auto-archives unavailable playlists.
     */
    public ValidationRun validatePlaylists(
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxItems
    ) {
        logger.info("Starting playlist validation - triggerType: {}, triggeredBy: {}", triggerType, triggeredBy);

        ValidationRun validationRun = new ValidationRun(triggerType, triggeredBy, triggeredByDisplayName);

        try {
            validationRunRepository.save(validationRun);

            int limit = maxItems != null ? maxItems : validationProperties.getVideo().getMaxItemsPerRun();
            validatePlaylistsInternal(validationRun, triggeredByDisplayName, limit);

            validationRun.complete(ValidationRun.STATUS_COMPLETED);
            validationRunRepository.save(validationRun);

            logger.info("Playlist validation completed - checked: {}, archived: {}",
                    validationRun.getPlaylistsChecked(), validationRun.getPlaylistsMarkedArchived());

        } catch (Exception e) {
            logger.error("Playlist validation failed", e);
            validationRun.complete(ValidationRun.STATUS_FAILED);
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }

        return validationRun;
    }

    /**
     * Validate videos only. Auto-archives unavailable videos.
     */
    public ValidationRun validateVideos(
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxItems
    ) {
        logger.info("Starting video validation - triggerType: {}, triggeredBy: {}", triggerType, triggeredBy);

        ValidationRun validationRun = new ValidationRun(triggerType, triggeredBy, triggeredByDisplayName);

        try {
            validationRunRepository.save(validationRun);

            int limit = maxItems != null ? maxItems : validationProperties.getVideo().getMaxItemsPerRun();
            validateVideosInternal(validationRun, triggeredByDisplayName, limit);

            validationRun.complete(ValidationRun.STATUS_COMPLETED);
            validationRunRepository.save(validationRun);

            logger.info("Video validation completed - checked: {}, archived: {}",
                    validationRun.getVideosChecked(), validationRun.getVideosMarkedArchived());

        } catch (Exception e) {
            logger.error("Video validation failed", e);
            validationRun.complete(ValidationRun.STATUS_FAILED);
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveEx) {
                logger.error("Failed to save validation run after error", saveEx);
            }
        }

        return validationRun;
    }

    // ==================== Internal Validation Methods ====================

    /**
     * Internal method for channel validation with pre-fetched items (used by validateAllContent for progress tracking).
     */
    private void validateChannelsInternalWithItems(ValidationRun run, String actorName, List<Channel> channelsToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {
        validateChannelsInternalCore(run, actorName, channelsToValidate);
    }

    /**
     * Internal method for playlist validation with pre-fetched items (used by validateAllContent for progress tracking).
     */
    private void validatePlaylistsInternalWithItems(ValidationRun run, String actorName, List<Playlist> playlistsToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {
        validatePlaylistsInternalCore(run, actorName, playlistsToValidate);
    }

    /**
     * Internal method for video validation with pre-fetched items (used by validateAllContent for progress tracking).
     */
    private void validateVideosInternalWithItems(ValidationRun run, String actorName, List<Video> videosToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {
        validateVideosInternalCore(run, actorName, videosToValidate);
    }

    private void validateChannelsInternal(ValidationRun run, String actorName, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<Channel> channelsToValidate = getChannelsForValidation(limit);
        validateChannelsInternalCore(run, actorName, channelsToValidate);
    }

    private void validateChannelsInternalCore(ValidationRun run, String actorName, List<Channel> channelsToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {

        logger.info("Found {} channels to validate", channelsToValidate.size());

        if (channelsToValidate.isEmpty()) {
            run.addDetail("channelMessage", "No channels found to validate");
            return;
        }

        List<String> youtubeIds = channelsToValidate.stream()
                .map(Channel::getYoutubeId)
                .collect(Collectors.toList());

        // Use the new method that properly distinguishes between "not found" and "error"
        BatchValidationResult<ChannelDetailsDto> validationResult =
                youtubeService.batchValidateChannelsDtoWithDetails(youtubeIds);
        List<String> archivedChannelIds = new ArrayList<>();
        List<String> errorChannelIds = new ArrayList<>();

        for (Channel channel : channelsToValidate) {
            try {
                run.incrementChannelsChecked();
                String youtubeId = channel.getYoutubeId();

                if (validationResult.isValid(youtubeId)) {
                    // Channel exists on YouTube - mark as VALID and refresh metadata
                    channel.setValidationStatus(ValidationStatus.VALID);
                    channel.setLastValidatedAt(Timestamp.now());

                    // Refresh cached metadata from YouTube
                    ChannelDetailsDto dto = validationResult.getContent(youtubeId);
                    if (dto != null) {
                        refreshChannelMetadata(channel, dto);
                    }

                    channelRepository.save(channel);
                    logger.debug("Channel validated as VALID - youtubeId: {}", youtubeId);

                } else if (validationResult.isNotFound(youtubeId)) {
                    // Channel DEFINITIVELY doesn't exist on YouTube - auto-archive
                    channel.setValidationStatus(ValidationStatus.ARCHIVED);
                    channel.setLastValidatedAt(Timestamp.now());
                    channelRepository.save(channel);

                    run.incrementChannelsArchived();
                    archivedChannelIds.add(channel.getId());

                    auditLogService.logSystem(
                            "channel_auto_archived",
                            "channel",
                            channel.getId(),
                            actorName != null ? actorName : "Content Validation"
                    );

                    logger.info("Channel auto-archived (confirmed not on YouTube) - youtubeId: {}, name: {}",
                            youtubeId, channel.getName());

                } else if (validationResult.isError(youtubeId)) {
                    // Transient error - DO NOT archive, mark as ERROR for retry later
                    channel.setValidationStatus(ValidationStatus.ERROR);
                    channel.setLastValidatedAt(Timestamp.now());
                    channelRepository.save(channel);

                    run.incrementError();
                    errorChannelIds.add(channel.getId());

                    String errorMsg = validationResult.getErrorMessage(youtubeId);
                    logger.warn("Channel validation error (will retry later) - youtubeId: {}, error: {}",
                            youtubeId, errorMsg);
                }

                // Save progress periodically (every 10 items)
                if (run.getChannelsChecked() % 10 == 0) {
                    validationRunRepository.save(run);
                }
            } catch (Exception e) {
                run.incrementError();
                logger.error("Error processing channel validation {}: {}", channel.getYoutubeId(), e.getMessage());
            }
        }

        // Final save after channels phase
        validationRunRepository.save(run);

        if (!archivedChannelIds.isEmpty()) {
            run.addDetail("archivedChannelIds", archivedChannelIds);
        }
        if (!errorChannelIds.isEmpty()) {
            run.addDetail("errorChannelIds", errorChannelIds);
        }
    }

    private void validatePlaylistsInternal(ValidationRun run, String actorName, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<Playlist> playlistsToValidate = getPlaylistsForValidation(limit);
        validatePlaylistsInternalCore(run, actorName, playlistsToValidate);
    }

    private void validatePlaylistsInternalCore(ValidationRun run, String actorName, List<Playlist> playlistsToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {

        logger.info("Found {} playlists to validate", playlistsToValidate.size());

        if (playlistsToValidate.isEmpty()) {
            run.addDetail("playlistMessage", "No playlists found to validate");
            return;
        }

        List<String> youtubeIds = playlistsToValidate.stream()
                .map(Playlist::getYoutubeId)
                .collect(Collectors.toList());

        // Use the new method that properly distinguishes between "not found" and "error"
        BatchValidationResult<PlaylistDetailsDto> validationResult =
                youtubeService.batchValidatePlaylistsDtoWithDetails(youtubeIds);
        List<String> archivedPlaylistIds = new ArrayList<>();
        List<String> errorPlaylistIds = new ArrayList<>();

        for (Playlist playlist : playlistsToValidate) {
            try {
                run.incrementPlaylistsChecked();
                String youtubeId = playlist.getYoutubeId();

                if (validationResult.isValid(youtubeId)) {
                    // Playlist exists on YouTube - mark as VALID and refresh metadata
                    playlist.setValidationStatus(ValidationStatus.VALID);
                    playlist.setLastValidatedAt(Timestamp.now());

                    // Refresh cached metadata from YouTube
                    PlaylistDetailsDto dto = validationResult.getContent(youtubeId);
                    if (dto != null) {
                        refreshPlaylistMetadata(playlist, dto);
                    }

                    playlistRepository.save(playlist);
                    logger.debug("Playlist validated as VALID - youtubeId: {}", youtubeId);

                } else if (validationResult.isNotFound(youtubeId)) {
                    // Playlist DEFINITIVELY doesn't exist on YouTube - auto-archive
                    playlist.setValidationStatus(ValidationStatus.ARCHIVED);
                    playlist.setLastValidatedAt(Timestamp.now());
                    playlistRepository.save(playlist);

                    run.incrementPlaylistsArchived();
                    archivedPlaylistIds.add(playlist.getId());

                    auditLogService.logSystem(
                            "playlist_auto_archived",
                            "playlist",
                            playlist.getId(),
                            actorName != null ? actorName : "Content Validation"
                    );

                    logger.info("Playlist auto-archived (confirmed not on YouTube) - youtubeId: {}, title: {}",
                            youtubeId, playlist.getTitle());

                } else if (validationResult.isError(youtubeId)) {
                    // Transient error - DO NOT archive, mark as ERROR for retry later
                    playlist.setValidationStatus(ValidationStatus.ERROR);
                    playlist.setLastValidatedAt(Timestamp.now());
                    playlistRepository.save(playlist);

                    run.incrementError();
                    errorPlaylistIds.add(playlist.getId());

                    String errorMsg = validationResult.getErrorMessage(youtubeId);
                    logger.warn("Playlist validation error (will retry later) - youtubeId: {}, error: {}",
                            youtubeId, errorMsg);
                }

                // Save progress periodically (every 10 items)
                if (run.getPlaylistsChecked() % 10 == 0) {
                    validationRunRepository.save(run);
                }
            } catch (Exception e) {
                run.incrementError();
                logger.error("Error processing playlist validation {}: {}", playlist.getYoutubeId(), e.getMessage());
            }
        }

        // Final save after playlists phase
        validationRunRepository.save(run);

        if (!archivedPlaylistIds.isEmpty()) {
            run.addDetail("archivedPlaylistIds", archivedPlaylistIds);
        }
        if (!errorPlaylistIds.isEmpty()) {
            run.addDetail("errorPlaylistIds", errorPlaylistIds);
        }
    }

    private void validateVideosInternal(ValidationRun run, String actorName, int limit)
            throws ExecutionException, InterruptedException, TimeoutException {

        List<Video> videosToValidate = getVideosForValidation(limit);
        validateVideosInternalCore(run, actorName, videosToValidate);
    }

    private void validateVideosInternalCore(ValidationRun run, String actorName, List<Video> videosToValidate)
            throws ExecutionException, InterruptedException, TimeoutException {

        logger.info("Found {} videos to validate", videosToValidate.size());

        if (videosToValidate.isEmpty()) {
            run.addDetail("videoMessage", "No videos found to validate");
            return;
        }

        List<String> youtubeIds = videosToValidate.stream()
                .map(Video::getYoutubeId)
                .collect(Collectors.toList());

        // Use the new method that properly distinguishes between "not found" and "error"
        BatchValidationResult<StreamDetailsDto> validationResult =
                youtubeService.batchValidateVideosDtoWithDetails(youtubeIds);
        List<String> archivedVideoIds = new ArrayList<>();
        List<String> errorVideoIds = new ArrayList<>();

        for (Video video : videosToValidate) {
            try {
                run.incrementVideosChecked();
                String youtubeId = video.getYoutubeId();

                if (validationResult.isValid(youtubeId)) {
                    // Video exists on YouTube - mark as VALID and refresh metadata
                    video.setValidationStatus(ValidationStatus.VALID);
                    video.setLastValidatedAt(Timestamp.now());

                    // Refresh cached metadata from YouTube
                    StreamDetailsDto dto = validationResult.getContent(youtubeId);
                    if (dto != null) {
                        refreshVideoMetadata(video, dto);
                    }

                    videoRepository.save(video);
                    logger.debug("Video validated as VALID - youtubeId: {}", youtubeId);

                } else if (validationResult.isNotFound(youtubeId)) {
                    // Video DEFINITIVELY doesn't exist on YouTube - auto-archive
                    video.setValidationStatus(ValidationStatus.ARCHIVED);
                    video.setLastValidatedAt(Timestamp.now());
                    videoRepository.save(video);

                    run.incrementVideosArchived();
                    archivedVideoIds.add(video.getId());

                    auditLogService.logSystem(
                            "video_auto_archived",
                            "video",
                            video.getId(),
                            actorName != null ? actorName : "Content Validation"
                    );

                    logger.info("Video auto-archived (confirmed not on YouTube) - youtubeId: {}, title: {}",
                            youtubeId, video.getTitle());

                } else if (validationResult.isError(youtubeId)) {
                    // Transient error - DO NOT archive, mark as ERROR for retry later
                    video.setValidationStatus(ValidationStatus.ERROR);
                    video.setLastValidatedAt(Timestamp.now());
                    videoRepository.save(video);

                    run.incrementError();
                    errorVideoIds.add(video.getId());

                    String errorMsg = validationResult.getErrorMessage(youtubeId);
                    logger.warn("Video validation error (will retry later) - youtubeId: {}, error: {}",
                            youtubeId, errorMsg);
                }

                // Save progress periodically (every 10 items)
                if (run.getVideosChecked() % 10 == 0) {
                    validationRunRepository.save(run);
                }
            } catch (Exception e) {
                run.incrementError();
                logger.error("Error processing video validation {}: {}", video.getYoutubeId(), e.getMessage());
            }
        }

        // Final save after videos phase
        validationRunRepository.save(run);

        if (!archivedVideoIds.isEmpty()) {
            run.addDetail("archivedVideoIds", archivedVideoIds);
        }
        if (!errorVideoIds.isEmpty()) {
            run.addDetail("errorVideoIds", errorVideoIds);
        }
    }

    // ==================== Get Content for Validation ====================

    private List<Channel> getChannelsForValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        // Fetch with Firestore-level limit to avoid loading entire collection
        // Multiply by 2 to account for filtering (some may be recently validated)
        List<Channel> allChannels = fetchApprovedChannels(limit * 2);
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        List<Channel> result = allChannels.stream()
                .filter(c -> {
                    ValidationStatus status = c.getValidationStatus();

                    // Skip permanently archived channels
                    if (status == ValidationStatus.ARCHIVED || status == ValidationStatus.UNAVAILABLE) {
                        return false;
                    }

                    Timestamp lastValidated = c.getLastValidatedAt();

                    // Always include if never validated (status is null)
                    if (status == null || lastValidated == null) {
                        return true;
                    }

                    Instant lastValidatedInstant = Instant.ofEpochSecond(
                            lastValidated.getSeconds(),
                            lastValidated.getNanos()
                    );

                    // Include VALID items if validated more than 24h ago (routine re-check)
                    // Include ERROR items if validated more than 24h ago (retry after cooling off)
                    return lastValidatedInstant.isBefore(oneDayAgo);
                })
                .sorted(Comparator.comparing(
                        Channel::getLastValidatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .limit(limit)
                .collect(Collectors.toList());

        // Log breakdown for debugging
        long neverValidated = result.stream().filter(c -> c.getValidationStatus() == null).count();
        long errorRetry = result.stream().filter(c -> c.getValidationStatus() == ValidationStatus.ERROR).count();
        long validRecheck = result.stream().filter(c -> c.getValidationStatus() == ValidationStatus.VALID).count();
        logger.debug("Channels to validate: {} total (neverValidated={}, errorRetry={}, validRecheck={})",
                result.size(), neverValidated, errorRetry, validRecheck);

        return result;
    }

    private List<Playlist> getPlaylistsForValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        // Fetch with Firestore-level limit to avoid loading entire collection
        // Multiply by 2 to account for filtering (some may be recently validated)
        List<Playlist> allPlaylists = fetchApprovedPlaylists(limit * 2);
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        List<Playlist> result = allPlaylists.stream()
                .filter(p -> {
                    ValidationStatus status = p.getValidationStatus();

                    // Skip permanently archived playlists
                    if (status == ValidationStatus.ARCHIVED || status == ValidationStatus.UNAVAILABLE) {
                        return false;
                    }

                    Timestamp lastValidated = p.getLastValidatedAt();

                    // Always include if never validated (status is null)
                    if (status == null || lastValidated == null) {
                        return true;
                    }

                    Instant lastValidatedInstant = Instant.ofEpochSecond(
                            lastValidated.getSeconds(),
                            lastValidated.getNanos()
                    );

                    // Include VALID items if validated more than 24h ago (routine re-check)
                    // Include ERROR items if validated more than 24h ago (retry after cooling off)
                    return lastValidatedInstant.isBefore(oneDayAgo);
                })
                .sorted(Comparator.comparing(
                        Playlist::getLastValidatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .limit(limit)
                .collect(Collectors.toList());

        // Log breakdown for debugging
        long neverValidated = result.stream().filter(p -> p.getValidationStatus() == null).count();
        long errorRetry = result.stream().filter(p -> p.getValidationStatus() == ValidationStatus.ERROR).count();
        long validRecheck = result.stream().filter(p -> p.getValidationStatus() == ValidationStatus.VALID).count();
        logger.debug("Playlists to validate: {} total (neverValidated={}, errorRetry={}, validRecheck={})",
                result.size(), neverValidated, errorRetry, validRecheck);

        return result;
    }

    private List<Video> getVideosForValidation(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        // Fetch with Firestore-level limit to avoid loading entire collection
        // Multiply by 2 to account for filtering (some may be recently validated)
        List<Video> allVideos = fetchApprovedVideos(limit * 2);
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        List<Video> result = allVideos.stream()
                .filter(v -> {
                    ValidationStatus status = v.getValidationStatus();

                    // Skip permanently archived videos
                    if (status == ValidationStatus.ARCHIVED || status == ValidationStatus.UNAVAILABLE) {
                        return false;
                    }

                    Timestamp lastValidated = v.getLastValidatedAt();

                    // Always include if never validated (status is null)
                    if (status == null || lastValidated == null) {
                        return true;
                    }

                    Instant lastValidatedInstant = Instant.ofEpochSecond(
                            lastValidated.getSeconds(),
                            lastValidated.getNanos()
                    );

                    // Include VALID items if validated more than 24h ago (routine re-check)
                    // Include ERROR items if validated more than 24h ago (retry after cooling off)
                    return lastValidatedInstant.isBefore(oneDayAgo);
                })
                .sorted(Comparator.comparing(
                        Video::getLastValidatedAt,
                        Comparator.nullsFirst(Comparator.naturalOrder())
                ))
                .limit(limit)
                .collect(Collectors.toList());

        // Log breakdown for debugging
        long neverValidated = result.stream().filter(v -> v.getValidationStatus() == null).count();
        long errorRetry = result.stream().filter(v -> v.getValidationStatus() == ValidationStatus.ERROR).count();
        long validRecheck = result.stream().filter(v -> v.getValidationStatus() == ValidationStatus.VALID).count();
        logger.debug("Videos to validate: {} total (neverValidated={}, errorRetry={}, validRecheck={})",
                result.size(), neverValidated, errorRetry, validRecheck);

        return result;
    }

    // ==================== Get Archived Content ====================

    /**
     * Get all archived/unavailable content (channels, playlists, videos).
     * Includes both ARCHIVED (new validation) and UNAVAILABLE (legacy video validation).
     */
    public List<Object> getArchivedContent(String contentType) throws ExecutionException, InterruptedException, TimeoutException {
        List<Object> results = new ArrayList<>();

        if (contentType == null || "channel".equalsIgnoreCase(contentType)) {
            results.addAll(channelRepository.findByValidationStatus(ValidationStatus.ARCHIVED));
            results.addAll(channelRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE));
        }
        if (contentType == null || "playlist".equalsIgnoreCase(contentType)) {
            results.addAll(playlistRepository.findByValidationStatus(ValidationStatus.ARCHIVED));
            results.addAll(playlistRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE));
        }
        if (contentType == null || "video".equalsIgnoreCase(contentType)) {
            results.addAll(videoRepository.findByValidationStatus(ValidationStatus.ARCHIVED));
            results.addAll(videoRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE));
        }

        return results;
    }

    /**
     * Get archived/unavailable channels.
     * Includes both ARCHIVED and UNAVAILABLE statuses.
     */
    public List<Channel> getArchivedChannels() throws ExecutionException, InterruptedException, TimeoutException {
        return getArchivedChannels(500); // Default limit
    }

    /**
     * Get archived/unavailable channels with limit.
     * Includes both ARCHIVED and UNAVAILABLE statuses.
     * Limit is applied at Firestore query level for efficiency.
     * Fetches from ARCHIVED first, then fills remainder from UNAVAILABLE.
     */
    public List<Channel> getArchivedChannels(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Channel> result = new ArrayList<>();

        // First fetch ARCHIVED items up to limit
        List<Channel> archived = channelRepository.findByValidationStatus(ValidationStatus.ARCHIVED, limit);
        result.addAll(archived);

        // If we haven't reached the limit, fetch remainder from UNAVAILABLE
        if (result.size() < limit) {
            int remaining = limit - result.size();
            List<Channel> unavailable = channelRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE, remaining);
            result.addAll(unavailable);
        }

        return result;
    }

    /**
     * Get archived/unavailable playlists.
     * Includes both ARCHIVED and UNAVAILABLE statuses.
     */
    public List<Playlist> getArchivedPlaylists() throws ExecutionException, InterruptedException, TimeoutException {
        return getArchivedPlaylists(500); // Default limit
    }

    /**
     * Get archived/unavailable playlists with limit.
     * Includes both ARCHIVED and UNAVAILABLE statuses.
     * Limit is applied at Firestore query level for efficiency.
     * Fetches from ARCHIVED first, then fills remainder from UNAVAILABLE.
     */
    public List<Playlist> getArchivedPlaylists(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Playlist> result = new ArrayList<>();

        // First fetch ARCHIVED items up to limit
        List<Playlist> archived = playlistRepository.findByValidationStatus(ValidationStatus.ARCHIVED, limit);
        result.addAll(archived);

        // If we haven't reached the limit, fetch remainder from UNAVAILABLE
        if (result.size() < limit) {
            int remaining = limit - result.size();
            List<Playlist> unavailable = playlistRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE, remaining);
            result.addAll(unavailable);
        }

        return result;
    }

    /**
     * Get archived/unavailable videos.
     * Includes both ARCHIVED (new validation) and UNAVAILABLE (legacy video validation).
     */
    public List<Video> getArchivedVideos() throws ExecutionException, InterruptedException, TimeoutException {
        return getArchivedVideos(500); // Default limit
    }

    /**
     * Get archived/unavailable videos with limit.
     * Includes both ARCHIVED (new validation) and UNAVAILABLE (legacy video validation).
     * Limit is applied at Firestore query level for efficiency.
     * Fetches from ARCHIVED first, then fills remainder from UNAVAILABLE.
     */
    public List<Video> getArchivedVideos(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Video> result = new ArrayList<>();

        // First fetch ARCHIVED items up to limit
        List<Video> archived = videoRepository.findByValidationStatus(ValidationStatus.ARCHIVED, limit);
        result.addAll(archived);

        // If we haven't reached the limit, fetch remainder from UNAVAILABLE
        if (result.size() < limit) {
            int remaining = limit - result.size();
            List<Video> unavailable = videoRepository.findByValidationStatus(ValidationStatus.UNAVAILABLE, remaining);
            result.addAll(unavailable);
        }

        return result;
    }

    /**
     * Get counts of archived/unavailable content by type.
     * Includes both ARCHIVED and UNAVAILABLE statuses.
     */
    public ArchivedCounts getArchivedCounts() throws ExecutionException, InterruptedException, TimeoutException {
        long channels = channelRepository.countByValidationStatus(ValidationStatus.ARCHIVED)
                + channelRepository.countByValidationStatus(ValidationStatus.UNAVAILABLE);
        long playlists = playlistRepository.countByValidationStatus(ValidationStatus.ARCHIVED)
                + playlistRepository.countByValidationStatus(ValidationStatus.UNAVAILABLE);
        long videos = videoRepository.countByValidationStatus(ValidationStatus.ARCHIVED)
                + videoRepository.countByValidationStatus(ValidationStatus.UNAVAILABLE);
        return new ArchivedCounts(channels, playlists, videos);
    }

    // ==================== Actions ====================

    /**
     * Permanently delete archived content.
     */
    public BulkActionResult deleteContent(String contentType, List<String> ids, String actorUid, String actorName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (String id : ids) {
            try {
                switch (contentType.toLowerCase()) {
                    case "channel":
                        channelRepository.deleteById(id);
                        auditLogService.logSystem("channel_deleted", "channel", id, actorName);
                        break;
                    case "playlist":
                        playlistRepository.deleteById(id);
                        auditLogService.logSystem("playlist_deleted", "playlist", id, actorName);
                        break;
                    case "video":
                        videoRepository.deleteById(id);
                        auditLogService.logSystem("video_deleted", "video", id, actorName);
                        break;
                    default:
                        errors.add("Unknown content type: " + contentType);
                        continue;
                }
                successCount++;
            } catch (Exception e) {
                errors.add("Failed to delete " + contentType + " " + id + ": " + e.getMessage());
                logger.error("Failed to delete {} {}", contentType, id, e);
            }
        }

        return new BulkActionResult(successCount, ids.size() - successCount, errors);
    }

    /**
     * Restore archived content to VALID status.
     */
    public BulkActionResult restoreContent(String contentType, List<String> ids, String actorUid, String actorName) {
        int successCount = 0;
        List<String> errors = new ArrayList<>();

        for (String id : ids) {
            try {
                boolean restored = false;
                switch (contentType.toLowerCase()) {
                    case "channel":
                        Channel channel = channelRepository.findById(id).orElse(null);
                        if (channel != null) {
                            channel.setValidationStatus(ValidationStatus.VALID);
                            channel.setLastValidatedAt(Timestamp.now());
                            channelRepository.save(channel);
                            auditLogService.logSystem("channel_restored", "channel", id, actorName);
                            restored = true;
                        } else {
                            errors.add("Channel not found: " + id);
                        }
                        break;
                    case "playlist":
                        Playlist playlist = playlistRepository.findById(id).orElse(null);
                        if (playlist != null) {
                            playlist.setValidationStatus(ValidationStatus.VALID);
                            playlist.setLastValidatedAt(Timestamp.now());
                            playlistRepository.save(playlist);
                            auditLogService.logSystem("playlist_restored", "playlist", id, actorName);
                            restored = true;
                        } else {
                            errors.add("Playlist not found: " + id);
                        }
                        break;
                    case "video":
                        Video video = videoRepository.findById(id).orElse(null);
                        if (video != null) {
                            video.setValidationStatus(ValidationStatus.VALID);
                            video.setLastValidatedAt(Timestamp.now());
                            videoRepository.save(video);
                            auditLogService.logSystem("video_restored", "video", id, actorName);
                            restored = true;
                        } else {
                            errors.add("Video not found: " + id);
                        }
                        break;
                    default:
                        errors.add("Unknown content type: " + contentType);
                        continue;
                }
                if (restored) {
                    successCount++;
                }
            } catch (Exception e) {
                errors.add("Failed to restore " + contentType + " " + id + ": " + e.getMessage());
                logger.error("Failed to restore {} {}", contentType, id, e);
            }
        }

        return new BulkActionResult(successCount, ids.size() - successCount, errors);
    }

    // ==================== Validation History ====================

    /**
     * Get validation run history.
     */
    public List<ValidationRun> getValidationHistory(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        return validationRunRepository.findAll(limit);
    }

    /**
     * Get the latest validation run.
     */
    public ValidationRun getLatestValidationRun() throws ExecutionException, InterruptedException, TimeoutException {
        return validationRunRepository.findLatest().orElse(null);
    }

    // ==================== Metadata Refresh Helpers ====================

    /**
     * Refresh cached metadata on a Channel from YouTube data.
     * Only updates non-null fields from the DTO to preserve existing data.
     */
    private void refreshChannelMetadata(Channel channel, ChannelDetailsDto dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) {
            channel.setName(dto.getName());
        }
        if (dto.getDescription() != null) {
            channel.setDescription(dto.getDescription());
        }
        if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isBlank()) {
            channel.setThumbnailUrl(dto.getThumbnailUrl());
        }
        if (dto.getSubscriberCount() != null && dto.getSubscriberCount() >= 0) {
            channel.setSubscribers(dto.getSubscriberCount());
        }
        if (dto.getStreamCount() != null && dto.getStreamCount() >= 0) {
            channel.setVideoCount(dto.getStreamCount().intValue());
        }
        logger.trace("Refreshed metadata for channel {}: name={}, subscribers={}, videoCount={}",
                channel.getYoutubeId(), channel.getName(), channel.getSubscribers(), channel.getVideoCount());
    }

    /**
     * Refresh cached metadata on a Playlist from YouTube data.
     * Only updates non-null fields from the DTO to preserve existing data.
     */
    private void refreshPlaylistMetadata(Playlist playlist, PlaylistDetailsDto dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) {
            playlist.setTitle(dto.getName());
        }
        if (dto.getDescription() != null) {
            playlist.setDescription(dto.getDescription());
        }
        if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isBlank()) {
            playlist.setThumbnailUrl(dto.getThumbnailUrl());
        }
        if (dto.getStreamCount() != null && dto.getStreamCount() >= 0) {
            playlist.setItemCount(dto.getStreamCount().intValue());
        }
        logger.trace("Refreshed metadata for playlist {}: title={}, itemCount={}",
                playlist.getYoutubeId(), playlist.getTitle(), playlist.getItemCount());
    }

    /**
     * Refresh cached metadata on a Video from YouTube data.
     * Only updates non-null fields from the DTO to preserve existing data.
     */
    private void refreshVideoMetadata(Video video, StreamDetailsDto dto) {
        if (dto.getName() != null && !dto.getName().isBlank()) {
            video.setTitle(dto.getName());
        }
        if (dto.getDescription() != null) {
            video.setDescription(dto.getDescription());
        }
        if (dto.getThumbnailUrl() != null && !dto.getThumbnailUrl().isBlank()) {
            video.setThumbnailUrl(dto.getThumbnailUrl());
        }
        if (dto.getViewCount() != null && dto.getViewCount() >= 0) {
            video.setViewCount(dto.getViewCount());
        }
        if (dto.getDuration() != null && dto.getDuration() >= 0) {
            video.setDurationSeconds(dto.getDuration().intValue());
        }
        if (dto.getUploaderName() != null && !dto.getUploaderName().isBlank()) {
            video.setChannelTitle(dto.getUploaderName());
        }
        logger.trace("Refreshed metadata for video {}: title={}, viewCount={}, duration={}",
                video.getYoutubeId(), video.getTitle(), video.getViewCount(), video.getDurationSeconds());
    }

    /**
     * Fetch approved channels with Firestore-level limit, tolerating legacy lowercase status values.
     * Uses limit parameter to avoid loading entire collection into memory.
     * Orders by lastValidatedAt ascending to prevent validation starvation - items that haven't
     * been validated recently are prioritized over recently validated items.
     */
    private List<Channel> fetchApprovedChannels(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Channel> merged = new ArrayList<>();
        // Split limit between cases to ensure we get enough from each
        int perCaseLimit = limit;  // Fetch full limit from each, dedupe will reduce
        // Order by lastValidatedAt ASC to prioritize oldest-validated items (prevents starvation)
        merged.addAll(channelRepository.findByStatusOrderByLastValidatedAtAsc("APPROVED", perCaseLimit));
        try {
            merged.addAll(channelRepository.findByStatusOrderByLastValidatedAtAsc("approved", perCaseLimit));
        } catch (Exception e) {
            logger.debug("Lowercase channel status query failed (can be ignored if not present): {}", e.getMessage());
        }
        return deduplicateChannels(merged);
    }

    /**
     * Fetch approved videos with Firestore-level limit, tolerating legacy lowercase status values.
     * Uses limit parameter to avoid loading entire collection into memory.
     * Orders by lastValidatedAt ascending to prevent validation starvation - items that haven't
     * been validated recently are prioritized over recently validated items.
     */
    private List<Video> fetchApprovedVideos(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Video> merged = new ArrayList<>();
        int perCaseLimit = limit;
        // Order by lastValidatedAt ASC to prioritize oldest-validated items (prevents starvation)
        merged.addAll(videoRepository.findByStatusOrderByLastValidatedAtAsc("APPROVED", perCaseLimit));
        try {
            merged.addAll(videoRepository.findByStatusOrderByLastValidatedAtAsc("approved", perCaseLimit));
        } catch (Exception e) {
            logger.debug("Lowercase video status query failed (can be ignored if not present): {}", e.getMessage());
        }
        return deduplicateVideos(merged);
    }

    /**
     * Fetch approved playlists with Firestore-level limit, tolerating both uppercase and lowercase status values.
     * Uses limit parameter to avoid loading entire collection into memory.
     * Orders by lastValidatedAt ascending to prevent validation starvation - items that haven't
     * been validated recently are prioritized over recently validated items.
     */
    private List<Playlist> fetchApprovedPlaylists(int limit) throws ExecutionException, InterruptedException, TimeoutException {
        List<Playlist> merged = new ArrayList<>();
        int perCaseLimit = limit;
        // Query both cases - playlists historically used lowercase, but controllers may set uppercase
        // Order by lastValidatedAt ASC to prioritize oldest-validated items (prevents starvation)
        merged.addAll(playlistRepository.findByStatusOrderByLastValidatedAtAsc("approved", perCaseLimit));
        try {
            merged.addAll(playlistRepository.findByStatusOrderByLastValidatedAtAsc("APPROVED", perCaseLimit));
        } catch (Exception e) {
            logger.debug("Uppercase playlist status query failed (can be ignored if not present): {}", e.getMessage());
        }
        return deduplicatePlaylists(merged);
    }

    private List<Channel> deduplicateChannels(List<Channel> channels) {
        var map = new java.util.LinkedHashMap<String, Channel>();
        for (Channel c : channels) {
            String key = c.getId() != null ? c.getId() : c.getYoutubeId();
            if (key != null) {
                map.put(key, c);
            }
        }
        return new ArrayList<>(map.values());
    }

    private List<Video> deduplicateVideos(List<Video> videos) {
        var map = new java.util.LinkedHashMap<String, Video>();
        for (Video v : videos) {
            String key = v.getId() != null ? v.getId() : v.getYoutubeId();
            if (key != null) {
                map.put(key, v);
            }
        }
        return new ArrayList<>(map.values());
    }

    private List<Playlist> deduplicatePlaylists(List<Playlist> playlists) {
        var map = new java.util.LinkedHashMap<String, Playlist>();
        for (Playlist p : playlists) {
            String key = p.getId() != null ? p.getId() : p.getYoutubeId();
            if (key != null) {
                map.put(key, p);
            }
        }
        return new ArrayList<>(map.values());
    }

    // ==================== Result Classes ====================

    public static class ArchivedCounts {
        private final long channels;
        private final long playlists;
        private final long videos;

        public ArchivedCounts(long channels, long playlists, long videos) {
            this.channels = channels;
            this.playlists = playlists;
            this.videos = videos;
        }

        public long getChannels() { return channels; }
        public long getPlaylists() { return playlists; }
        public long getVideos() { return videos; }
        public long getTotal() { return channels + playlists + videos; }
    }

    public static class BulkActionResult {
        private final int successCount;
        private final int failedCount;
        private final List<String> errors;

        public BulkActionResult(int successCount, int failedCount, List<String> errors) {
            this.successCount = successCount;
            this.failedCount = failedCount;
            this.errors = errors;
        }

        public int getSuccessCount() { return successCount; }
        public int getFailedCount() { return failedCount; }
        public List<String> getErrors() { return errors; }
    }
}
