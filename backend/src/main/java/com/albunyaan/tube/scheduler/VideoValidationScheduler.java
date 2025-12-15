package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.config.ValidationSchedulerProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.SchedulerLockService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.UUID;

/**
 * Video Validation Scheduler
 *
 * Automatically validates videos on a configurable schedule to detect removed/unavailable content.
 *
 * Safety features (YouTube rate limit prevention):
 * - Enable/disable via configuration without code deploy
 * - Distributed lock prevents concurrent runs across multiple instances
 * - Configurable max items per run (default: 10, conservative)
 * - Configurable cron schedule (default: once daily at 6 AM UTC)
 *
 * Uses ContentValidationService which implements conservative validation logic:
 * - VALID: Video confirmed to exist on YouTube
 * - ARCHIVED: Video definitively not found on YouTube (hidden from app)
 * - ERROR: Transient error (rate limiting, network issues) - video remains visible
 *
 * @see ValidationSchedulerProperties for configuration options
 * @see SchedulerLockService for distributed locking
 */
@Component
public class VideoValidationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(VideoValidationScheduler.class);

    private final ContentValidationService contentValidationService;
    private final CacheManager cacheManager;
    private final ValidationSchedulerProperties schedulerProperties;
    private final SchedulerLockService lockService;

    public VideoValidationScheduler(
            ContentValidationService contentValidationService,
            CacheManager cacheManager,
            ValidationSchedulerProperties schedulerProperties,
            SchedulerLockService lockService
    ) {
        this.contentValidationService = contentValidationService;
        this.cacheManager = cacheManager;
        this.schedulerProperties = schedulerProperties;
        this.lockService = lockService;

        logger.info("VideoValidationScheduler initialized - enabled: {}, cron: '{}', maxItems: {}, lockTTL: {}min",
                schedulerProperties.isEnabled(),
                schedulerProperties.getCron(),
                schedulerProperties.getMaxItemsPerRun(),
                schedulerProperties.getLockTtlMinutes());
    }

    /**
     * Scheduled validation run.
     * Uses cron expression from configuration (default: "0 0 6 * * ?" = 6 AM UTC daily).
     *
     * Note: The cron expression is evaluated from the property, but Spring requires
     * a compile-time constant. We use a SpEL expression to read from properties.
     */
    @Scheduled(cron = "${app.validation.video.scheduler.cron:0 0 6 * * ?}", zone = "UTC")
    public void scheduledValidation() {
        // Check if scheduler is enabled
        if (!schedulerProperties.isEnabled()) {
            logger.info("Video validation scheduler is disabled, skipping run");
            return;
        }

        String runId = UUID.randomUUID().toString();
        logger.info("Scheduled video validation triggered (runId: {})", runId);

        // Try to acquire distributed lock
        if (!lockService.tryAcquireLock(runId)) {
            logger.warn("Could not acquire scheduler lock (another instance may be running), skipping this run");
            return;
        }

        try {
            runValidation(runId);
        } finally {
            // Always release lock, even on failure
            lockService.releaseLock(runId);
        }
    }

    /**
     * Common validation logic.
     * Uses ContentValidationService.validateVideos() which implements conservative validation:
     * - Only marks videos as ARCHIVED when definitively not found on YouTube
     * - Treats transient errors as ERROR (video remains visible in app)
     */
    private void runValidation(String runId) {
        try {
            int maxItems = schedulerProperties.getMaxItemsPerRun();

            // maxItems=0 is a backout lever - skip validation entirely
            if (maxItems <= 0) {
                logger.info("max-items-per-run is 0 (backout lever active), skipping validation - runId: {}", runId);
                return;
            }

            logger.info("Starting video validation - runId: {}, maxItems: {}", runId, maxItems);

            ValidationRun result = contentValidationService.validateVideos(
                    ValidationRun.TRIGGER_SCHEDULED,
                    null,  // triggeredBy (null for scheduled runs)
                    "Video Validation Scheduler",
                    maxItems
            );

            logger.info("Validation completed - runId: {}, status: {}, checked: {}, archived: {}, errors: {}",
                    runId,
                    result.getStatus(),
                    result.getVideosChecked(),
                    result.getVideosMarkedArchived(),
                    result.getErrorCount()
            );

            // Only evict caches when validation completed successfully.
            // If validation failed, we don't want to clear caches unnecessarily.
            if (ValidationRun.STATUS_COMPLETED.equals(result.getStatus())) {
                evictPublicContentCaches();
            } else {
                logger.warn("Validation did not complete successfully (status: {}), skipping cache eviction",
                        result.getStatus());
            }

        } catch (Exception e) {
            logger.error("Video validation failed - runId: {}", runId, e);
        }
    }

    /**
     * Evict public content caches after validation runs.
     * This ensures the mobile app doesn't serve stale cached data that might
     * include videos that were just archived.
     */
    private void evictPublicContentCaches() {
        try {
            var publicContentCache = cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT);
            if (publicContentCache != null) {
                publicContentCache.clear();
                logger.debug("Evicted {} cache after validation", CacheConfig.CACHE_PUBLIC_CONTENT);
            }

            var videosCache = cacheManager.getCache(CacheConfig.CACHE_VIDEOS);
            if (videosCache != null) {
                videosCache.clear();
                logger.debug("Evicted {} cache after validation", CacheConfig.CACHE_VIDEOS);
            }
        } catch (Exception e) {
            logger.warn("Failed to evict caches after validation: {}", e.getMessage());
        }
    }
}
