package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for YouTube API calls.
 *
 * Detects rate limiting / anti-bot responses from YouTube and opens the circuit
 * to prevent further requests during a cooldown period.
 *
 * Features:
 * - Opens circuit on detecting rate limit errors (SignInConfirmNotBotException, etc.)
 * - Exponential backoff on repeated rate limit detections
 * - Configurable cooldown period
 * - Jitter to avoid deterministic patterns
 *
 * Thread-safe: All state is managed with atomic operations.
 */
@Service
public class YouTubeCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeCircuitBreaker.class);

    private final ValidationProperties validationProperties;

    // Circuit breaker state
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastOpenedAt = new AtomicReference<>(null);
    private final AtomicInteger consecutiveRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger currentCooldownMinutes = new AtomicInteger(0);
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>(null);
    private final AtomicReference<String> lastErrorType = new AtomicReference<>(null);

    // Metrics counters
    private final AtomicInteger totalRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger totalCircuitOpens = new AtomicInteger(0);

    public YouTubeCircuitBreaker(ValidationProperties validationProperties) {
        this.validationProperties = validationProperties;
        logger.info("YouTubeCircuitBreaker initialized - enabled: {}, cooldown: {} minutes",
                validationProperties.getYoutube().getCircuitBreaker().isEnabled(),
                validationProperties.getYoutube().getCircuitBreaker().getCooldownMinutes());
    }

    /**
     * Check if the circuit is currently open (requests should not be made).
     *
     * @return true if circuit is open and requests should be blocked
     */
    public boolean isOpen() {
        if (!validationProperties.getYoutube().getCircuitBreaker().isEnabled()) {
            return false;
        }

        Instant until = cooldownUntil.get();
        if (until == null) {
            return false;
        }

        if (Instant.now().isAfter(until)) {
            // Cooldown has expired, close the circuit
            closeCircuit();
            return false;
        }

        return true;
    }

    /**
     * Get the time remaining until the circuit closes.
     *
     * @return milliseconds until circuit closes, or 0 if already closed
     */
    public long getRemainingCooldownMs() {
        Instant until = cooldownUntil.get();
        if (until == null) {
            return 0;
        }

        long remaining = until.toEpochMilli() - Instant.now().toEpochMilli();
        return Math.max(0, remaining);
    }

    /**
     * Record a rate limit error and potentially open the circuit.
     *
     * @param exception The exception that was caught
     */
    public void recordRateLimitError(Exception exception) {
        if (!validationProperties.getYoutube().getCircuitBreaker().isEnabled()) {
            logger.warn("Rate limit error detected but circuit breaker is disabled: {}", exception.getMessage());
            return;
        }

        totalRateLimitErrors.incrementAndGet();
        consecutiveRateLimitErrors.incrementAndGet();
        lastErrorMessage.set(exception.getMessage());
        lastErrorType.set(exception.getClass().getSimpleName());

        logger.error("YouTube rate limit error detected: {} - {}",
                exception.getClass().getSimpleName(), exception.getMessage());

        ValidationProperties.YouTube.CircuitBreaker cbConfig =
                validationProperties.getYoutube().getCircuitBreaker();

        if (consecutiveRateLimitErrors.get() >= cbConfig.getMaxRateLimitErrorsToOpen()) {
            openCircuit();
        }
    }

    /**
     * Record a successful request (used to reset consecutive error count).
     */
    public void recordSuccess() {
        // Reset consecutive errors on success
        consecutiveRateLimitErrors.set(0);
    }

    /**
     * Check if an exception indicates a YouTube rate limit / anti-bot response.
     *
     * @param exception The exception to check
     * @return true if this is a rate limit error
     */
    public boolean isRateLimitError(Exception exception) {
        if (exception == null) {
            return false;
        }

        String className = exception.getClass().getSimpleName();
        String message = exception.getMessage();

        // Check exception class name
        if (isRateLimitExceptionClass(className)) {
            return true;
        }

        // Check exception message
        if (message != null && isRateLimitMessage(message)) {
            return true;
        }

        // Check cause recursively
        Throwable cause = exception.getCause();
        if (cause instanceof Exception && cause != exception) {
            return isRateLimitError((Exception) cause);
        }

        return false;
    }

    /**
     * Check if exception class name indicates rate limiting.
     */
    private boolean isRateLimitExceptionClass(String className) {
        if (className == null) {
            return false;
        }

        // NewPipeExtractor exception names that indicate rate limiting
        return className.contains("SignInConfirmNotBotException") ||
               className.contains("ReCaptchaException") ||
               className.contains("TooManyRequestsException");
    }

    /**
     * Check if exception message indicates rate limiting.
     */
    private boolean isRateLimitMessage(String message) {
        if (message == null) {
            return false;
        }

        String lowerMessage = message.toLowerCase();

        // YouTube anti-bot messages
        return lowerMessage.contains("sign in to confirm") ||
               lowerMessage.contains("not a bot") ||
               lowerMessage.contains("confirm you're not a bot") ||
               lowerMessage.contains("unusual traffic") ||
               lowerMessage.contains("too many requests") ||
               lowerMessage.contains("rate limit") ||
               lowerMessage.contains("login_required") ||
               lowerMessage.contains("recaptcha") ||
               lowerMessage.contains("captcha") ||
               lowerMessage.contains("429");
    }

    /**
     * Open the circuit with exponential backoff.
     */
    private void openCircuit() {
        ValidationProperties.YouTube.CircuitBreaker cbConfig =
                validationProperties.getYoutube().getCircuitBreaker();

        // Calculate cooldown with exponential backoff
        int baseCooldown = cbConfig.getCooldownMinutes();
        int currentCooldown = currentCooldownMinutes.get();

        int newCooldown;
        if (currentCooldown == 0) {
            // First time opening
            newCooldown = baseCooldown;
        } else {
            // Apply exponential backoff
            newCooldown = (int) Math.min(
                    currentCooldown * cbConfig.getBackoffMultiplier(),
                    cbConfig.getMaxCooldownMinutes()
            );
        }

        // Add jitter (up to 10% of cooldown)
        int jitterMinutes = (int) (newCooldown * 0.1 * Math.random());
        newCooldown += jitterMinutes;

        currentCooldownMinutes.set(newCooldown);

        Instant openedAt = Instant.now();
        Instant until = openedAt.plusSeconds(newCooldown * 60L);

        lastOpenedAt.set(openedAt);
        cooldownUntil.set(until);
        totalCircuitOpens.incrementAndGet();

        logger.error("CIRCUIT BREAKER OPENED - YouTube rate limiting detected! " +
                     "Cooldown: {} minutes (until {}). " +
                     "Consecutive errors: {}. Last error: {} - {}",
                newCooldown, until, consecutiveRateLimitErrors.get(),
                lastErrorType.get(), lastErrorMessage.get());
    }

    /**
     * Close the circuit (called when cooldown expires).
     */
    private void closeCircuit() {
        logger.info("Circuit breaker closing - cooldown period has ended. " +
                    "Validation will resume cautiously.");

        // Keep current cooldown for backoff calculation, but reset cooldown time
        cooldownUntil.set(null);

        // Don't reset consecutiveRateLimitErrors or currentCooldownMinutes here
        // They will be used for backoff if we hit rate limits again
    }

    /**
     * Manually reset the circuit breaker (for admin intervention).
     */
    public void reset() {
        logger.info("Circuit breaker manually reset");
        cooldownUntil.set(null);
        lastOpenedAt.set(null);
        consecutiveRateLimitErrors.set(0);
        currentCooldownMinutes.set(0);
        lastErrorMessage.set(null);
        lastErrorType.set(null);
    }

    // ==================== Status / Metrics ====================

    /**
     * Get current circuit breaker status for monitoring.
     */
    public CircuitBreakerStatus getStatus() {
        return new CircuitBreakerStatus(
                isOpen(),
                getRemainingCooldownMs(),
                lastOpenedAt.get(),
                cooldownUntil.get(),
                consecutiveRateLimitErrors.get(),
                currentCooldownMinutes.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                totalRateLimitErrors.get(),
                totalCircuitOpens.get()
        );
    }

    /**
     * Circuit breaker status for monitoring and diagnostics.
     */
    public static class CircuitBreakerStatus {
        private final boolean open;
        private final long remainingCooldownMs;
        private final Instant lastOpenedAt;
        private final Instant cooldownUntil;
        private final int consecutiveErrors;
        private final int currentCooldownMinutes;
        private final String lastErrorType;
        private final String lastErrorMessage;
        private final int totalRateLimitErrors;
        private final int totalCircuitOpens;

        public CircuitBreakerStatus(
                boolean open,
                long remainingCooldownMs,
                Instant lastOpenedAt,
                Instant cooldownUntil,
                int consecutiveErrors,
                int currentCooldownMinutes,
                String lastErrorType,
                String lastErrorMessage,
                int totalRateLimitErrors,
                int totalCircuitOpens
        ) {
            this.open = open;
            this.remainingCooldownMs = remainingCooldownMs;
            this.lastOpenedAt = lastOpenedAt;
            this.cooldownUntil = cooldownUntil;
            this.consecutiveErrors = consecutiveErrors;
            this.currentCooldownMinutes = currentCooldownMinutes;
            this.lastErrorType = lastErrorType;
            this.lastErrorMessage = lastErrorMessage;
            this.totalRateLimitErrors = totalRateLimitErrors;
            this.totalCircuitOpens = totalCircuitOpens;
        }

        public boolean isOpen() { return open; }
        public long getRemainingCooldownMs() { return remainingCooldownMs; }
        public Instant getLastOpenedAt() { return lastOpenedAt; }
        public Instant getCooldownUntil() { return cooldownUntil; }
        public int getConsecutiveErrors() { return consecutiveErrors; }
        public int getCurrentCooldownMinutes() { return currentCooldownMinutes; }
        public String getLastErrorType() { return lastErrorType; }
        public String getLastErrorMessage() { return lastErrorMessage; }
        public int getTotalRateLimitErrors() { return totalRateLimitErrors; }
        public int getTotalCircuitOpens() { return totalCircuitOpens; }
    }
}
