package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.repository.SystemSettingsRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.lang.Nullable;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Circuit breaker for YouTube API calls with Firestore-persisted state machine.
 *
 * Implements a three-state circuit breaker:
 * - CLOSED: Normal operation, requests allowed
 * - OPEN: Blocking requests, waiting for cooldown
 * - HALF_OPEN: Testing recovery with a single probe request
 *
 * Features:
 * - Rolling window error detection (N errors in T minutes)
 * - Exponential backoff with 5 levels (1h → 6h → 12h → 24h → 48h)
 * - Backoff decay after sustained success
 * - Firestore persistence for multi-instance coordination
 * - Fail-safe: defaults to OPEN if persistence unavailable (rejects requests)
 * - Optimistic locking for safe concurrent updates
 *
 * <h3>HALF_OPEN State: Two-Step Permission Protocol</h3>
 *
 * When the circuit is in HALF_OPEN state, callers MUST follow this two-step protocol:
 * <ol>
 *   <li>Call {@link #isOpen()} - if it returns {@code false}, requests MAY be allowed</li>
 *   <li>Call {@link #allowProbe()} - if it returns {@code true}, this caller has acquired
 *       the exclusive probe permit and should proceed with exactly ONE request</li>
 * </ol>
 *
 * <p><strong>Important:</strong> In HALF_OPEN state, {@code isOpen()} returning {@code false}
 * does NOT guarantee permission to proceed. The caller must also acquire the probe permit
 * via {@code allowProbe()}. Only one thread can hold the probe permit at a time.</p>
 *
 * <pre>{@code
 * // Correct usage pattern:
 * if (!circuitBreaker.isOpen()) {
 *     if (circuitBreaker.allowProbe()) {
 *         try {
 *             // Make YouTube request
 *             circuitBreaker.recordSuccess();
 *         } catch (Exception e) {
 *             if (circuitBreaker.isRateLimitError(e)) {
 *                 circuitBreaker.recordRateLimitError(e);
 *             } else {
 *                 circuitBreaker.recordProbeFailure(e);
 *             }
 *         }
 *     } else {
 *         // Another thread is already probing - skip this request
 *     }
 * }
 * }</pre>
 *
 * Thread-safe: All state is managed with atomic operations.
 */
@Service
public class YouTubeCircuitBreaker {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeCircuitBreaker.class);
    private static final String SETTINGS_KEY = "youtube_circuit_breaker";
    private static final int MAX_OPTIMISTIC_LOCK_RETRIES = 3;

    /**
     * Circuit breaker states.
     */
    public enum State {
        CLOSED,    // Normal operation, requests allowed
        OPEN,      // Blocking requests, cooldown active
        HALF_OPEN  // Testing recovery with probe request
    }

    /**
     * Cooldown durations by backoff level (in minutes).
     */
    private static final int[] COOLDOWN_BY_LEVEL = {
        60,    // Level 0: 1 hour
        360,   // Level 1: 6 hours
        720,   // Level 2: 12 hours
        1440,  // Level 3: 24 hours
        2880   // Level 4: 48 hours (cap)
    };

    private final ValidationProperties validationProperties;

    @Nullable
    private final SystemSettingsRepository systemSettingsRepository;

    // Circuit breaker state (in-memory, synced with Firestore)
    private final AtomicReference<State> state = new AtomicReference<>(State.CLOSED);
    private final AtomicReference<Instant> openedAt = new AtomicReference<>(null);
    private final AtomicReference<Instant> cooldownUntil = new AtomicReference<>(null);
    private final AtomicInteger backoffLevel = new AtomicInteger(0);
    private final AtomicLong version = new AtomicLong(0);

    // HALF_OPEN probe tracking: ensures only one request proceeds as probe
    private final AtomicReference<Boolean> probeInProgress = new AtomicReference<>(false);

    // Rolling window error tracking (timestamps stored in Firestore for multi-instance)
    private final List<Instant> recentErrors = new ArrayList<>();
    private final Object errorListLock = new Object();

    // Error details for diagnostics
    private final AtomicReference<String> lastErrorMessage = new AtomicReference<>(null);
    private final AtomicReference<String> lastErrorType = new AtomicReference<>(null);
    private final AtomicReference<Instant> lastErrorAt = new AtomicReference<>(null);

    // Metrics counters
    private final AtomicInteger totalRateLimitErrors = new AtomicInteger(0);
    private final AtomicInteger totalCircuitOpens = new AtomicInteger(0);

    // Flag to track if we're in fail-safe mode (persistence unavailable)
    private final AtomicReference<Boolean> persistenceAvailable = new AtomicReference<>(true);

    public YouTubeCircuitBreaker(
            ValidationProperties validationProperties,
            @Nullable SystemSettingsRepository systemSettingsRepository) {
        this.validationProperties = validationProperties;
        this.systemSettingsRepository = systemSettingsRepository;
        logger.info("YouTubeCircuitBreaker initialized - enabled: {}, persistence: {}",
                validationProperties.getYoutube().getCircuitBreaker().isEnabled(),
                systemSettingsRepository != null ? "enabled" : "disabled");
    }

    /**
     * Load persisted state on startup.
     */
    @PostConstruct
    public void loadPersistedState() {
        if (systemSettingsRepository == null) {
            logger.debug("No system settings repository - circuit breaker will use in-memory state only");
            return;
        }

        try {
            Optional<Map<String, Object>> data = systemSettingsRepository.load(SETTINGS_KEY);
            if (data.isPresent()) {
                Map<String, Object> stateData = data.get();
                loadStateFromMap(stateData);

                // Handle stale OPEN state: if cooldown expired, transition to HALF_OPEN
                if (state.get() == State.OPEN) {
                    Instant until = cooldownUntil.get();
                    if (until != null && Instant.now().isAfter(until)) {
                        logger.info("Stale OPEN state detected on startup - transitioning to HALF_OPEN");
                        state.set(State.HALF_OPEN);
                        persistState();
                    }
                }

                logger.info("Circuit breaker state loaded - state: {}, backoffLevel: {}, version: {}",
                        state.get(), backoffLevel.get(), version.get());
            } else {
                logger.debug("No persisted circuit breaker state found - starting fresh (CLOSED)");
            }
        } catch (Exception e) {
            logger.warn("Failed to load circuit breaker state: {} - starting fresh (CLOSED)", e.getMessage());
        }
    }

    /**
     * Load state from a map (used by loadPersistedState and tests).
     */
    private void loadStateFromMap(Map<String, Object> stateData) {
        // Load state enum
        String stateStr = (String) stateData.get("state");
        if (stateStr != null) {
            try {
                state.set(State.valueOf(stateStr));
            } catch (IllegalArgumentException e) {
                logger.warn("Invalid state value '{}' - defaulting to CLOSED", stateStr);
                state.set(State.CLOSED);
            }
        }

        // Load timestamps
        Number openedAtEpoch = (Number) stateData.get("openedAtEpoch");
        if (openedAtEpoch != null) {
            openedAt.set(Instant.ofEpochMilli(openedAtEpoch.longValue()));
        }

        Number cooldownUntilEpoch = (Number) stateData.get("cooldownUntilEpoch");
        if (cooldownUntilEpoch != null) {
            cooldownUntil.set(Instant.ofEpochMilli(cooldownUntilEpoch.longValue()));
        }

        Number lastErrorAtEpoch = (Number) stateData.get("lastErrorAtEpoch");
        if (lastErrorAtEpoch != null) {
            lastErrorAt.set(Instant.ofEpochMilli(lastErrorAtEpoch.longValue()));
        }

        // Load backoff level
        Number level = (Number) stateData.get("backoffLevel");
        if (level != null) {
            backoffLevel.set(Math.min(level.intValue(), COOLDOWN_BY_LEVEL.length - 1));
        }

        // Load version for optimistic locking
        Number ver = (Number) stateData.get("version");
        if (ver != null) {
            version.set(ver.longValue());
        }

        // Load error details
        String errorType = (String) stateData.get("lastErrorType");
        if (errorType != null) {
            lastErrorType.set(errorType);
        }

        String errorMessage = (String) stateData.get("lastErrorMessage");
        if (errorMessage != null) {
            lastErrorMessage.set(errorMessage);
        }

        // Load metrics
        Number totalErrors = (Number) stateData.get("totalRateLimitErrors");
        if (totalErrors != null) {
            totalRateLimitErrors.set(totalErrors.intValue());
        }

        Number totalOpens = (Number) stateData.get("totalCircuitOpens");
        if (totalOpens != null) {
            totalCircuitOpens.set(totalOpens.intValue());
        }

        // Load rolling window errors for multi-instance coordination
        @SuppressWarnings("unchecked")
        List<Number> errorEpochs = (List<Number>) stateData.get("recentErrorEpochs");
        if (errorEpochs != null) {
            int windowMinutes = validationProperties.getYoutube().getCircuitBreaker()
                    .getRollingWindow().getWindowMinutes();
            Instant windowStart = Instant.now().minusSeconds(windowMinutes * 60L);

            synchronized (errorListLock) {
                recentErrors.clear();
                for (Number epoch : errorEpochs) {
                    if (epoch != null) {
                        Instant errorTime = Instant.ofEpochMilli(epoch.longValue());
                        // Only include errors within the window
                        if (errorTime.isAfter(windowStart)) {
                            recentErrors.add(errorTime);
                        }
                    }
                }
            }
        }
    }

    /**
     * Persist current state to Firestore with optimistic locking.
     * Uses version check to prevent concurrent update conflicts.
     * Returns true if persist succeeded, false otherwise.
     */
    private boolean persistState() {
        if (systemSettingsRepository == null) {
            return true; // No persistence configured, consider it success
        }

        String correlationId = newCorrelationId();
        for (int attempt = 0; attempt < MAX_OPTIMISTIC_LOCK_RETRIES; attempt++) {
            try {
                long currentVersion = version.get();
                long newVersion = currentVersion + 1;

                Map<String, Object> stateData = new HashMap<>();
                stateData.put("state", state.get().name());

                Instant opened = openedAt.get();
                stateData.put("openedAtEpoch", opened != null ? opened.toEpochMilli() : null);

                Instant until = cooldownUntil.get();
                stateData.put("cooldownUntilEpoch", until != null ? until.toEpochMilli() : null);

                Instant lastErr = lastErrorAt.get();
                stateData.put("lastErrorAtEpoch", lastErr != null ? lastErr.toEpochMilli() : null);

                stateData.put("backoffLevel", backoffLevel.get());
                stateData.put("version", newVersion);
                stateData.put("lastErrorType", lastErrorType.get());
                stateData.put("lastErrorMessage", lastErrorMessage.get());
                stateData.put("totalRateLimitErrors", totalRateLimitErrors.get());
                stateData.put("totalCircuitOpens", totalCircuitOpens.get());
                stateData.put("lastUpdated", Instant.now().toEpochMilli());

                // Persist rolling window errors for multi-instance coordination
                synchronized (errorListLock) {
                    List<Long> errorEpochs = new ArrayList<>();
                    for (Instant errorTime : recentErrors) {
                        errorEpochs.add(errorTime.toEpochMilli());
                    }
                    stateData.put("recentErrorEpochs", errorEpochs);
                }

                // Use version-checked save for optimistic locking
                // Pass currentVersion (or -1 if version is 0, meaning new document)
                boolean success = systemSettingsRepository.saveWithVersionCheck(
                        SETTINGS_KEY, stateData, currentVersion == 0 ? -1 : currentVersion);

                if (success) {
                    version.set(newVersion);
                    persistenceAvailable.set(true);
                    logger.debug("Circuit breaker state persisted - state: {}, version: {}", state.get(), newVersion);
                    return true;
                }

                // Version conflict - reload and retry
                logger.debug("Version conflict on persist attempt {}, reloading state", attempt + 1);
                refreshStateFromFirestore();

            } catch (Exception e) {
                logger.error("Persist attempt {} failed (will retry if possible). correlationId={}, error={}",
                        attempt + 1, correlationId, e.getMessage());
                if (attempt < MAX_OPTIMISTIC_LOCK_RETRIES - 1) {
                    // Reload state before retry
                    try {
                        refreshStateFromFirestore();
                    } catch (Exception refreshEx) {
                        logger.error("Failed to refresh state before retry. correlationId={}, error={}",
                                correlationId, refreshEx.getMessage());
                    }
                }
            }
        }

        logger.error("Failed to persist circuit breaker state after {} attempts. correlationId={}",
                MAX_OPTIMISTIC_LOCK_RETRIES, correlationId);
        persistenceAvailable.set(false);
        return false;
    }

    /**
     * Refresh state from Firestore (for multi-instance coordination).
     * Uses loadOrThrow to properly detect persistence failures.
     */
    private void refreshStateFromFirestore() {
        if (systemSettingsRepository == null) {
            return;
        }

        String correlationId = newCorrelationId();
        try {
            // Use loadOrThrow to detect persistence failures
            Optional<Map<String, Object>> data = systemSettingsRepository.loadOrThrow(SETTINGS_KEY);
            if (data.isPresent()) {
                loadStateFromMap(data.get());
            }
            persistenceAvailable.set(true);
        } catch (Exception e) {
            logger.error("Failed to refresh circuit breaker state (persistence unavailable). correlationId={}, error={}",
                    correlationId, e.getMessage());
            persistenceAvailable.set(false);
        }
    }

    /**
     * Check if the circuit is currently open (requests should not be made).
     *
     * <p>FAIL-SAFE POLICY: If persistence is unavailable and we can't verify state,
     * this defaults to OPEN (rejecting requests) to prevent hammering YouTube.</p>
     *
     * <p><strong>HALF_OPEN State Warning:</strong> When in HALF_OPEN state, this method
     * returning {@code false} does NOT grant permission to proceed. Callers MUST also call
     * {@link #allowProbe()} to acquire the exclusive probe permit. See the class-level
     * documentation for the correct usage pattern.</p>
     *
     * @return true if circuit is open and requests should be blocked; false if requests
     *         may be allowed (but in HALF_OPEN state, caller must still acquire probe permit)
     */
    public boolean isOpen() {
        if (!validationProperties.getYoutube().getCircuitBreaker().isEnabled()) {
            return false;
        }

        // Refresh state from Firestore for multi-instance coordination
        refreshStateFromFirestore();

        // FAIL-SAFE: If persistence failed, default to OPEN
        if (!persistenceAvailable.get() && systemSettingsRepository != null) {
            logger.error("Circuit breaker persistence unavailable - defaulting to OPEN (safe mode). correlationId={}",
                    newCorrelationId());
            return true;
        }

        State currentState = state.get();

        switch (currentState) {
            case CLOSED:
                return false;

            case OPEN:
                // Check if cooldown has expired
                Instant until = cooldownUntil.get();
                if (until == null || Instant.now().isAfter(until)) {
                    // Transition to HALF_OPEN
                    logger.info("Circuit breaker cooldown expired - transitioning to HALF_OPEN");
                    state.set(State.HALF_OPEN);
                    persistState();
                    return false; // Allow the probe request
                }
                return true;

            case HALF_OPEN:
                // In HALF_OPEN, requests are allowed but ONLY one may proceed as the probe.
                // IMPORTANT: Do NOT acquire the probe permit here; callers must call allowProbe()
                // immediately before the outbound YouTube request.
                return probeInProgress.get();

            default:
                return true;
        }
    }

    /**
     * Batch-friendly helper: true when outbound requests should be blocked.
     *
     * Alias for {@link #isOpen()} to match plan terminology ("blocking" vs "open").
     */
    public boolean isCircuitBreakerBlocking() {
        return isOpen();
    }

    /**
     * Attempt to acquire the single probe permit when in HALF_OPEN state.
     * Call this immediately before making an outbound YouTube request.
     *
     * <p><strong>Note:</strong> Callers MUST check {@link #isOpen()} before calling this method.
     * This method only handles probe permission in HALF_OPEN state and should not be used
     * to bypass an OPEN circuit. When in OPEN state, this method returns {@code false}
     * as a defensive measure against misuse.</p>
     *
     * @return true if caller may proceed with the request (CLOSED state or acquired probe permit in HALF_OPEN)
     */
    public boolean allowProbe() {
        State currentState = state.get();
        if (currentState == State.OPEN) {
            return false; // Explicitly block if circuit is OPEN
        }
        if (currentState != State.HALF_OPEN) {
            return true; // CLOSED state - allow
        }
        return probeInProgress.compareAndSet(false, true);
    }

    /**
     * Check if the current request is a probe (circuit is in HALF_OPEN and probe is in progress).
     * Used by gateway to apply probe timeout.
     *
     * @return true if this is a probe request
     */
    public boolean isProbeRequest() {
        return state.get() == State.HALF_OPEN && probeInProgress.get();
    }

    /**
     * Get the configured probe timeout in seconds.
     *
     * @return probe timeout in seconds
     */
    public int getProbeTimeoutSeconds() {
        return validationProperties.getYoutube().getCircuitBreaker().getProbeTimeoutSeconds();
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
        lastErrorMessage.set(truncate(exception.getMessage(), 500));
        lastErrorType.set(exception.getClass().getSimpleName());
        lastErrorAt.set(Instant.now());

        logger.error("YouTube rate limit error detected: {} - {}",
                exception.getClass().getSimpleName(), exception.getMessage());

        // Handle based on current state
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Probe failed - reopen with increased backoff
            reopenWithIncreasedBackoff();
            return;
        }

        // Add to rolling window
        addErrorToRollingWindow();

        // Check rolling window threshold
        if (shouldOpenCircuit()) {
            openCircuit();
        }

        persistState();
    }

    /**
     * Add error to rolling window and clean old entries.
     */
    private void addErrorToRollingWindow() {
        int windowMinutes = validationProperties.getYoutube().getCircuitBreaker()
                .getRollingWindow().getWindowMinutes();
        Instant windowStart = Instant.now().minusSeconds(windowMinutes * 60L);

        synchronized (errorListLock) {
            // Add new error
            recentErrors.add(Instant.now());

            // Remove errors outside the window
            recentErrors.removeIf(t -> t.isBefore(windowStart));
        }
    }

    /**
     * Check if rolling window threshold is exceeded.
     */
    private boolean shouldOpenCircuit() {
        int threshold = validationProperties.getYoutube().getCircuitBreaker()
                .getRollingWindow().getErrorThreshold();

        synchronized (errorListLock) {
            return recentErrors.size() >= threshold;
        }
    }

    /**
     * Record a successful request.
     * Resets error state and potentially closes circuit from HALF_OPEN.
     */
    public void recordSuccess() {
        State currentState = state.get();

        if (currentState == State.HALF_OPEN) {
            // Probe succeeded - close the circuit and reset probe flag
            logger.info("Circuit breaker probe succeeded - transitioning to CLOSED");
            probeInProgress.set(false);
            closeCircuit();
            return;
        }

        if (currentState == State.CLOSED) {
            // Check for backoff decay (decrement level after sustained success)
            checkBackoffDecay();
        }

        // Clear rolling window on success
        synchronized (errorListLock) {
            recentErrors.clear();
        }
    }

    /**
     * Record a probe failure due to a non-rate-limit error (network timeout, parsing error, etc.).
     * This clears the probe permit and reopens the circuit WITHOUT increasing backoff level,
     * since non-rate-limit errors are transient and don't indicate YouTube is actively blocking us.
     *
     * <p><strong>Caller Requirement:</strong> This method should only be called by the thread
     * that successfully acquired the probe permit via {@link #allowProbe()}. If called by a
     * thread that does not hold the probe permit, the call is ignored.</p>
     *
     * @param exception The exception that caused the probe to fail
     */
    public void recordProbeFailure(Exception exception) {
        // Validate probe ownership: only the thread that acquired the probe permit should call this.
        // Use compareAndSet to atomically clear the probe flag and verify ownership.
        if (!probeInProgress.compareAndSet(true, false)) {
            logger.debug("recordProbeFailure called but probe not in progress - ignoring");
            return;
        }

        // Use compareAndSet for atomic state transition to prevent overwriting a concurrent
        // CLOSED transition (e.g., if recordSuccess() was called by another path).
        if (!state.compareAndSet(State.HALF_OPEN, State.OPEN)) {
            logger.debug("recordProbeFailure: state changed from HALF_OPEN - skipping probe failure handling");
            return;
        }

        // Log the failure
        logger.warn("Circuit breaker probe failed with non-rate-limit error: {} - {}. " +
                    "Reopening circuit at current backoff level (not increasing).",
                exception.getClass().getSimpleName(), exception.getMessage());

        // Reopen circuit at CURRENT backoff level (don't increment - it's not a rate limit)
        int level = backoffLevel.get();
        int cooldownMinutes = getCooldownMinutesForLevel(level);

        Instant now = Instant.now();
        Instant until = now.plusSeconds(cooldownMinutes * 60L);

        openedAt.set(now);
        cooldownUntil.set(until);
        // Don't increment totalCircuitOpens - this is a continuation, not a new open

        logger.info("Circuit breaker reopened after probe failure. State: OPEN, Backoff level: {}, " +
                    "Cooldown: {} minutes (until {})",
                level, cooldownMinutes, until);

        persistState();
    }

    /**
     * Check if backoff level should decay (decrement after 48h of success).
     */
    private void checkBackoffDecay() {
        if (backoffLevel.get() == 0) {
            return; // Already at minimum
        }

        int decayHours = validationProperties.getYoutube().getCircuitBreaker().getBackoffDecayHours();
        Instant lastError = lastErrorAt.get();

        if (lastError == null) {
            return;
        }

        Instant decayThreshold = Instant.now().minusSeconds(decayHours * 3600L);
        if (lastError.isBefore(decayThreshold)) {
            int newLevel = backoffLevel.decrementAndGet();
            logger.info("Backoff level decayed to {} (no errors for {}+ hours)", newLevel, decayHours);
            lastErrorAt.set(Instant.now()); // Reset decay timer
            persistState();
        }
    }

    /**
     * Open the circuit with current backoff level.
     */
    private void openCircuit() {
        int level = backoffLevel.get();
        int cooldownMinutes = getCooldownMinutesForLevel(level);

        Instant now = Instant.now();
        Instant until = now.plusSeconds(cooldownMinutes * 60L);

        state.set(State.OPEN);
        openedAt.set(now);
        cooldownUntil.set(until);
        totalCircuitOpens.incrementAndGet();

        // Clear rolling window after opening
        synchronized (errorListLock) {
            recentErrors.clear();
        }

        logger.error("CIRCUIT BREAKER OPENED - YouTube rate limiting detected! " +
                     "State: OPEN, Backoff level: {}, Cooldown: {} minutes (until {}). " +
                     "Last error: {} - {}",
                level, cooldownMinutes, until,
                lastErrorType.get(), lastErrorMessage.get());

        persistState();
    }

    /**
     * Reopen circuit after failed HALF_OPEN probe with increased backoff.
     */
    private void reopenWithIncreasedBackoff() {
        // Reset probe flag first
        probeInProgress.set(false);

        // Increment backoff level (capped at max)
        int newLevel = Math.min(backoffLevel.incrementAndGet(), COOLDOWN_BY_LEVEL.length - 1);
        backoffLevel.set(newLevel);

        int cooldownMinutes = getCooldownMinutesForLevel(newLevel);
        Instant now = Instant.now();
        Instant until = now.plusSeconds(cooldownMinutes * 60L);

        state.set(State.OPEN);
        openedAt.set(now);
        cooldownUntil.set(until);
        totalCircuitOpens.incrementAndGet();

        logger.error("CIRCUIT BREAKER REOPENED - HALF_OPEN probe failed! " +
                     "State: OPEN, Backoff level increased to: {}, Cooldown: {} minutes (until {}). " +
                     "Last error: {} - {}",
                newLevel, cooldownMinutes, until,
                lastErrorType.get(), lastErrorMessage.get());

        persistState();
    }

    /**
     * Close the circuit (successful recovery).
     */
    private void closeCircuit() {
        state.set(State.CLOSED);
        cooldownUntil.set(null);
        // Don't reset backoffLevel - it decays naturally after sustained success
        // Don't reset openedAt - useful for diagnostics

        // Clear rolling window
        synchronized (errorListLock) {
            recentErrors.clear();
        }

        logger.info("Circuit breaker CLOSED - validation requests will resume. Backoff level: {}",
                backoffLevel.get());

        persistState();
    }

    /**
     * Get cooldown duration in minutes for a given backoff level.
     * Uses the COOLDOWN_BY_LEVEL array for the specified backoff schedule:
     * Level 0: 1 hour (60 min)
     * Level 1: 6 hours (360 min)
     * Level 2: 12 hours (720 min)
     * Level 3: 24 hours (1440 min)
     * Level 4: 48 hours (2880 min) - cap
     */
    private int getCooldownMinutesForLevel(int level) {
        int maxMinutes = validationProperties.getYoutube().getCircuitBreaker().getCooldownMaxMinutes();

        // Clamp level to valid range
        if (level < 0) {
            level = 0;
        } else if (level >= COOLDOWN_BY_LEVEL.length) {
            level = COOLDOWN_BY_LEVEL.length - 1;
        }

        // Use the predefined backoff schedule
        int cooldownMinutes = COOLDOWN_BY_LEVEL[level];
        return Math.min(cooldownMinutes, maxMinutes);
    }

    private String newCorrelationId() {
        return UUID.randomUUID().toString();
    }

    /**
     * Truncate string to max length.
     */
    private String truncate(String s, int maxLen) {
        if (s == null) return null;
        return s.length() > maxLen ? s.substring(0, maxLen) : s;
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
     * Manually reset the circuit breaker (for admin intervention).
     */
    public void reset() {
        logger.info("Circuit breaker manually reset");
        state.set(State.CLOSED);
        openedAt.set(null);
        cooldownUntil.set(null);
        backoffLevel.set(0);
        lastErrorMessage.set(null);
        lastErrorType.set(null);
        lastErrorAt.set(null);

        synchronized (errorListLock) {
            recentErrors.clear();
        }

        persistState();
    }

    /**
     * Package-private method to set state for testing.
     * DO NOT use in production code.
     */
    void setStateForTesting(State newState) {
        state.set(newState);
    }

    // ==================== Status / Metrics ====================

    /**
     * Get current circuit breaker status for monitoring.
     */
    public CircuitBreakerStatus getStatus() {
        return new CircuitBreakerStatus(
                isOpen(),
                state.get(),
                getRemainingCooldownMs(),
                openedAt.get(),
                cooldownUntil.get(),
                backoffLevel.get(),
                lastErrorType.get(),
                lastErrorMessage.get(),
                totalRateLimitErrors.get(),
                totalCircuitOpens.get()
        );
    }

    /**
     * Get current state for logging/diagnostics.
     */
    public State getCurrentState() {
        return state.get();
    }

    /**
     * Circuit breaker status for monitoring and diagnostics.
     */
    public static class CircuitBreakerStatus {
        private final boolean open;
        private final State state;
        private final long remainingCooldownMs;
        private final Instant lastOpenedAt;
        private final Instant cooldownUntil;
        private final int backoffLevel;
        private final String lastErrorType;
        private final String lastErrorMessage;
        private final int totalRateLimitErrors;
        private final int totalCircuitOpens;

        public CircuitBreakerStatus(
                boolean open,
                State state,
                long remainingCooldownMs,
                Instant lastOpenedAt,
                Instant cooldownUntil,
                int backoffLevel,
                String lastErrorType,
                String lastErrorMessage,
                int totalRateLimitErrors,
                int totalCircuitOpens
        ) {
            this.open = open;
            this.state = state;
            this.remainingCooldownMs = remainingCooldownMs;
            this.lastOpenedAt = lastOpenedAt;
            this.cooldownUntil = cooldownUntil;
            this.backoffLevel = backoffLevel;
            this.lastErrorType = lastErrorType;
            this.lastErrorMessage = lastErrorMessage;
            this.totalRateLimitErrors = totalRateLimitErrors;
            this.totalCircuitOpens = totalCircuitOpens;
        }

        public boolean isOpen() { return open; }
        public State getState() { return state; }
        public long getRemainingCooldownMs() { return remainingCooldownMs; }
        public Instant getLastOpenedAt() { return lastOpenedAt; }
        public Instant getCooldownUntil() { return cooldownUntil; }
        public int getBackoffLevel() { return backoffLevel; }
        public String getLastErrorType() { return lastErrorType; }
        public String getLastErrorMessage() { return lastErrorMessage; }
        public int getTotalRateLimitErrors() { return totalRateLimitErrors; }
        public int getTotalCircuitOpens() { return totalCircuitOpens; }

        // Legacy getters for backwards compatibility
        public int getConsecutiveErrors() { return 0; } // Deprecated - use rolling window
        public int getCurrentCooldownMinutes() { return (int) (remainingCooldownMs / 60000); }
    }
}
