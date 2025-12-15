package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Video Validation Scheduler
 *
 * Automatically validates videos according to configured schedule to detect removed/unavailable content.
 * Default: Once daily at 6:00 AM UTC (configurable via app.validation.video.scheduler.cron).
 *
 * Safety features:
 * - Can be disabled via app.validation.video.scheduler.enabled=false
 * - Prevents concurrent runs (only one validation can run at a time)
 * - Respects circuit breaker state (won't run if YouTube rate limiting detected)
 * - Uses conservative batch sizes (configurable via app.validation.video.max-items-per-run)
 *
 * Uses ContentValidationService which implements conservative validation logic:
 * - VALID: Video confirmed to exist on YouTube
 * - ARCHIVED: Video definitively not found on YouTube (hidden from app)
 * - ERROR: Transient error (rate limiting, network issues) - video remains visible
 */
@Component
public class VideoValidationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(VideoValidationScheduler.class);

    private final ContentValidationService contentValidationService;
    private final CacheManager cacheManager;
    private final ValidationProperties validationProperties;
    private final YouTubeCircuitBreaker circuitBreaker;

    // Prevents concurrent runs
    private final AtomicBoolean isRunning = new AtomicBoolean(false);

    public VideoValidationScheduler(
            ContentValidationService contentValidationService,
            CacheManager cacheManager,
            ValidationProperties validationProperties,
            YouTubeCircuitBreaker circuitBreaker) {
        this.contentValidationService = contentValidationService;
        this.cacheManager = cacheManager;
        this.validationProperties = validationProperties;
        this.circuitBreaker = circuitBreaker;

        logger.info("VideoValidationScheduler initialized - enabled: {}, cron: {}, maxItems: {}",
                validationProperties.getVideo().getScheduler().isEnabled(),
                validationProperties.getVideo().getScheduler().getCron(),
                validationProperties.getVideo().getMaxItemsPerRun());
    }

    /**
     * Scheduled validation run using configurable cron expression.
     * Default: "0 0 6 * * ?" (6:00 AM UTC daily)
     */
    @Scheduled(cron = "${app.validation.video.scheduler.cron:0 0 6 * * ?}", zone = "UTC")
    public void scheduledValidation() {
        logger.info("Scheduled video validation triggered");
        runValidation("scheduled");
    }

    /**
     * Common validation logic for all scheduled runs.
     *
     * Safety checks:
     * 1. Scheduler must be enabled
     * 2. Must not be currently running (prevents concurrent runs)
     * 3. Circuit breaker must not be open (rate limiting cooldown)
     *
     * Uses ContentValidationService.validateVideos() which implements conservative validation:
     * - Only marks videos as ARCHIVED when definitively not found on YouTube
     * - Treats transient errors as ERROR (video remains visible in app)
     */
    private void runValidation(String timeOfDay) {
        // Check if scheduler is enabled
        if (!validationProperties.getVideo().getScheduler().isEnabled()) {
            logger.info("Video validation scheduler is DISABLED - skipping {} run", timeOfDay);
            return;
        }

        // Check circuit breaker
        if (circuitBreaker.isOpen()) {
            YouTubeCircuitBreaker.CircuitBreakerStatus status = circuitBreaker.getStatus();
            logger.warn("Video validation skipped - circuit breaker is OPEN. " +
                        "YouTube rate limiting detected. Cooldown remaining: {} minutes. " +
                        "Last error: {} - {}",
                    status.getRemainingCooldownMs() / 60000,
                    status.getLastErrorType(),
                    status.getLastErrorMessage());
            return;
        }

        // Prevent concurrent runs
        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Video validation skipped - a previous run is still in progress");
            return;
        }

        try {
            int maxItems = validationProperties.getVideo().getMaxItemsPerRun();
            logger.info("Starting {} video validation (max items: {})", timeOfDay, maxItems);

            ValidationRun result = contentValidationService.validateVideos(
                    ValidationRun.TRIGGER_SCHEDULED,
                    null,  // triggeredBy (null for scheduled runs)
                    "Video Validation Scheduler (" + timeOfDay + ")",
                    maxItems
            );

            logger.info("{} validation completed - Run ID: {}, Status: {}, Checked: {}, Archived: {}, Errors: {}",
                    timeOfDay,
                    result.getId(),
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
                logger.warn("{} validation did not complete successfully (status: {}), skipping cache eviction",
                        timeOfDay, result.getStatus());
            }

        } catch (Exception e) {
            logger.error("{} video validation failed", timeOfDay, e);

            // Check if this was a rate limit error
            if (circuitBreaker.isRateLimitError(e)) {
                circuitBreaker.recordRateLimitError(e);
            }
        } finally {
            isRunning.set(false);
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

    /**
     * Check if a validation run is currently in progress.
     */
    public boolean isRunning() {
        return isRunning.get();
    }

    /**
     * Manually trigger a validation run (for admin use).
     * Respects the same safety checks as scheduled runs.
     *
     * @return true if validation was started, false if skipped
     */
    public boolean triggerManualRun() {
        // Check circuit breaker first (before scheduler enabled check for manual runs)
        if (circuitBreaker.isOpen()) {
            logger.warn("Manual validation rejected - circuit breaker is OPEN");
            return false;
        }

        if (!isRunning.compareAndSet(false, true)) {
            logger.warn("Manual validation rejected - a run is already in progress");
            return false;
        }

        try {
            int maxItems = validationProperties.getVideo().getMaxItemsPerRun();
            logger.info("Starting manual video validation (max items: {})", maxItems);

            ValidationRun result = contentValidationService.validateVideos(
                    ValidationRun.TRIGGER_MANUAL,
                    null,
                    "Manual Trigger",
                    maxItems
            );

            logger.info("Manual validation completed - Run ID: {}, Status: {}, Checked: {}, Archived: {}, Errors: {}",
                    result.getId(),
                    result.getStatus(),
                    result.getVideosChecked(),
                    result.getVideosMarkedArchived(),
                    result.getErrorCount()
            );

            if (ValidationRun.STATUS_COMPLETED.equals(result.getStatus())) {
                evictPublicContentCaches();
            }

            return true;
        } catch (Exception e) {
            logger.error("Manual video validation failed", e);

            if (circuitBreaker.isRateLimitError(e)) {
                circuitBreaker.recordRateLimitError(e);
            }
            return false;
        } finally {
            isRunning.set(false);
        }
    }
}
