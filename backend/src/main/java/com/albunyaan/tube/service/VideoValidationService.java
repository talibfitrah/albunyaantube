package com.albunyaan.tube.service;

import com.albunyaan.tube.model.SourceType;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
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
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.stream.Collectors;

/**
 * Video Validation Service
 *
 * Core service for validating videos against YouTube API to detect removed/unavailable content.
 * Used by both scheduled validation and manual triggers.
 */
@Service
public class VideoValidationService {

    private static final Logger logger = LoggerFactory.getLogger(VideoValidationService.class);
    private static final int DEFAULT_BATCH_SIZE = 50; // YouTube API limit
    private static final int DEFAULT_MAX_VIDEOS_PER_RUN = 500; // Prevent quota exhaustion

    private final VideoRepository videoRepository;
    private final YouTubeService youtubeService;
    private final AuditLogService auditLogService;
    private final ValidationRunRepository validationRunRepository;

    public VideoValidationService(
            VideoRepository videoRepository,
            YouTubeService youtubeService,
            AuditLogService auditLogService,
            ValidationRunRepository validationRunRepository
    ) {
        this.videoRepository = videoRepository;
        this.youtubeService = youtubeService;
        this.auditLogService = auditLogService;
        this.validationRunRepository = validationRunRepository;
    }

    /**
     * Validate standalone videos (scheduled or manual trigger)
     *
     * @param triggerType "SCHEDULED", "MANUAL", "IMPORT", "EXPORT"
     * @param triggeredBy Actor UID (null for scheduled runs)
     * @param triggeredByDisplayName Actor display name (null for scheduled runs)
     * @param maxVideos Maximum number of videos to validate (null = use default)
     * @return ValidationRun result
     */
    public ValidationRun validateStandaloneVideos(
            String triggerType,
            String triggeredBy,
            String triggeredByDisplayName,
            Integer maxVideos
    ) {
        logger.info("Starting video validation - triggerType: {}, triggeredBy: {}", triggerType, triggeredBy);

        // Create validation run record
        ValidationRun validationRun = new ValidationRun(triggerType, triggeredBy, triggeredByDisplayName);

        try {
            // Save initial run record
            validationRunRepository.save(validationRun);

            // Get videos to validate (standalone only, oldest first)
            List<Video> videosToValidate = getStandaloneVideosForValidation(maxVideos);
            logger.info("Found {} standalone videos to validate", videosToValidate.size());

            if (videosToValidate.isEmpty()) {
                validationRun.complete("COMPLETED");
                validationRun.addDetail("message", "No standalone videos found to validate");
                validationRunRepository.save(validationRun);
                return validationRun;
            }

            // Extract YouTube IDs
            List<String> youtubeIds = videosToValidate.stream()
                    .map(Video::getYoutubeId)
                    .collect(Collectors.toList());

            // Batch validate against YouTube API
            Map<String, org.schabi.newpipe.extractor.stream.StreamInfo> validVideos =
                    youtubeService.batchValidateVideos(youtubeIds);

            // Process results
            int checkedCount = 0;
            int unavailableCount = 0;
            int errorCount = 0;
            List<String> unavailableVideoIds = new ArrayList<>();

            for (Video video : videosToValidate) {
                try {
                    checkedCount++;

                    if (validVideos.containsKey(video.getYoutubeId())) {
                        // Video exists on YouTube - mark as VALID
                        video.setValidationStatus(ValidationStatus.VALID);
                        video.setLastValidatedAt(Timestamp.now());
                        videoRepository.save(video);
                        logger.debug("Video {} is valid", video.getYoutubeId());
                    } else {
                        // Video not found on YouTube - mark as UNAVAILABLE
                        video.setValidationStatus(ValidationStatus.UNAVAILABLE);
                        video.setLastValidatedAt(Timestamp.now());
                        videoRepository.save(video);

                        unavailableCount++;
                        unavailableVideoIds.add(video.getId());

                        // Create audit log for unavailable video
                        auditLogService.logSystem(
                                "video_marked_unavailable",
                                "video",
                                video.getId(),
                                triggeredByDisplayName != null ? triggeredByDisplayName : "Video Validation Scheduler"
                        );

                        logger.info("Video marked as unavailable - youtubeId: {}, title: {}",
                                video.getYoutubeId(), video.getTitle());
                    }
                } catch (Exception e) {
                    errorCount++;
                    logger.error("Error validating video {}: {}", video.getYoutubeId(), e.getMessage(), e);
                }
            }

            // Update validation run with results
            validationRun.setVideosChecked(checkedCount);
            validationRun.setVideosMarkedUnavailable(unavailableCount);
            validationRun.setErrorCount(errorCount);
            validationRun.addDetail("unavailableVideoIds", unavailableVideoIds);
            validationRun.complete("COMPLETED");

            validationRunRepository.save(validationRun);

            logger.info("Video validation completed - checked: {}, unavailable: {}, errors: {}",
                    checkedCount, unavailableCount, errorCount);

        } catch (Exception e) {
            logger.error("Video validation failed", e);
            validationRun.complete("FAILED");
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveException) {
                logger.error("Failed to save validation run after error", saveException);
            }
        }

        return validationRun;
    }

    /**
     * Get standalone videos for validation (oldest first, not yet validated or last validated > 1 day ago)
     */
    private List<Video> getStandaloneVideosForValidation(Integer maxVideos) throws ExecutionException, InterruptedException {
        // Get all approved videos
        List<Video> allVideos = videoRepository.findByStatus("APPROVED");

        // Filter for standalone videos (sourceType = STANDALONE or UNKNOWN)
        // and videos that need validation (validationStatus != UNAVAILABLE)
        // Skip videos validated within the last day
        Instant oneDayAgo = Instant.now().minus(1, ChronoUnit.DAYS);

        List<Video> standaloneVideos = allVideos.stream()
                .filter(v -> {
                    SourceType sourceType = v.getSourceType();
                    // Include STANDALONE and UNKNOWN (for backward compatibility with existing videos)
                    return sourceType == SourceType.STANDALONE || sourceType == SourceType.UNKNOWN;
                })
                .filter(v -> {
                    // Skip videos already marked as unavailable
                    if (v.getValidationStatus() == ValidationStatus.UNAVAILABLE) {
                        return false;
                    }

                    // Skip videos validated within the last day
                    Timestamp lastValidated = v.getLastValidatedAt();
                    if (lastValidated != null) {
                        Instant lastValidatedInstant = Instant.ofEpochSecond(
                            lastValidated.getSeconds(),
                            lastValidated.getNanos()
                        );
                        return lastValidatedInstant.isBefore(oneDayAgo);
                    }

                    // Include videos that have never been validated
                    return true;
                })
                .sorted(Comparator.comparing(
                        Video::getCreatedAt,
                        Comparator.nullsLast(Comparator.naturalOrder())
                ))
                .collect(Collectors.toList());

        // Apply limit
        int limit = maxVideos != null ? maxVideos : DEFAULT_MAX_VIDEOS_PER_RUN;
        if (standaloneVideos.size() > limit) {
            return standaloneVideos.subList(0, limit);
        }

        return standaloneVideos;
    }

    /**
     * Validate a specific list of videos (for import/export)
     *
     * @param videos List of videos to validate
     * @param triggerType "IMPORT" or "EXPORT"
     * @return ValidationRun result
     */
    public ValidationRun validateSpecificVideos(List<Video> videos, String triggerType) {
        logger.info("Starting specific video validation - count: {}, triggerType: {}", videos.size(), triggerType);

        ValidationRun validationRun = new ValidationRun(triggerType);

        try {
            validationRunRepository.save(validationRun);

            if (videos.isEmpty()) {
                validationRun.complete("COMPLETED");
                validationRun.addDetail("message", "No videos to validate");
                validationRunRepository.save(validationRun);
                return validationRun;
            }

            // Extract YouTube IDs
            List<String> youtubeIds = videos.stream()
                    .map(Video::getYoutubeId)
                    .collect(Collectors.toList());

            // Batch validate
            Map<String, org.schabi.newpipe.extractor.stream.StreamInfo> validVideos =
                    youtubeService.batchValidateVideos(youtubeIds);

            // Process results
            int checkedCount = 0;
            int unavailableCount = 0;

            for (Video video : videos) {
                checkedCount++;
                if (!validVideos.containsKey(video.getYoutubeId())) {
                    unavailableCount++;
                }
            }

            validationRun.setVideosChecked(checkedCount);
            validationRun.setVideosMarkedUnavailable(unavailableCount);
            validationRun.complete("COMPLETED");
            validationRunRepository.save(validationRun);

            logger.info("Specific video validation completed - checked: {}, unavailable: {}",
                    checkedCount, unavailableCount);

        } catch (Exception e) {
            logger.error("Specific video validation failed", e);
            validationRun.complete("FAILED");
            validationRun.addDetail("errorMessage", e.getMessage());
            try {
                validationRunRepository.save(validationRun);
            } catch (Exception saveException) {
                logger.error("Failed to save validation run after error", saveException);
            }
        }

        return validationRun;
    }

    /**
     * Get the latest validation run
     */
    public ValidationRun getLatestValidationRun() throws ExecutionException, InterruptedException {
        return validationRunRepository.findLatest().orElse(null);
    }

    /**
     * Get validation run by ID
     */
    public ValidationRun getValidationRunById(String id) throws ExecutionException, InterruptedException {
        return validationRunRepository.findById(id).orElse(null);
    }

    /**
     * Get validation history (last N runs)
     */
    public List<ValidationRun> getValidationHistory(int limit) throws ExecutionException, InterruptedException {
        return validationRunRepository.findAll(limit);
    }
}

