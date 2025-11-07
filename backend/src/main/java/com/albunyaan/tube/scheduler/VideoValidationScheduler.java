package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.VideoValidationService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * Video Validation Scheduler
 *
 * Automatically validates standalone videos 3 times per day to detect removed/unavailable content.
 * Runs at: 6:00 AM, 2:00 PM, and 10:00 PM server time.
 */
@Component
public class VideoValidationScheduler {

    private static final Logger logger = LoggerFactory.getLogger(VideoValidationScheduler.class);

    private final VideoValidationService videoValidationService;

    public VideoValidationScheduler(VideoValidationService videoValidationService) {
        this.videoValidationService = videoValidationService;
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
     * Common validation logic for all scheduled runs
     */
    private void runValidation(String timeOfDay) {
        try {
            ValidationRun result = videoValidationService.validateStandaloneVideos(
                    "SCHEDULED",    // triggerType
                    null,           // triggeredBy (null for scheduled)
                    "Video Validation Scheduler (" + timeOfDay + ")",  // triggeredByDisplayName
                    null            // maxVideos (use default)
            );

            logger.info("{} validation completed - Run ID: {}, Status: {}, Checked: {}, Unavailable: {}, Errors: {}",
                    timeOfDay,
                    result.getId(),
                    result.getStatus(),
                    result.getVideosChecked(),
                    result.getVideosMarkedUnavailable(),
                    result.getErrorCount()
            );

        } catch (Exception e) {
            logger.error("{} video validation failed", timeOfDay, e);
        }
    }
}

