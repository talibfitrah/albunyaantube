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
            private int lockTtlMinutes = 120; // Default: 2 hours (120 minutes)

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
         * Circuit breaker settings for rate limiting protection.
         * Implements a state machine: CLOSED → OPEN → HALF_OPEN → CLOSED
         */
        public static class CircuitBreaker {
            private boolean enabled = true;
            private int cooldownBaseMinutes = 60; // Level 0: 1 hour
            private int cooldownMaxMinutes = 2880; // Level 4 cap: 48 hours
            private int backoffDecayHours = 48; // Hours of success before decrementing backoff level
            private int probeTimeoutSeconds = 30; // Timeout for HALF_OPEN probe requests
            private final RollingWindow rollingWindow = new RollingWindow();

            // Legacy properties (kept for backwards compatibility)
            private int cooldownMinutes = 720; // 12 hours default (legacy)
            private int maxRateLimitErrorsToOpen = 1; // Legacy - use rolling window instead
            private int maxCooldownMinutes = 2880; // Legacy - use cooldownMaxMinutes
            private double backoffMultiplier = 2.0; // Legacy

            public boolean isEnabled() {
                return enabled;
            }

            public void setEnabled(boolean enabled) {
                this.enabled = enabled;
            }

            public int getCooldownBaseMinutes() {
                return cooldownBaseMinutes;
            }

            public void setCooldownBaseMinutes(int cooldownBaseMinutes) {
                this.cooldownBaseMinutes = cooldownBaseMinutes;
            }

            public int getCooldownMaxMinutes() {
                return cooldownMaxMinutes;
            }

            public void setCooldownMaxMinutes(int cooldownMaxMinutes) {
                this.cooldownMaxMinutes = cooldownMaxMinutes;
            }

            public int getBackoffDecayHours() {
                return backoffDecayHours;
            }

            public void setBackoffDecayHours(int backoffDecayHours) {
                this.backoffDecayHours = backoffDecayHours;
            }

            public int getProbeTimeoutSeconds() {
                return probeTimeoutSeconds;
            }

            public void setProbeTimeoutSeconds(int probeTimeoutSeconds) {
                this.probeTimeoutSeconds = probeTimeoutSeconds;
            }

            public RollingWindow getRollingWindow() {
                return rollingWindow;
            }

            // Legacy getters/setters (for backwards compatibility)
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

            /**
             * Rolling window settings for determining when to open the circuit breaker.
             * Opens after N errors within T minutes (default: 3 errors in 10 minutes).
             */
            public static class RollingWindow {
                private int errorThreshold = 3; // Number of rate-limit errors to trigger open
                private int windowMinutes = 10; // Time window to count errors within

                public int getErrorThreshold() {
                    return errorThreshold;
                }

                public void setErrorThreshold(int errorThreshold) {
                    this.errorThreshold = errorThreshold;
                }

                public int getWindowMinutes() {
                    return windowMinutes;
                }

                public void setWindowMinutes(int windowMinutes) {
                    this.windowMinutes = windowMinutes;
                }
            }
        }
    }
}
