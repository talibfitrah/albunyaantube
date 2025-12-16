package com.albunyaan.tube.integration;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.repository.SystemSettingsRepository;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Integration tests for YouTubeCircuitBreaker with Firestore persistence.
 *
 * Tests verify:
 * - Circuit breaker state survives restart (persisted in Firestore)
 * - Multiple instances share breaker state
 * - Stale OPEN state transitions to HALF_OPEN on startup
 * - Optimistic locking prevents race conditions
 */
public class CircuitBreakerIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private SystemSettingsRepository systemSettingsRepository;

    private ValidationProperties validationProperties;

    @Override
    protected String[] getCollectionsToClean() {
        return new String[]{
                "system_settings",
                "categories",
                "channels",
                "playlists",
                "videos"
        };
    }

    @BeforeEach
    public void setUp() {
        validationProperties = createDefaultProperties();
    }

    private ValidationProperties createDefaultProperties() {
        ValidationProperties props = new ValidationProperties();
        props.getYoutube().getCircuitBreaker().setEnabled(true);
        props.getYoutube().getCircuitBreaker().setCooldownBaseMinutes(1);
        props.getYoutube().getCircuitBreaker().setCooldownMaxMinutes(10);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(1);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setWindowMinutes(10);
        props.getYoutube().getCircuitBreaker().setBackoffDecayHours(48);
        props.getYoutube().getCircuitBreaker().setProbeTimeoutSeconds(30);
        return props;
    }

    @Test
    @DisplayName("Circuit breaker state should survive restart (Firestore persistence)")
    void circuitBreakerState_shouldSurviveRestart() {
        // Arrange - create first instance and open circuit
        YouTubeCircuitBreaker breaker1 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker1.loadPersistedState();

        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker1.recordRateLimitError(rateLimitError);

        assertTrue(breaker1.isOpen(), "Breaker 1 should be open after rate limit error");
        assertEquals(YouTubeCircuitBreaker.State.OPEN, breaker1.getCurrentState());

        // Act - simulate restart by creating new instance
        YouTubeCircuitBreaker breaker2 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker2.loadPersistedState();

        // Assert - new instance should load persisted OPEN state
        assertTrue(breaker2.isOpen(), "Breaker 2 should be open after loading persisted state");
        assertEquals(YouTubeCircuitBreaker.State.OPEN, breaker2.getCurrentState());
    }

    @Test
    @DisplayName("Multiple instances should share circuit breaker state")
    void multipleInstances_shouldShareBreakerState() {
        // Arrange - create two instances
        YouTubeCircuitBreaker breaker1 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        YouTubeCircuitBreaker breaker2 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker1.loadPersistedState();
        breaker2.loadPersistedState();

        // Both should start closed
        assertFalse(breaker1.isOpen());
        assertFalse(breaker2.isOpen());

        // Act - open circuit on instance 1
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker1.recordRateLimitError(rateLimitError);

        // Assert - instance 2 should see the open state on next isOpen() call
        assertTrue(breaker1.isOpen(), "Breaker 1 should be open");
        assertTrue(breaker2.isOpen(), "Breaker 2 should see open state from Firestore");
    }

    @Test
    @DisplayName("Stale OPEN state should transition to HALF_OPEN on startup")
    void staleOpenState_shouldTransitionToHalfOpenOnStartup() throws Exception {
        // Arrange - manually write an expired OPEN state to Firestore
        Map<String, Object> expiredState = Map.of(
                "state", "OPEN",
                "openedAtEpoch", System.currentTimeMillis() - 7200000, // 2 hours ago
                "cooldownUntilEpoch", System.currentTimeMillis() - 3600000, // 1 hour ago (expired)
                "backoffLevel", 0,
                "version", 1L
        );
        systemSettingsRepository.save("youtube_circuit_breaker", new java.util.HashMap<>(expiredState));

        // Act - create new instance (simulates startup)
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        // Assert - should have transitioned to HALF_OPEN
        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, breaker.getCurrentState(),
                "Stale OPEN state should transition to HALF_OPEN on startup");
        assertFalse(breaker.isOpen(), "HALF_OPEN state should allow the first request");
    }

    @Test
    @DisplayName("Circuit breaker should persist state changes to Firestore")
    void circuitBreaker_shouldPersistStateChanges() throws Exception {
        // Arrange
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        // Act - trigger state change
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker.recordRateLimitError(rateLimitError);

        // Assert - verify state is persisted in Firestore
        Optional<Map<String, Object>> stored = systemSettingsRepository.load("youtube_circuit_breaker");
        assertTrue(stored.isPresent(), "State should be persisted to Firestore");

        Map<String, Object> data = stored.get();
        assertEquals("OPEN", data.get("state"));
        assertNotNull(data.get("openedAtEpoch"));
        assertNotNull(data.get("cooldownUntilEpoch"));
        assertEquals(0, ((Number) data.get("backoffLevel")).intValue());
    }

    @Test
    @DisplayName("HALF_OPEN probe success should close circuit")
    void halfOpenProbeSuccess_shouldCloseCircuit() throws Exception {
        // Arrange - set up HALF_OPEN state
        Map<String, Object> halfOpenState = Map.of(
                "state", "HALF_OPEN",
                "openedAtEpoch", System.currentTimeMillis() - 3600000,
                "cooldownUntilEpoch", System.currentTimeMillis() - 1000,
                "backoffLevel", 0,
                "version", 1L
        );
        systemSettingsRepository.save("youtube_circuit_breaker", new java.util.HashMap<>(halfOpenState));

        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, breaker.getCurrentState());

        // Act - acquire probe permit and record success
        assertTrue(breaker.allowProbe(), "Should acquire probe permit");
        breaker.recordSuccess();

        // Assert - should transition to CLOSED
        assertEquals(YouTubeCircuitBreaker.State.CLOSED, breaker.getCurrentState());
        assertFalse(breaker.isOpen());

        // Verify persisted state
        Optional<Map<String, Object>> stored = systemSettingsRepository.load("youtube_circuit_breaker");
        assertTrue(stored.isPresent());
        assertEquals("CLOSED", stored.get().get("state"));
    }

    @Test
    @DisplayName("HALF_OPEN probe failure should reopen with increased backoff")
    void halfOpenProbeFailure_shouldReopenWithIncreasedBackoff() throws Exception {
        // Arrange - set up HALF_OPEN state with backoff level 1
        Map<String, Object> halfOpenState = Map.of(
                "state", "HALF_OPEN",
                "openedAtEpoch", System.currentTimeMillis() - 3600000,
                "cooldownUntilEpoch", System.currentTimeMillis() - 1000,
                "backoffLevel", 1,
                "version", 1L
        );
        systemSettingsRepository.save("youtube_circuit_breaker", new java.util.HashMap<>(halfOpenState));

        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, breaker.getCurrentState());

        // Act - record rate limit error (probe failed)
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker.recordRateLimitError(rateLimitError);

        // Assert - should transition back to OPEN with increased backoff
        assertEquals(YouTubeCircuitBreaker.State.OPEN, breaker.getCurrentState());
        assertTrue(breaker.isOpen());

        // Verify backoff level increased
        Optional<Map<String, Object>> stored = systemSettingsRepository.load("youtube_circuit_breaker");
        assertTrue(stored.isPresent());
        assertEquals("OPEN", stored.get().get("state"));
        assertEquals(2, ((Number) stored.get().get("backoffLevel")).intValue(),
                "Backoff level should increase from 1 to 2");
    }

    @Test
    @DisplayName("Concurrent state updates should use optimistic locking")
    void concurrentUpdates_shouldUseOptimisticLocking() throws Exception {
        // Arrange - create two instances
        YouTubeCircuitBreaker breaker1 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        YouTubeCircuitBreaker breaker2 = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker1.loadPersistedState();
        breaker2.loadPersistedState();

        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(2);
        AtomicInteger successCount = new AtomicInteger(0);

        // Act - both instances try to record errors concurrently
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");

        Thread t1 = new Thread(() -> {
            try {
                startLatch.await();
                breaker1.recordRateLimitError(rateLimitError);
                successCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        Thread t2 = new Thread(() -> {
            try {
                startLatch.await();
                breaker2.recordRateLimitError(rateLimitError);
                successCount.incrementAndGet();
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            } finally {
                doneLatch.countDown();
            }
        });

        t1.start();
        t2.start();
        startLatch.countDown();
        assertTrue(doneLatch.await(5, TimeUnit.SECONDS), "Threads should complete within timeout");

        // Assert - both should complete (optimistic locking handles conflicts via retry)
        assertEquals(2, successCount.get(), "Both updates should succeed (with retries)");

        // Final state should be OPEN
        Optional<Map<String, Object>> stored = systemSettingsRepository.load("youtube_circuit_breaker");
        assertTrue(stored.isPresent());
        assertEquals("OPEN", stored.get().get("state"));
    }

    @Test
    @DisplayName("isCircuitBreakerBlocking should be alias for isOpen")
    void isCircuitBreakerBlocking_shouldBeAliasForIsOpen() {
        // Arrange
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        // Initially closed
        assertFalse(breaker.isCircuitBreakerBlocking());
        assertEquals(breaker.isOpen(), breaker.isCircuitBreakerBlocking());

        // After opening
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker.recordRateLimitError(rateLimitError);

        assertTrue(breaker.isCircuitBreakerBlocking());
        assertEquals(breaker.isOpen(), breaker.isCircuitBreakerBlocking());
    }

    @Test
    @DisplayName("Reset should clear state and persist to Firestore")
    void reset_shouldClearStateAndPersist() throws Exception {
        // Arrange - open circuit
        YouTubeCircuitBreaker breaker = new YouTubeCircuitBreaker(validationProperties, systemSettingsRepository);
        breaker.loadPersistedState();

        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        breaker.recordRateLimitError(rateLimitError);
        assertTrue(breaker.isOpen());

        // Act
        breaker.reset();

        // Assert - should be closed
        assertFalse(breaker.isOpen());
        assertEquals(YouTubeCircuitBreaker.State.CLOSED, breaker.getCurrentState());

        // Verify persisted state is reset
        Optional<Map<String, Object>> stored = systemSettingsRepository.load("youtube_circuit_breaker");
        assertTrue(stored.isPresent());
        assertEquals("CLOSED", stored.get().get("state"));
        assertEquals(0, ((Number) stored.get().get("backoffLevel")).intValue());
    }
}
