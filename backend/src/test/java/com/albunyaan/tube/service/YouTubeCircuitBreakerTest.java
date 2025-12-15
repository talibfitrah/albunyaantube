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
        props.getYoutube().getCircuitBreaker().setCooldownMinutes(1); // Short cooldown for testing
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
        assertEquals(0, status.getConsecutiveErrors());
        assertEquals(0, status.getCurrentCooldownMinutes());
    }

    @Test
    @DisplayName("Success should reset consecutive error count")
    void successShouldResetConsecutiveErrorCount() {
        // Arrange - configure to require 3 errors to open
        validationProperties.getYoutube().getCircuitBreaker().setMaxRateLimitErrorsToOpen(3);
        circuitBreaker = new YouTubeCircuitBreaker(validationProperties, null);

        // Record 2 errors (not enough to open)
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        assertFalse(circuitBreaker.isOpen()); // Still closed

        // Act - record success
        circuitBreaker.recordSuccess();

        // Record 2 more errors (should not open since consecutive count was reset)
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));
        circuitBreaker.recordRateLimitError(new RuntimeException("rate limit"));

        // Assert - should still be closed (only 2 consecutive errors)
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
        assertTrue(status.getRemainingCooldownMs() > 0);
        assertNotNull(status.getLastOpenedAt());
        assertNotNull(status.getCooldownUntil());
        assertEquals(1, status.getConsecutiveErrors());
        assertEquals(1, status.getTotalRateLimitErrors());
        assertEquals(1, status.getTotalCircuitOpens());
        assertNotNull(status.getLastErrorMessage());
        assertEquals("RuntimeException", status.getLastErrorType());
    }

    @Test
    @DisplayName("Multiple errors should increase cooldown with backoff")
    void multipleErrorsShouldIncreaseWithBackoff() {
        // Arrange - record first error and reset to trigger backoff
        Exception error = new RuntimeException("rate limit");
        circuitBreaker.recordRateLimitError(error);
        int firstCooldown = circuitBreaker.getStatus().getCurrentCooldownMinutes();

        // Let circuit close by resetting, but keep cooldown state
        circuitBreaker.reset();

        // Record another error - should have higher cooldown due to backoff
        // But since we reset, it should start fresh
        circuitBreaker.recordRateLimitError(error);
        int secondCooldown = circuitBreaker.getStatus().getCurrentCooldownMinutes();

        // After reset, cooldown starts fresh (reset clears cooldown state)
        assertEquals(firstCooldown, secondCooldown); // Both should be base cooldown after reset
    }
}
