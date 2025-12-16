package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for YouTubeCircuitBreaker
 *
 * Tests verify:
 * - Circuit breaker opens on rate limit errors
 * - Circuit breaker respects cooldown period
 * - Rate limit error detection works correctly
 * - Exponential backoff works as expected
 * - Reset functionality works
 */
class YouTubeCircuitBreakerTest {

    private ValidationProperties validationProperties;
    private YouTubeCircuitBreaker circuitBreaker;

    @BeforeEach
    void setUp() {
        validationProperties = createDefaultProperties();
        // Pass null for SystemSettingsRepository in tests (no persistence)
        circuitBreaker = new YouTubeCircuitBreaker(validationProperties, null);
    }

    private ValidationProperties createDefaultProperties() {
        ValidationProperties props = new ValidationProperties();
        // Set up default values for testing
        props.getYoutube().getCircuitBreaker().setEnabled(true);
        props.getYoutube().getCircuitBreaker().setCooldownBaseMinutes(1); // Short cooldown for testing
        props.getYoutube().getCircuitBreaker().setCooldownMaxMinutes(10);
        // Rolling window: 1 error in 10 minutes opens circuit (for easy testing)
        props.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(1);
        props.getYoutube().getCircuitBreaker().getRollingWindow().setWindowMinutes(10);
        // Legacy properties (for backwards compatibility)
        props.getYoutube().getCircuitBreaker().setCooldownMinutes(1);
        props.getYoutube().getCircuitBreaker().setMaxRateLimitErrorsToOpen(1);
        props.getYoutube().getCircuitBreaker().setMaxCooldownMinutes(10);
        props.getYoutube().getCircuitBreaker().setBackoffMultiplier(2.0);
        return props;
    }

    @Test
    @DisplayName("Circuit should be closed initially")
    void circuitShouldBeClosedInitially() {
        assertFalse(circuitBreaker.isOpen());
        assertEquals(0, circuitBreaker.getRemainingCooldownMs());
    }

    @Test
    @DisplayName("Circuit should open after rate limit error")
    void circuitShouldOpenAfterRateLimitError() {
        // Arrange
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");

        // Act
        circuitBreaker.recordRateLimitError(rateLimitError);

        // Assert
        assertTrue(circuitBreaker.isOpen());
        assertTrue(circuitBreaker.getRemainingCooldownMs() > 0);
    }

    @Test
    @DisplayName("Should detect SignInConfirmNotBotException message as rate limit error")
    void shouldDetectSignInConfirmMessage() {
        Exception error = new RuntimeException("Sign in to confirm you're not a bot");
        assertTrue(circuitBreaker.isRateLimitError(error));
    }

    @Test
    @DisplayName("Should detect ReCaptcha message as rate limit error")
    void shouldDetectReCaptchaMessage() {
        Exception error = new RuntimeException("reCAPTCHA required");
        assertTrue(circuitBreaker.isRateLimitError(error));
    }

    @Test
    @DisplayName("Should detect unusual traffic message as rate limit error")
    void shouldDetectUnusualTrafficMessage() {
        Exception error = new RuntimeException("Unusual traffic from your computer network");
        assertTrue(circuitBreaker.isRateLimitError(error));
    }

    @Test
    @DisplayName("Should detect too many requests message as rate limit error")
    void shouldDetectTooManyRequestsMessage() {
        Exception error = new RuntimeException("Too many requests");
        assertTrue(circuitBreaker.isRateLimitError(error));
    }

    @Test
    @DisplayName("Should detect 429 status code as rate limit error")
    void shouldDetect429StatusCode() {
        Exception error = new RuntimeException("HTTP error 429");
        assertTrue(circuitBreaker.isRateLimitError(error));
    }

    @Test
    @DisplayName("Should not detect regular exceptions as rate limit errors")
    void shouldNotDetectRegularExceptionsAsRateLimitErrors() {
        Exception error = new RuntimeException("Video not found");
        assertFalse(circuitBreaker.isRateLimitError(error));

        Exception networkError = new IOException("Connection timeout");
        assertFalse(circuitBreaker.isRateLimitError(networkError));

        Exception extractionError = new ExtractionException("Failed to parse response");
        assertFalse(circuitBreaker.isRateLimitError(extractionError));
    }

    @Test
    @DisplayName("Should detect rate limit in exception cause")
    void shouldDetectRateLimitInCause() {
        Exception cause = new RuntimeException("sign in to confirm that you're not a bot");
        Exception wrapper = new RuntimeException("Extraction failed", cause);
        assertTrue(circuitBreaker.isRateLimitError(wrapper));
    }

    @Test
    @DisplayName("Circuit should not open when disabled")
    void circuitShouldNotOpenWhenDisabled() {
        // Arrange
        validationProperties.getYoutube().getCircuitBreaker().setEnabled(false);
        circuitBreaker = new YouTubeCircuitBreaker(validationProperties, null);
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");

        // Act
        circuitBreaker.recordRateLimitError(rateLimitError);

        // Assert - circuit stays closed when disabled
        assertFalse(circuitBreaker.isOpen());
    }

    @Test
    @DisplayName("Reset should close circuit and clear state")
    void resetShouldCloseCircuitAndClearState() {
        // Arrange - open the circuit
        Exception rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        circuitBreaker.recordRateLimitError(rateLimitError);
        assertTrue(circuitBreaker.isOpen());

        // Act
        circuitBreaker.reset();

        // Assert
        assertFalse(circuitBreaker.isOpen());
        assertEquals(0, circuitBreaker.getRemainingCooldownMs());

        YouTubeCircuitBreaker.CircuitBreakerStatus status = circuitBreaker.getStatus();
        assertEquals(YouTubeCircuitBreaker.State.CLOSED, status.getState());
        assertEquals(0, status.getCurrentCooldownMinutes());
        assertEquals(0, status.getBackoffLevel());
    }

    @Test
    @DisplayName("Success should clear rolling window errors")
    void successShouldClearRollingWindowErrors() {
        // Arrange - configure to require 3 errors in window to open
        validationProperties.getYoutube().getCircuitBreaker().getRollingWindow().setErrorThreshold(3);
        circuitBreaker = new YouTubeCircuitBreaker(validationProperties, null);

        // Record 2 errors (not enough to open)
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        assertFalse(circuitBreaker.isOpen()); // Still closed (only 2 errors, need 3)

        // Act - record success (in new implementation, success clears rolling window)
        circuitBreaker.recordSuccess();

        // Record 2 more errors (should not open since rolling window was cleared)
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));

        // Assert - should still be closed (only 2 errors in window after clear)
        assertFalse(circuitBreaker.isOpen());
    }

    @Test
    @DisplayName("Status should report correct metrics")
    void statusShouldReportCorrectMetrics() {
        // Arrange
        Exception error = new RuntimeException("Sign in to confirm you're not a bot");
        circuitBreaker.recordRateLimitError(error);

        // Act
        YouTubeCircuitBreaker.CircuitBreakerStatus status = circuitBreaker.getStatus();

        // Assert
        assertTrue(status.isOpen());
        assertEquals(YouTubeCircuitBreaker.State.OPEN, status.getState());
        assertTrue(status.getRemainingCooldownMs() > 0);
        assertNotNull(status.getLastOpenedAt());
        assertNotNull(status.getCooldownUntil());
        assertEquals(0, status.getBackoffLevel()); // First open is level 0
        assertEquals(1, status.getTotalRateLimitErrors());
        assertEquals(1, status.getTotalCircuitOpens());
        assertNotNull(status.getLastErrorMessage());
        assertEquals("RuntimeException", status.getLastErrorType());
    }

    @Test
    @DisplayName("Failed probe should increase backoff level")
    void failedProbeShouldIncreaseBackoffLevel() {
        Exception error = new RuntimeException("rate limit");

        // First error - opens circuit at backoff level 0
        circuitBreaker.recordRateLimitError(error);
        assertEquals(0, circuitBreaker.getStatus().getBackoffLevel(), "First open should be level 0");
        assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState());

        // Manually transition to HALF_OPEN (simulating cooldown expiry)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, circuitBreaker.getStatus().getState());

        // Record another error while in HALF_OPEN - this simulates a failed probe
        // and should increase the backoff level
        circuitBreaker.recordRateLimitError(error);

        // Backoff level should have increased to 1
        assertEquals(1, circuitBreaker.getStatus().getBackoffLevel(),
                "Failed probe should increase backoff level to 1");
        assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState(),
                "Failed probe should reopen circuit");
    }

    @Test
    @DisplayName("Backoff level should increase with each failed probe cycle")
    void backoffLevelShouldIncreaseWithEachFailedProbe() {
        Exception error = new RuntimeException("rate limit");

        // Cycle 1: CLOSED → OPEN (level 0)
        circuitBreaker.recordRateLimitError(error);
        assertEquals(0, circuitBreaker.getStatus().getBackoffLevel());

        // Cycle 1: OPEN → HALF_OPEN → OPEN (level 1)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(1, circuitBreaker.getStatus().getBackoffLevel());

        // Cycle 2: OPEN → HALF_OPEN → OPEN (level 2)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(2, circuitBreaker.getStatus().getBackoffLevel());

        // Cycle 3: OPEN → HALF_OPEN → OPEN (level 3)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(3, circuitBreaker.getStatus().getBackoffLevel());

        // Cycle 4: OPEN → HALF_OPEN → OPEN (level 4 - cap)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(4, circuitBreaker.getStatus().getBackoffLevel(), "Should cap at level 4");

        // Cycle 5: Should stay at cap (level 4)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(4, circuitBreaker.getStatus().getBackoffLevel(), "Should stay at cap");
    }

    @Test
    @DisplayName("Reset should clear backoff level")
    void resetShouldClearBackoffLevel() {
        Exception error = new RuntimeException("rate limit");

        // Build up backoff level to 2
        circuitBreaker.recordRateLimitError(error);
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        circuitBreaker.recordRateLimitError(error);
        assertEquals(2, circuitBreaker.getStatus().getBackoffLevel());

        // Reset should clear backoff level
        circuitBreaker.reset();
        assertEquals(0, circuitBreaker.getStatus().getBackoffLevel(), "Reset clears backoff level");
        assertEquals(YouTubeCircuitBreaker.State.CLOSED, circuitBreaker.getStatus().getState());
    }

    @Test
    @DisplayName("Non-rate-limit probe failure should clear probe permit and reopen without increasing backoff")
    void nonRateLimitProbeFailureShouldClearProbeAndReopenWithoutIncreasingBackoff() {
        Exception rateLimitError = new RuntimeException("rate limit");
        Exception networkError = new IOException("Connection timeout");

        // Open circuit at level 0
        circuitBreaker.recordRateLimitError(rateLimitError);
        assertEquals(0, circuitBreaker.getStatus().getBackoffLevel());

        // Transition to HALF_OPEN
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);

        // Acquire probe permit (required before recordProbeFailure will have effect)
        assertTrue(circuitBreaker.allowProbe(), "Should acquire probe permit");

        // Simulate probe failure with non-rate-limit error (network timeout)
        circuitBreaker.recordProbeFailure(networkError);

        // Should reopen circuit but NOT increase backoff level
        assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState(),
                "Should reopen circuit after probe failure");
        assertEquals(0, circuitBreaker.getStatus().getBackoffLevel(),
                "Backoff level should NOT increase for non-rate-limit errors");
    }

    @Test
    @DisplayName("Probe failure should clear probe permit allowing new probe after cooldown")
    void probeFailureShouldClearProbePermit() {
        Exception rateLimitError = new RuntimeException("rate limit");
        Exception networkError = new IOException("Connection timeout");

        // Open circuit
        circuitBreaker.recordRateLimitError(rateLimitError);

        // Transition to HALF_OPEN and acquire probe permit
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        assertTrue(circuitBreaker.allowProbe(), "First probe should be allowed");
        assertFalse(circuitBreaker.allowProbe(), "Second probe should be blocked");

        // Record probe failure
        circuitBreaker.recordProbeFailure(networkError);

        // Circuit should be OPEN again
        assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState());

        // After transitioning back to HALF_OPEN, probe permit should be available again
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        assertTrue(circuitBreaker.allowProbe(), "Probe should be allowed after previous probe failure cleared permit");
    }

    @Test
    @DisplayName("In HALF_OPEN state: first request proceeds (isOpen=false), second is blocked (isOpen=true)")
    void halfOpenState_firstRequestProceeds_secondIsBlocked() {
        Exception rateLimitError = new RuntimeException("rate limit");

        // Open circuit
        circuitBreaker.recordRateLimitError(rateLimitError);
        assertEquals(YouTubeCircuitBreaker.State.OPEN, circuitBreaker.getStatus().getState());

        // Transition to HALF_OPEN (simulating cooldown expiry)
        circuitBreaker.setStateForTesting(YouTubeCircuitBreaker.State.HALF_OPEN);
        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, circuitBreaker.getStatus().getState());

        // First request: isOpen() should return false (no probe in progress yet)
        assertFalse(circuitBreaker.isOpen(),
                "isOpen() should return false in HALF_OPEN when no probe in progress");

        // First request: allowProbe() acquires permit and returns true
        assertTrue(circuitBreaker.allowProbe(),
                "allowProbe() should return true for first request");

        // Now probe is in progress - verify state
        assertTrue(circuitBreaker.isProbeRequest(),
                "isProbeRequest() should return true after acquiring permit");

        // Second request: isOpen() should return true (probe in progress)
        assertTrue(circuitBreaker.isOpen(),
                "isOpen() should return true in HALF_OPEN when probe is in progress");

        // Second request: allowProbe() should return false (permit already taken)
        assertFalse(circuitBreaker.allowProbe(),
                "allowProbe() should return false for second request");

        // State should still be HALF_OPEN
        assertEquals(YouTubeCircuitBreaker.State.HALF_OPEN, circuitBreaker.getCurrentState(),
                "State should still be HALF_OPEN");
    }

    @Test
    @DisplayName("In CLOSED state: isOpen returns false and allowProbe returns true")
    void closedState_allowsRequests() {
        // Initially CLOSED
        assertEquals(YouTubeCircuitBreaker.State.CLOSED, circuitBreaker.getStatus().getState());

        // isOpen() should return false
        assertFalse(circuitBreaker.isOpen(), "isOpen() should return false in CLOSED state");

        // allowProbe() should return true (no-op in CLOSED state)
        assertTrue(circuitBreaker.allowProbe(), "allowProbe() should return true in CLOSED state");

        // Can call allowProbe() multiple times in CLOSED state (it's a no-op)
        assertTrue(circuitBreaker.allowProbe(), "allowProbe() should return true again in CLOSED state");
    }
}
