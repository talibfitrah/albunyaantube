package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Throttler for YouTube API requests.
 *
 * Ensures requests are spaced out to avoid triggering YouTube's rate limiting.
 * Uses a simple delay-based approach with jitter.
 *
 * Thread-safe: Uses a lock to serialize throttled requests.
 */
@Service
public class YouTubeThrottler {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeThrottler.class);

    private final ValidationProperties validationProperties;
    private final ReentrantLock throttleLock = new ReentrantLock();

    private volatile long lastRequestTime = 0;

    public YouTubeThrottler(ValidationProperties validationProperties) {
        this.validationProperties = validationProperties;
        logger.info("YouTubeThrottler initialized - enabled: {}, delay: {}ms, jitter: {}ms",
                validationProperties.getYoutube().getThrottle().isEnabled(),
                validationProperties.getYoutube().getThrottle().getDelayBetweenItemsMs(),
                validationProperties.getYoutube().getThrottle().getJitterMs());
    }

    /**
     * Wait if needed before making a YouTube request.
     * This method is thread-safe and will serialize requests if multiple threads call it.
     */
    public void throttle() {
        if (!validationProperties.getYoutube().getThrottle().isEnabled()) {
            return;
        }

        ValidationProperties.YouTube.Throttle config = validationProperties.getYoutube().getThrottle();
        long baseDelay = config.getDelayBetweenItemsMs();
        long jitter = config.getJitterMs();

        // Calculate total delay with jitter
        long delay = baseDelay;
        if (jitter > 0) {
            delay += ThreadLocalRandom.current().nextLong(jitter);
        }

        throttleLock.lock();
        try {
            long now = System.currentTimeMillis();
            long timeSinceLastRequest = now - lastRequestTime;

            if (timeSinceLastRequest < delay && lastRequestTime > 0) {
                long sleepTime = delay - timeSinceLastRequest;
                if (sleepTime > 0) {
                    logger.trace("Throttling: sleeping {}ms before next request", sleepTime);
                    try {
                        Thread.sleep(sleepTime);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        logger.warn("Throttle sleep interrupted");
                    }
                }
            }

            lastRequestTime = System.currentTimeMillis();
        } finally {
            throttleLock.unlock();
        }
    }

    /**
     * Check if throttling is enabled.
     */
    public boolean isEnabled() {
        return validationProperties.getYoutube().getThrottle().isEnabled();
    }

    /**
     * Get the configured delay between requests.
     */
    public long getDelayMs() {
        return validationProperties.getYoutube().getThrottle().getDelayBetweenItemsMs();
    }

    /**
     * Get the configured jitter.
     */
    public long getJitterMs() {
        return validationProperties.getYoutube().getThrottle().getJitterMs();
    }
}
