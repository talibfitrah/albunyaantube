package com.albunyaan.tube.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for video validation scheduling.
 *
 * Provides safety controls to prevent YouTube rate limiting:
 * - Enable/disable scheduler without code deploy
 * - Configurable cron schedule
 * - Distributed lock TTL for preventing concurrent runs
 * - Maximum items per validation run
 *
 * @see com.albunyaan.tube.scheduler.VideoValidationScheduler
 */
@Configuration
@ConfigurationProperties(prefix = "app.validation.video.scheduler")
@Validated
public class ValidationSchedulerProperties {

    /**
     * Enable or disable the video validation scheduler.
     * When false, scheduled validations will not run (but manual validations still work).
     */
    private boolean enabled = true;

    /**
     * Cron expression for validation schedule (UTC timezone).
     * Default: Once daily at 6:00 AM UTC.
     */
    private String cron = "0 0 6 * * ?";

    /**
     * TTL in minutes for the distributed lock that prevents concurrent runs.
     * Must be longer than the maximum expected validation run duration.
     * Default: 120 minutes (2 hours).
     */
    @Min(value = 10, message = "Lock TTL must be at least 10 minutes")
    @Max(value = 1440, message = "Lock TTL must not exceed 24 hours (1440 minutes)")
    private int lockTtlMinutes = 120;

    /**
     * Maximum number of items to validate per run.
     * Lower values reduce rate limiting risk.
     * Set to 0 to effectively disable validation (backout lever).
     * Default: 10 items (conservative, safe default).
     */
    @Min(value = 0, message = "Max items per run must be at least 0 (0 = disabled)")
    @Max(value = 500, message = "Max items per run must not exceed 500")
    private int maxItemsPerRun = 10;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getCron() {
        return cron;
    }

    public void setCron(String cron) {
        this.cron = cron;
    }

    public int getLockTtlMinutes() {
        return lockTtlMinutes;
    }

    public void setLockTtlMinutes(int lockTtlMinutes) {
        this.lockTtlMinutes = lockTtlMinutes;
    }

    public int getMaxItemsPerRun() {
        return maxItemsPerRun;
    }

    public void setMaxItemsPerRun(int maxItemsPerRun) {
        this.maxItemsPerRun = maxItemsPerRun;
    }
}
