package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.ContentValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.CacheManager;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Video Validation Scheduler
 *
 * Automatically validates videos 3 times per day to detect removed/unavailable content.
 * Runs at: 6:00 AM, 2:00 PM, and 10:00 PM UTC.
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

    public VideoValidationScheduler(ContentValidationService contentValidationService, CacheManager cacheManager) {
        this.contentValidationService = contentValidationService;
        this.cacheManager = cacheManager;
    }

    /**
     * Morning validation run at 6:00 AM
     */
    @Scheduled(cron = "0 0 6 * * ?", zone = "UTC")
    public void morningValidation() {
        logger.info("Starting morning video validation at 6:00 AM UTC");
        runValidation("morning");
    }

    /**
     * Afternoon validation run at 2:00 PM
     */
    @Scheduled(cron = "0 0 14 * * ?", zone = "UTC")
    public void afternoonValidation() {
        logger.info("Starting afternoon video validation at 2:00 PM UTC");
        runValidation("afternoon");
    }

    /**
     * Evening validation run at 10:00 PM
     */
    @Scheduled(cron = "0 0 22 * * ?", zone = "UTC")
    public void eveningValidation() {
        logger.info("Starting evening video validation at 10:00 PM UTC");
        runValidation("evening");
    }

    /**
     * Common validation logic for all scheduled runs.
     * Uses ContentValidationService.validateVideos() which implements conservative validation:
     * - Only marks videos as ARCHIVED when definitively not found on YouTube
     * - Treats transient errors as ERROR (video remains visible in app)
     */
    private void runValidation(String timeOfDay) {
        try {
            ValidationRun result = contentValidationService.validateVideos(
                    ValidationRun.TRIGGER_SCHEDULED,
                    null,  // triggeredBy (null for scheduled runs)
                    "Video Validation Scheduler (" + timeOfDay + ")",
                    null   // maxItems (use default)
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

