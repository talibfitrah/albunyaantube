package com.albunyaan.tube.config;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;
import org.springframework.validation.annotation.Validated;

/**
 * Configuration properties for YouTube circuit breaker.
 *
 * Implements circuit breaker pattern to prevent hammering YouTube when rate-limited.
 * State is persisted in Firestore for crash recovery and multi-instance coordination.
 *
 * States:
 * - CLOSED: Normal operation, requests allowed
 * - OPEN: Rate limiting detected, requests blocked
 * - HALF_OPEN: Testing recovery, single probe request allowed
 *
 * @see com.albunyaan.tube.service.YouTubeCircuitBreakerService
 */
@Configuration
@ConfigurationProperties(prefix = "app.youtube.circuit-breaker")
@Validated
public class YouTubeCircuitBreakerProperties {

    /**
     * Enable or disable the circuit breaker.
     * When disabled, requests proceed without circuit breaker protection.
     */
    private boolean enabled = true;

    /**
     * Firestore collection for circuit breaker state.
     */
    private String persistenceCollection = "system_settings";

    /**
     * Firestore document ID for circuit breaker state.
     */
    private String persistenceDocumentId = "youtube_circuit_breaker";

    /**
     * Base cooldown duration in minutes when breaker opens (backoff level 0).
     * Default: 60 minutes (1 hour).
     */
    @Min(value = 1, message = "Cooldown base must be at least 1 minute")
    @Max(value = 1440, message = "Cooldown base must not exceed 24 hours")
    private int cooldownBaseMinutes = 60;

    /**
     * Maximum cooldown duration in minutes (backoff level 4 cap).
     * Default: 2880 minutes (48 hours).
     */
    @Min(value = 60, message = "Cooldown max must be at least 60 minutes")
    @Max(value = 10080, message = "Cooldown max must not exceed 7 days")
    private int cooldownMaxMinutes = 2880;

    /**
     * Hours of successful operation (no rate-limit errors) before
     * decrementing the backoff level.
     * Default: 48 hours.
     */
    @Min(value = 1, message = "Backoff decay must be at least 1 hour")
    @Max(value = 168, message = "Backoff decay must not exceed 7 days")
    private int backoffDecayHours = 48;

    /**
     * Timeout in seconds for HALF_OPEN probe requests.
     * If probe doesn't complete within this time, treat as failure.
     * Default: 30 seconds.
     */
    @Min(value = 5, message = "Probe timeout must be at least 5 seconds")
    @Max(value = 120, message = "Probe timeout must not exceed 2 minutes")
    private int probeTimeoutSeconds = 30;

    /**
     * Number of rate-limit errors within rolling window to trigger breaker open.
     * Default: 3 errors.
     */
    @Min(value = 1, message = "Error threshold must be at least 1")
    @Max(value = 20, message = "Error threshold must not exceed 20")
    private int rollingWindowErrorThreshold = 3;

    /**
     * Rolling window duration in minutes for counting rate-limit errors.
     * Default: 10 minutes.
     */
    @Min(value = 1, message = "Rolling window must be at least 1 minute")
    @Max(value = 60, message = "Rolling window must not exceed 60 minutes")
    private int rollingWindowMinutes = 10;

    /**
     * Maximum retry attempts for persisting circuit breaker state.
     * After max retries, falls back to local-only state.
     * Default: 3 retries.
     */
    @Min(value = 1, message = "Max retries must be at least 1")
    @Max(value = 10, message = "Max retries must not exceed 10")
    private int maxPersistenceRetries = 3;

    // Getters and Setters

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public String getPersistenceCollection() {
        return persistenceCollection;
    }

    public void setPersistenceCollection(String persistenceCollection) {
        this.persistenceCollection = persistenceCollection;
    }

    public String getPersistenceDocumentId() {
        return persistenceDocumentId;
    }

    public void setPersistenceDocumentId(String persistenceDocumentId) {
        this.persistenceDocumentId = persistenceDocumentId;
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

    public int getRollingWindowErrorThreshold() {
        return rollingWindowErrorThreshold;
    }

    public void setRollingWindowErrorThreshold(int rollingWindowErrorThreshold) {
        this.rollingWindowErrorThreshold = rollingWindowErrorThreshold;
    }

    public int getRollingWindowMinutes() {
        return rollingWindowMinutes;
    }

    public void setRollingWindowMinutes(int rollingWindowMinutes) {
        this.rollingWindowMinutes = rollingWindowMinutes;
    }

    public int getMaxPersistenceRetries() {
        return maxPersistenceRetries;
    }

    public void setMaxPersistenceRetries(int maxPersistenceRetries) {
        this.maxPersistenceRetries = maxPersistenceRetries;
    }

    /**
     * Cross-field validation to ensure cooldownBaseMinutes does not exceed cooldownMaxMinutes.
     */
    @AssertTrue(message = "cooldownBaseMinutes must not exceed cooldownMaxMinutes")
    private boolean isCooldownRangeValid() {
        return cooldownBaseMinutes <= cooldownMaxMinutes;
    }

    /**
     * Calculate cooldown duration in minutes for a given backoff level.
     * Uses a fixed schedule (not exponential formula):
     *   Level 0: base (default 60min / 1h)
     *   Level 1: 360min (6h)
     *   Level 2: 720min (12h)
     *   Level 3: 1440min (24h)
     *   Level 4+: max (default 2880min / 48h)
     *
     * Note: If cooldownMaxMinutes is configured below the intermediate step values
     * (360, 720, or 1440), multiple backoff levels will effectively have the same
     * cooldown duration (clamped to max).
     */
    public long calculateCooldownMinutes(int backoffLevel) {
        if (backoffLevel <= 0) {
            return cooldownBaseMinutes;
        }
        // Fixed backoff schedule for levels 1-4, capped at max
        // Array indexes: 0=level1, 1=level2, 2=level3, 3=level4+
        long[] cooldowns = {360, 720, 1440, cooldownMaxMinutes};
        int index = Math.min(backoffLevel - 1, cooldowns.length - 1);
        return Math.min(cooldowns[index], cooldownMaxMinutes);
    }
}
