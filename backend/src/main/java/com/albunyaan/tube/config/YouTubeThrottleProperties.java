package com.albunyaan.tube.config;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for YouTube API request throttling.
 *
 * Implements rate limiting to prevent triggering YouTube anti-bot protections.
 * Applies to all NewPipeExtractor calls that hit YouTube.
 *
 * @see com.albunyaan.tube.service.YouTubeGateway
 */
@Configuration
@ConfigurationProperties(prefix = "app.youtube.throttle")
@Validated
public class YouTubeThrottleProperties {

    /**
     * Enable or disable request throttling.
     * When disabled, requests are made without delay (not recommended for production).
     */
    private boolean enabled = true;

    /**
     * Base delay in milliseconds between YouTube API calls.
     * Default: 3000ms (3 seconds).
     */
    @Min(value = 0, message = "Delay must be non-negative")
    @Max(value = 60000, message = "Delay must not exceed 60 seconds")
    private long delayBetweenItemsMs = 3000;

    /**
     * Random jitter in milliseconds added to the base delay.
     * Prevents deterministic request patterns that may trigger anti-bot detection.
     * Actual delay = delayBetweenItemsMs + random(0, jitterMs).
     * Default: 1000ms (1 second).
     */
    @Min(value = 0, message = "Jitter must be non-negative")
    @Max(value = 30000, message = "Jitter must not exceed 30 seconds")
    private long jitterMs = 1000;

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public long getDelayBetweenItemsMs() {
        return delayBetweenItemsMs;
    }

    public void setDelayBetweenItemsMs(long delayBetweenItemsMs) {
        this.delayBetweenItemsMs = delayBetweenItemsMs;
    }

    public long getJitterMs() {
        return jitterMs;
    }

    public void setJitterMs(long jitterMs) {
        this.jitterMs = jitterMs;
    }

    /**
     * Calculate total delay with jitter.
     * @return delay in milliseconds (base delay + random jitter)
     */
    public long calculateDelayWithJitter() {
        if (!enabled || delayBetweenItemsMs == 0) {
            return 0;
        }
        long jitter = jitterMs > 0 ? (long) (Math.random() * jitterMs) : 0;
        return delayBetweenItemsMs + jitter;
    }
}
