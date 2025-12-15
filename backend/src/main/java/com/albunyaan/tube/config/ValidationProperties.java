package com.albunyaan.tube.config;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * Configuration properties for content validation.
 *
 * Controls scheduler behavior, rate limiting, circuit breaker, and throttling
 * to prevent triggering YouTube's anti-bot protections.
 */
@Configuration
@ConfigurationProperties(prefix = "app.validation")
public class ValidationProperties {

    private final Video video = new Video();
    private final YouTube youtube = new YouTube();

    public Video getVideo() {
        return video;
    }

    public YouTube getYoutube() {
        return youtube;
    }

    /**
     * Video validation settings
     */
    public static class Video {
        private final Scheduler scheduler = new Scheduler();
        private int maxItemsPerRun = 10;

        public Scheduler getScheduler() {
            return scheduler;
        }

        public int getMaxItemsPerRun() {
            return maxItemsPerRun;
        }

        public void setMaxItemsPerRun(int maxItemsPerRun) {
            this.maxItemsPerRun = maxItemsPerRun;
        }

        /**
         * Scheduler settings for video validation
         */
        public static class Scheduler {
            private boolean enabled = true;
            private String cron = "0 0 6 * * ?"; // Default: once daily at 6 AM UTC

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
        }
    }

    /**
     * YouTube API interaction settings
     */
    public static class YouTube {
        private final Throttle throttle = new Throttle();
        private final CircuitBreaker circuitBreaker = new CircuitBreaker();

        public Throttle getThrottle() {
            return throttle;
        }

        public CircuitBreaker getCircuitBreaker() {
            return circuitBreaker;
        }

        /**
         * Throttling settings for YouTube requests
         */
        public static class Throttle {
            private boolean enabled = true;
            private long delayBetweenItemsMs = 3000; // 3 seconds between requests
            private long jitterMs = 1000; // Random jitter up to 1 second

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
        }

        /**
         * Circuit breaker settings for rate limiting protection
         */
        public static class CircuitBreaker {
            private boolean enabled = true;
            private int cooldownMinutes = 720; // 12 hours default
            private int maxRateLimitErrorsToOpen = 1; // Open after first rate limit error
            private int maxCooldownMinutes = 2880; // 48 hours max
            private double backoffMultiplier = 2.0; // Double cooldown on repeated errors

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getCooldownMinutes() {
                return cooldownMinutes;
            }

            public void setCooldownMinutes(int cooldownMinutes) {
                this.cooldownMinutes = cooldownMinutes;
            }

            public int getMaxRateLimitErrorsToOpen() {
                return maxRateLimitErrorsToOpen;
            }

            public void setMaxRateLimitErrorsToOpen(int maxRateLimitErrorsToOpen) {
                this.maxRateLimitErrorsToOpen = maxRateLimitErrorsToOpen;
            }

            public int getMaxCooldownMinutes() {
                return maxCooldownMinutes;
            }

            public void setMaxCooldownMinutes(int maxCooldownMinutes) {
                this.maxCooldownMinutes = maxCooldownMinutes;
            }

            public double getBackoffMultiplier() {
                return backoffMultiplier;
            }

            public void setBackoffMultiplier(double backoffMultiplier) {
                this.backoffMultiplier = backoffMultiplier;
            }
        }
    }
}
