package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.config.YouTubeCircuitBreakerProperties;
import com.google.api.core.ApiFuture;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.Transaction;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * Circuit breaker service for YouTube API requests.
 *
 * Implements circuit breaker pattern to prevent hammering YouTube when rate-limited.
 * State is persisted in Firestore for:
 * - Crash recovery (state survives restarts)
 * - Multi-instance coordination (all instances share state)
 *
 * States:
 * - CLOSED: Normal operation, requests allowed
 * - OPEN: Rate limiting detected, requests blocked until cooldown expires
 * - HALF_OPEN: Testing recovery, exactly ONE probe request allowed (gated via Firestore)
 *
 * Fail-safe policy: When Firestore unavailable, default to OPEN (safe).
 *
 * @see YouTubeCircuitBreakerProperties for configuration
 */
@Service
public class YouTubeCircuitBreakerService {

    private static final Logger logger = LoggerFactory.getLogger(YouTubeCircuitBreakerService.class);

    // Circuit breaker states
    public static final String STATE_CLOSED = "CLOSED";
    public static final String STATE_OPEN = "OPEN";
    public static final String STATE_HALF_OPEN = "HALF_OPEN";

    // HALF_OPEN probe tracking states
    private static final String PROBE_NONE = "NONE";
    private static final String PROBE_IN_FLIGHT = "IN_FLIGHT";

    // Rate-limit error detection patterns
    private static final String[] RATE_LIMIT_PATTERNS = {
            "confirm you're not a bot",
            "sign in to confirm",
            "LOGIN_REQUIRED",
            "confirm that you're not a bot",
            "SignInConfirmNotBotException"
    };

    private final Firestore firestore;
    private final FirestoreTimeoutProperties timeoutProperties;
    private final YouTubeCircuitBreakerProperties breakerProperties;

    public YouTubeCircuitBreakerService(
            Firestore firestore,
            FirestoreTimeoutProperties timeoutProperties,
            YouTubeCircuitBreakerProperties breakerProperties
    ) {
        this.firestore = firestore;
        this.timeoutProperties = timeoutProperties;
        this.breakerProperties = breakerProperties;

        logger.info("YouTubeCircuitBreakerService initialized - enabled: {}, threshold: {} errors in {}min",
                breakerProperties.isEnabled(),
                breakerProperties.getRollingWindowErrorThreshold(),
                breakerProperties.getRollingWindowMinutes());
    }

    /**
     * Check if a request should be allowed based on circuit breaker state.
     *
     * In HALF_OPEN state, only ONE probe request is allowed across all instances.
     * This is enforced via atomic Firestore transaction that claims the probe lease.
     *
     * @return true if request is allowed, false if circuit breaker is open
     */
    public boolean allowRequest() {
        if (!breakerProperties.isEnabled()) {
            return true;
        }

        try {
            CircuitBreakerState state = readState();

            if (state == null) {
                // No state = CLOSED (first run)
                return true;
            }

            Instant now = Instant.now();

            switch (state.state()) {
                case STATE_CLOSED:
                    return true;

                case STATE_OPEN:
                    // Check if cooldown has expired
                    if (state.cooldownUntil() != null && now.isAfter(state.cooldownUntil())) {
                        // Try to transition to HALF_OPEN and claim probe lease atomically
                        boolean claimed = tryClaimProbe(state);
                        if (claimed) {
                            logger.info("Circuit breaker transitioned to HALF_OPEN, probe lease claimed");
                            return true; // This request is the probe
                        } else {
                            // Another instance already claimed the probe, block this request
                            logger.debug("Circuit breaker cooldown expired but probe already claimed by another instance");
                            return false;
                        }
                    }
                    logger.debug("Circuit breaker OPEN, request blocked (cooldown until: {})", state.cooldownUntil());
                    return false;

                case STATE_HALF_OPEN:
                    // In HALF_OPEN, only allow if this request can claim/has the probe lease
                    // Check if probe is already in-flight
                    if (PROBE_IN_FLIGHT.equals(state.probeStatus())) {
                        // Check for probe timeout
                        if (state.probeStartedAt() != null) {
                            Instant probeTimeout = state.probeStartedAt()
                                    .plusSeconds(breakerProperties.getProbeTimeoutSeconds());
                            if (now.isAfter(probeTimeout)) {
                                // Probe timed out - treat as failure, reopen breaker
                                logger.warn("HALF_OPEN probe timed out after {}s, treating as failure",
                                        breakerProperties.getProbeTimeoutSeconds());
                                handleProbeTimeout(state);
                                return false;
                            }
                        }
                        // Probe in-flight but not timed out - block other requests
                        logger.debug("Circuit breaker HALF_OPEN, probe in-flight, blocking request");
                        return false;
                    }
                    // No probe in-flight - try to claim it
                    boolean claimed = tryClaimProbe(state);
                    if (claimed) {
                        logger.debug("Circuit breaker HALF_OPEN, probe lease claimed");
                        return true;
                    }
                    logger.debug("Circuit breaker HALF_OPEN, failed to claim probe (race), blocking request");
                    return false;

                default:
                    logger.warn("Unknown circuit breaker state: {}, defaulting to CLOSED", state.state());
                    return true;
            }

        } catch (Exception e) {
            // Fail-safe: if we can't read state, default to OPEN (safe)
            logger.error("Failed to read circuit breaker state (fail-safe: blocking request): {}", e.getMessage());
            return false;
        }
    }

    /**
     * Check if a request should be allowed and return the current status in a single call.
     * This avoids double Firestore reads when the caller needs both the allowed decision
     * and the current status (e.g., for exception messages).
     *
     * @return AllowRequestResult containing both allowed decision and current status
     */
    public AllowRequestResult tryAllowRequest() {
        if (!breakerProperties.isEnabled()) {
            return new AllowRequestResult(true, new CircuitBreakerStatus(
                    false, STATE_CLOSED, null, null, null, 0, 0
            ));
        }

        try {
            CircuitBreakerState state = readState();

            if (state == null) {
                // No state = CLOSED (first run)
                return new AllowRequestResult(true, new CircuitBreakerStatus(
                        true, STATE_CLOSED, null, null, null, 0, 0
                ));
            }

            CircuitBreakerStatus status = new CircuitBreakerStatus(
                    breakerProperties.isEnabled(),
                    state.state(),
                    state.openedAt(),
                    state.cooldownUntil(),
                    state.lastTriggeredBy(),
                    state.backoffLevel(),
                    state.consecutiveFailures()
            );

            Instant now = Instant.now();

            switch (state.state()) {
                case STATE_CLOSED:
                    return new AllowRequestResult(true, status);

                case STATE_OPEN:
                    // Check if cooldown has expired
                    if (state.cooldownUntil() != null && now.isAfter(state.cooldownUntil())) {
                        // Try to transition to HALF_OPEN and claim probe lease atomically
                        boolean claimed = tryClaimProbe(state);
                        if (claimed) {
                            logger.info("Circuit breaker transitioned to HALF_OPEN, probe lease claimed");
                            // Update status to reflect new state
                            CircuitBreakerStatus halfOpenStatus = new CircuitBreakerStatus(
                                    true, STATE_HALF_OPEN, state.openedAt(), state.cooldownUntil(),
                                    state.lastTriggeredBy(), state.backoffLevel(), state.consecutiveFailures()
                            );
                            return new AllowRequestResult(true, halfOpenStatus);
                        } else {
                            // Another instance already claimed the probe, block this request
                            logger.debug("Circuit breaker cooldown expired but probe already claimed by another instance");
                            return new AllowRequestResult(false, status);
                        }
                    }
                    logger.debug("Circuit breaker OPEN, request blocked (cooldown until: {})", state.cooldownUntil());
                    return new AllowRequestResult(false, status);

                case STATE_HALF_OPEN:
                    // In HALF_OPEN, only allow if this request can claim/has the probe lease
                    if (PROBE_IN_FLIGHT.equals(state.probeStatus())) {
                        // Check for probe timeout
                        if (state.probeStartedAt() != null) {
                            Instant probeTimeout = state.probeStartedAt()
                                    .plusSeconds(breakerProperties.getProbeTimeoutSeconds());
                            if (now.isAfter(probeTimeout)) {
                                // Probe timed out - treat as failure, reopen breaker
                                logger.warn("HALF_OPEN probe timed out after {}s, treating as failure",
                                        breakerProperties.getProbeTimeoutSeconds());
                                handleProbeTimeout(state);
                                // Return OPEN status since we're reopening
                                CircuitBreakerStatus openStatus = new CircuitBreakerStatus(
                                        true, STATE_OPEN, now, state.cooldownUntil(),
                                        "ProbeTimeout", state.backoffLevel() + 1, state.consecutiveFailures()
                                );
                                return new AllowRequestResult(false, openStatus);
                            }
                        }
                        // Probe in-flight but not timed out - block other requests
                        logger.debug("Circuit breaker HALF_OPEN, probe in-flight, blocking request");
                        return new AllowRequestResult(false, status);
                    }
                    // No probe in-flight - try to claim it
                    boolean claimed = tryClaimProbe(state);
                    if (claimed) {
                        logger.debug("Circuit breaker HALF_OPEN, probe lease claimed");
                        return new AllowRequestResult(true, status);
                    }
                    logger.debug("Circuit breaker HALF_OPEN, failed to claim probe (race), blocking request");
                    return new AllowRequestResult(false, status);

                default:
                    logger.warn("Unknown circuit breaker state: {}, defaulting to CLOSED", state.state());
                    return new AllowRequestResult(true, status);
            }

        } catch (Exception e) {
            // Fail-safe: if we can't read state, default to OPEN (safe)
            logger.error("Failed to read circuit breaker state (fail-safe: blocking request): {}", e.getMessage());
            return new AllowRequestResult(false, new CircuitBreakerStatus(
                    breakerProperties.isEnabled(),
                    "UNKNOWN",
                    null, null, null, 0, 0
            ));
        }
    }

    /**
     * Record a successful request.
     * In HALF_OPEN state, this closes the breaker.
     * In CLOSED state, may decay backoff level.
     */
    public void recordSuccess() {
        if (!breakerProperties.isEnabled()) {
            return;
        }

        try {
            CircuitBreakerState state = readState();

            if (state == null) {
                return; // No state to update
            }

            if (STATE_HALF_OPEN.equals(state.state())) {
                // Probe succeeded, close the breaker
                transitionToClosed(state);
                logger.info("Circuit breaker CLOSED (probe succeeded)");
            } else if (STATE_CLOSED.equals(state.state())) {
                // Check for backoff decay
                maybeDecayBackoff(state);
            }

        } catch (Exception e) {
            logger.warn("Failed to record success in circuit breaker: {}", e.getMessage());
        }
    }

    /**
     * Record a rate-limit error.
     * May open the breaker if threshold is reached.
     *
     * Uses true rolling window: counts N errors since windowStartAt.
     * If windowStartAt is outside the rolling window, resets to start a new window.
     */
    public void recordRateLimitError(Exception exception) {
        if (!breakerProperties.isEnabled()) {
            return;
        }

        if (!isRateLimitError(exception)) {
            return; // Not a rate-limit error
        }

        logger.warn("Rate-limit error detected: {}", exception.getMessage());

        try {
            CircuitBreakerState state = readState();

            if (state == null) {
                // First error, create initial state
                state = new CircuitBreakerState(
                        STATE_CLOSED, null, null, null, null,
                        0, null, 0, 1
                );
            }

            Instant now = Instant.now();
            Instant windowCutoff = now.minusSeconds(breakerProperties.getRollingWindowMinutes() * 60L);

            // Determine if we're within an existing window or starting a new one
            int newFailureCount;
            Instant effectiveWindowStart;

            if (state.windowStartAt() == null || state.windowStartAt().isBefore(windowCutoff)) {
                // Window expired or never set - start a new window
                newFailureCount = 1;
                effectiveWindowStart = now;
                logger.debug("Starting new rolling window at {}", effectiveWindowStart);
            } else {
                // Within existing window - increment count
                newFailureCount = state.consecutiveFailures() + 1;
                effectiveWindowStart = state.windowStartAt();
                logger.debug("Continuing existing window (started: {}), failure count: {}",
                        effectiveWindowStart, newFailureCount);
            }

            // Check if in HALF_OPEN state - probe failed, reopen immediately
            if (STATE_HALF_OPEN.equals(state.state())) {
                reopenBreaker(state, exception);
                return;
            }

            // Update state
            if (newFailureCount >= breakerProperties.getRollingWindowErrorThreshold()) {
                // Open the breaker
                openBreaker(state, exception, newFailureCount);
            } else {
                // Just record the failure
                updateFailureCount(state, newFailureCount, now, exception, effectiveWindowStart);
            }

        } catch (Exception e) {
            logger.error("Failed to record rate-limit error in circuit breaker: {}", e.getMessage());
        }
    }

    /**
     * Check if an exception is a rate-limit error.
     */
    public boolean isRateLimitError(Exception exception) {
        if (exception == null) {
            return false;
        }

        String className = exception.getClass().getSimpleName();
        String message = exception.getMessage();

        // Check class name
        if ("SignInConfirmNotBotException".equals(className)) {
            return true;
        }

        // Check message patterns
        if (message != null) {
            String lowerMessage = message.toLowerCase();
            for (String pattern : RATE_LIMIT_PATTERNS) {
                if (lowerMessage.contains(pattern.toLowerCase())) {
                    return true;
                }
            }
        }

        // Check cause recursively
        Throwable cause = exception.getCause();
        if (cause instanceof Exception && cause != exception) {
            return isRateLimitError((Exception) cause);
        }

        return false;
    }

    /**
     * Get current circuit breaker status for monitoring.
     */
    public CircuitBreakerStatus getStatus() {
        try {
            CircuitBreakerState state = readState();
            if (state == null) {
                return new CircuitBreakerStatus(
                        breakerProperties.isEnabled(),
                        STATE_CLOSED,
                        null, null, null, 0, 0
                );
            }
            return new CircuitBreakerStatus(
                    breakerProperties.isEnabled(),
                    state.state(),
                    state.openedAt(),
                    state.cooldownUntil(),
                    state.lastTriggeredBy(),
                    state.backoffLevel(),
                    state.consecutiveFailures()
            );
        } catch (Exception e) {
            logger.warn("Failed to get circuit breaker status: {}", e.getMessage());
            return new CircuitBreakerStatus(
                    breakerProperties.isEnabled(),
                    "UNKNOWN",
                    null, null, null, 0, 0
            );
        }
    }

    // ==================== Private Methods ====================

    private CircuitBreakerState readState() throws ExecutionException, InterruptedException, TimeoutException {
        DocumentReference docRef = firestore
                .collection(breakerProperties.getPersistenceCollection())
                .document(breakerProperties.getPersistenceDocumentId());

        ApiFuture<DocumentSnapshot> future = docRef.get();
        DocumentSnapshot doc = future.get(timeoutProperties.getRead(), TimeUnit.SECONDS);

        if (!doc.exists()) {
            return null;
        }

        return documentToState(doc);
    }

    private CircuitBreakerState documentToState(DocumentSnapshot doc) {
        String state = doc.getString("state");
        Timestamp openedAtTs = doc.getTimestamp("openedAt");
        Timestamp cooldownUntilTs = doc.getTimestamp("cooldownUntil");
        String lastTriggeredBy = doc.getString("lastTriggeredBy");
        String triggerMessage = doc.getString("triggerMessage");
        Long consecutiveFailures = doc.getLong("consecutiveFailures");
        Timestamp lastFailureAtTs = doc.getTimestamp("lastFailureAt");
        Long backoffLevel = doc.getLong("backoffLevel");
        Long version = doc.getLong("version");
        String probeStatus = doc.getString("probeStatus");
        Timestamp probeStartedAtTs = doc.getTimestamp("probeStartedAt");
        Timestamp windowStartAtTs = doc.getTimestamp("windowStartAt");

        return new CircuitBreakerState(
                state != null ? state : STATE_CLOSED,
                timestampToInstant(openedAtTs),
                timestampToInstant(cooldownUntilTs),
                lastTriggeredBy,
                triggerMessage,
                consecutiveFailures != null ? consecutiveFailures.intValue() : 0,
                timestampToInstant(lastFailureAtTs),
                backoffLevel != null ? backoffLevel.intValue() : 0,
                version != null ? version : 1,
                probeStatus != null ? probeStatus : PROBE_NONE,
                timestampToInstant(probeStartedAtTs),
                timestampToInstant(windowStartAtTs)
        );
    }

    private Map<String, Object> stateToDocument(CircuitBreakerState state) {
        Map<String, Object> data = new HashMap<>();
        data.put("state", state.state());
        data.put("openedAt", instantToTimestamp(state.openedAt()));
        data.put("cooldownUntil", instantToTimestamp(state.cooldownUntil()));
        data.put("lastTriggeredBy", state.lastTriggeredBy());
        data.put("triggerMessage", state.triggerMessage());
        data.put("consecutiveFailures", state.consecutiveFailures());
        data.put("lastFailureAt", instantToTimestamp(state.lastFailureAt()));
        data.put("backoffLevel", state.backoffLevel());
        data.put("version", state.version());
        data.put("probeStatus", state.probeStatus());
        data.put("probeStartedAt", instantToTimestamp(state.probeStartedAt()));
        data.put("windowStartAt", instantToTimestamp(state.windowStartAt()));
        return data;
    }

    private Instant timestampToInstant(Timestamp ts) {
        if (ts == null) {
            return null;
        }
        return Instant.ofEpochSecond(ts.getSeconds(), ts.getNanos());
    }

    private Timestamp instantToTimestamp(Instant instant) {
        if (instant == null) {
            return null;
        }
        return Timestamp.ofTimeSecondsAndNanos(instant.getEpochSecond(), instant.getNano());
    }

    /**
     * Try to atomically claim the probe lease and transition to HALF_OPEN.
     * Only one instance can successfully claim the probe.
     *
     * @return true if this instance claimed the probe, false if another instance claimed it
     */
    private boolean tryClaimProbe(CircuitBreakerState currentState) {
        try {
            DocumentReference docRef = firestore
                    .collection(breakerProperties.getPersistenceCollection())
                    .document(breakerProperties.getPersistenceDocumentId());

            final long expectedVersion = currentState.version();
            Instant now = Instant.now();

            // Create new state with probe claimed
            CircuitBreakerState newState = new CircuitBreakerState(
                    STATE_HALF_OPEN,
                    currentState.openedAt(),
                    currentState.cooldownUntil(),
                    currentState.lastTriggeredBy(),
                    currentState.triggerMessage(),
                    currentState.consecutiveFailures(),
                    currentState.lastFailureAt(),
                    currentState.backoffLevel(),
                    expectedVersion + 1,
                    PROBE_IN_FLIGHT,
                    now,
                    currentState.windowStartAt()
            );

            ApiFuture<Boolean> result = firestore.runTransaction((Transaction transaction) -> {
                DocumentSnapshot doc = transaction.get(docRef).get();

                // Check version for optimistic locking
                if (doc.exists()) {
                    Long currentVersion = doc.getLong("version");
                    if (currentVersion != null && currentVersion != expectedVersion) {
                        // Version mismatch - another instance updated state
                        return false;
                    }

                    // Also check if probe is already in-flight
                    String existingProbeStatus = doc.getString("probeStatus");
                    if (PROBE_IN_FLIGHT.equals(existingProbeStatus)) {
                        // Probe already claimed
                        return false;
                    }
                }

                transaction.set(docRef, stateToDocument(newState));
                return true;
            });

            Boolean claimed = result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            return Boolean.TRUE.equals(claimed);

        } catch (Exception e) {
            logger.error("Failed to claim probe lease: {}", e.getMessage());
            return false; // Fail-safe: don't claim if we can't persist
        }
    }

    /**
     * Handle probe timeout - treat as failure and reopen breaker.
     */
    private void handleProbeTimeout(CircuitBreakerState currentState) {
        Instant now = Instant.now();
        int newBackoffLevel = Math.min(currentState.backoffLevel() + 1, 4); // Cap at level 4

        // Calculate cooldown duration with increased backoff
        long cooldownMinutes = breakerProperties.calculateCooldownMinutes(newBackoffLevel);
        Instant cooldownUntil = now.plusSeconds(cooldownMinutes * 60);

        CircuitBreakerState newState = new CircuitBreakerState(
                STATE_OPEN,
                now,
                cooldownUntil,
                "ProbeTimeout",
                "Probe request timed out after " + breakerProperties.getProbeTimeoutSeconds() + " seconds",
                currentState.consecutiveFailures() + 1,
                now,
                newBackoffLevel,
                currentState.version() + 1,
                PROBE_NONE,
                null,
                null // Reset window on reopen
        );

        logger.warn("Reopening circuit breaker (probe timeout) - backoff level: {} -> {}, cooldown until: {}",
                currentState.backoffLevel(), newBackoffLevel, cooldownUntil);

        persistState(newState, currentState.version());
    }

    private void transitionToClosed(CircuitBreakerState currentState) {
        CircuitBreakerState newState = new CircuitBreakerState(
                STATE_CLOSED,
                null, // Clear openedAt
                null, // Clear cooldownUntil
                currentState.lastTriggeredBy(),
                currentState.triggerMessage(),
                0, // Reset failure count
                currentState.lastFailureAt(),
                currentState.backoffLevel(),
                currentState.version() + 1,
                PROBE_NONE, // Clear probe status
                null,       // Clear probe started time
                null        // Clear window start (reset failure counting)
        );
        persistState(newState, currentState.version());
    }

    private void openBreaker(CircuitBreakerState currentState, Exception exception, int failureCount) {
        Instant now = Instant.now();
        int newBackoffLevel = currentState != null ? currentState.backoffLevel() : 0;

        // Calculate cooldown duration
        long cooldownMinutes = breakerProperties.calculateCooldownMinutes(newBackoffLevel);
        Instant cooldownUntil = now.plusSeconds(cooldownMinutes * 60);

        String triggerMessage = exception.getMessage();
        if (triggerMessage != null && triggerMessage.length() > 500) {
            triggerMessage = triggerMessage.substring(0, 500);
        }

        CircuitBreakerState newState = new CircuitBreakerState(
                STATE_OPEN,
                now,
                cooldownUntil,
                exception.getClass().getSimpleName(),
                triggerMessage,
                failureCount,
                now,
                newBackoffLevel,
                currentState != null ? currentState.version() + 1 : 1,
                PROBE_NONE,
                null,
                null // Reset window on open
        );

        logger.warn("Opening circuit breaker - backoff level: {}, cooldown until: {}, triggered by: {}",
                newBackoffLevel, cooldownUntil, exception.getClass().getSimpleName());

        persistState(newState, currentState != null ? currentState.version() : 0);
    }

    private void reopenBreaker(CircuitBreakerState currentState, Exception exception) {
        Instant now = Instant.now();
        int newBackoffLevel = Math.min(currentState.backoffLevel() + 1, 4); // Cap at level 4

        // Calculate cooldown duration with increased backoff
        long cooldownMinutes = breakerProperties.calculateCooldownMinutes(newBackoffLevel);
        Instant cooldownUntil = now.plusSeconds(cooldownMinutes * 60);

        String triggerMessage = exception.getMessage();
        if (triggerMessage != null && triggerMessage.length() > 500) {
            triggerMessage = triggerMessage.substring(0, 500);
        }

        CircuitBreakerState newState = new CircuitBreakerState(
                STATE_OPEN,
                now,
                cooldownUntil,
                exception.getClass().getSimpleName(),
                triggerMessage,
                currentState.consecutiveFailures() + 1,
                now,
                newBackoffLevel,
                currentState.version() + 1,
                PROBE_NONE,
                null,
                null // Reset window on reopen
        );

        logger.warn("Reopening circuit breaker (probe failed) - backoff level: {} -> {}, cooldown until: {}",
                currentState.backoffLevel(), newBackoffLevel, cooldownUntil);

        persistState(newState, currentState.version());
    }

    private void updateFailureCount(CircuitBreakerState currentState, int newCount, Instant now, Exception exception,
                                    Instant windowStartAt) {
        String triggerMessage = exception.getMessage();
        if (triggerMessage != null && triggerMessage.length() > 500) {
            triggerMessage = triggerMessage.substring(0, 500);
        }

        CircuitBreakerState newState = new CircuitBreakerState(
                currentState.state(),
                currentState.openedAt(),
                currentState.cooldownUntil(),
                exception.getClass().getSimpleName(),
                triggerMessage,
                newCount,
                now,
                currentState.backoffLevel(),
                currentState.version() + 1,
                currentState.probeStatus(),
                currentState.probeStartedAt(),
                windowStartAt
        );

        logger.debug("Recording rate-limit failure {}/{} within rolling window (window started: {})",
                newCount, breakerProperties.getRollingWindowErrorThreshold(), windowStartAt);

        persistState(newState, currentState.version());
    }

    private void maybeDecayBackoff(CircuitBreakerState currentState) {
        if (currentState.backoffLevel() <= 0) {
            return; // Already at minimum
        }

        if (currentState.lastFailureAt() == null) {
            return; // No failure recorded
        }

        Instant decayThreshold = Instant.now().minusSeconds(breakerProperties.getBackoffDecayHours() * 3600L);
        if (currentState.lastFailureAt().isBefore(decayThreshold)) {
            // Decay backoff level
            int newLevel = currentState.backoffLevel() - 1;
            CircuitBreakerState newState = new CircuitBreakerState(
                    currentState.state(),
                    currentState.openedAt(),
                    currentState.cooldownUntil(),
                    currentState.lastTriggeredBy(),
                    currentState.triggerMessage(),
                    currentState.consecutiveFailures(),
                    Instant.now(), // Reset lastFailureAt to prevent immediate decay again
                    newLevel,
                    currentState.version() + 1,
                    currentState.probeStatus(),
                    currentState.probeStartedAt(),
                    currentState.windowStartAt()
            );

            logger.info("Decaying circuit breaker backoff level: {} -> {}", currentState.backoffLevel(), newLevel);
            persistState(newState, currentState.version());
        }
    }

    private void persistState(CircuitBreakerState newState, long expectedVersion) {
        int retries = breakerProperties.getMaxPersistenceRetries();

        for (int attempt = 1; attempt <= retries; attempt++) {
            try {
                DocumentReference docRef = firestore
                        .collection(breakerProperties.getPersistenceCollection())
                        .document(breakerProperties.getPersistenceDocumentId());

                final long versionToCheck = expectedVersion;

                ApiFuture<Boolean> result = firestore.runTransaction((Transaction transaction) -> {
                    DocumentSnapshot doc = transaction.get(docRef).get();

                    // Optimistic locking check
                    if (doc.exists()) {
                        Long currentVersion = doc.getLong("version");
                        if (currentVersion != null && currentVersion != versionToCheck) {
                            logger.debug("Version conflict: expected {}, found {}", versionToCheck, currentVersion);
                            return false;
                        }
                    }

                    transaction.set(docRef, stateToDocument(newState));
                    return true;
                });

                Boolean success = result.get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (Boolean.TRUE.equals(success)) {
                    logger.debug("Circuit breaker state persisted: {}", newState.state());
                    return;
                }

                // Version conflict, retry
                logger.warn("Circuit breaker state persist conflict (attempt {}/{})", attempt, retries);

            } catch (Exception e) {
                logger.error("Failed to persist circuit breaker state (attempt {}/{}): {}",
                        attempt, retries, e.getMessage());
            }
        }

        logger.error("Failed to persist circuit breaker state after {} retries - state change lost, will retry on next request", retries);
    }

    // ==================== Data Classes ====================

    /**
     * Internal circuit breaker state.
     */
    public record CircuitBreakerState(
            String state,
            Instant openedAt,
            Instant cooldownUntil,
            String lastTriggeredBy,
            String triggerMessage,
            int consecutiveFailures,
            Instant lastFailureAt,
            int backoffLevel,
            long version,
            // Probe tracking fields for HALF_OPEN single-probe gating
            String probeStatus,      // NONE or IN_FLIGHT
            Instant probeStartedAt,  // When probe was claimed (for timeout)
            // Rolling window tracking - when the current failure counting window started
            Instant windowStartAt
    ) {
        /**
         * Constructor for backwards compatibility (without probe/window fields).
         */
        public CircuitBreakerState(
                String state, Instant openedAt, Instant cooldownUntil,
                String lastTriggeredBy, String triggerMessage,
                int consecutiveFailures, Instant lastFailureAt,
                int backoffLevel, long version
        ) {
            this(state, openedAt, cooldownUntil, lastTriggeredBy, triggerMessage,
                    consecutiveFailures, lastFailureAt, backoffLevel, version,
                    PROBE_NONE, null, null);
        }
    }

    /**
     * Circuit breaker status for monitoring/API responses.
     */
    public record CircuitBreakerStatus(
            boolean enabled,
            String state,
            Instant openedAt,
            Instant cooldownUntil,
            String lastTriggeredBy,
            int backoffLevel,
            int consecutiveFailures
    ) {}

    /**
     * Result of tryAllowRequest() - combines allowed decision with current status.
     * Avoids double Firestore read when caller needs both pieces of information.
     */
    public record AllowRequestResult(
            boolean allowed,
            CircuitBreakerStatus status
    ) {}
}
