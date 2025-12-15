package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for YouTubeThrottler
 *
 * Tests verify:
 * - Throttling introduces delay between requests
 * - Throttling can be disabled
 * - Configuration values are read correctly
 */
class YouTubeThrottlerTest {

    private ValidationProperties validationProperties;
    private YouTubeThrottler throttler;

    @BeforeEach
    void setUp() {
        validationProperties = createDefaultProperties();
        throttler = new YouTubeThrottler(validationProperties);
    }

    private ValidationProperties createDefaultProperties() {
        ValidationProperties props = new ValidationProperties();
        props.getYoutube().getThrottle().setEnabled(true);
        props.getYoutube().getThrottle().setDelayBetweenItemsMs(100); // Short delay for testing
        props.getYoutube().getThrottle().setJitterMs(50);
        return props;
    }

    @Test
    @DisplayName("Should report enabled when configured as enabled")
    void shouldReportEnabledWhenEnabled() {
        assertTrue(throttler.isEnabled());
    }

    @Test
    @DisplayName("Should report disabled when configured as disabled")
    void shouldReportDisabledWhenDisabled() {
        validationProperties.getYoutube().getThrottle().setEnabled(false);
        throttler = new YouTubeThrottler(validationProperties);
        assertFalse(throttler.isEnabled());
    }

    @Test
    @DisplayName("Should return configured delay")
    void shouldReturnConfiguredDelay() {
        assertEquals(100, throttler.getDelayMs());
    }

    @Test
    @DisplayName("Should return configured jitter")
    void shouldReturnConfiguredJitter() {
        assertEquals(50, throttler.getJitterMs());
    }

    @Test
    @DisplayName("Throttle should not throw when enabled")
    void throttleShouldNotThrowWhenEnabled() {
        assertDoesNotThrow(() -> throttler.throttle());
    }

    @Test
    @DisplayName("Throttle should not throw when disabled")
    void throttleShouldNotThrowWhenDisabled() {
        validationProperties.getYoutube().getThrottle().setEnabled(false);
        throttler = new YouTubeThrottler(validationProperties);
        assertDoesNotThrow(() -> throttler.throttle());
    }

    @Test
    @DisplayName("First throttle call should return quickly")
    void firstThrottleCallShouldReturnQuickly() {
        long start = System.currentTimeMillis();
        throttler.throttle();
        long elapsed = System.currentTimeMillis() - start;

        // First call should be quick (no previous request to wait for)
        assertTrue(elapsed < 50, "First call should be quick, was " + elapsed + "ms");
    }

    @Test
    @DisplayName("Second throttle call should introduce delay")
    void secondThrottleCallShouldIntroduceDelay() {
        // First call - should be quick
        throttler.throttle();

        // Second call - should wait
        long start = System.currentTimeMillis();
        throttler.throttle();
        long elapsed = System.currentTimeMillis() - start;

        // Second call should have waited at least some of the delay
        // (we use >= 50ms to account for timing variance, config is 100ms + 50ms jitter)
        assertTrue(elapsed >= 50, "Second call should wait, only waited " + elapsed + "ms");
    }

    @Test
    @DisplayName("Throttle should skip delay when disabled")
    void throttleShouldSkipDelayWhenDisabled() {
        validationProperties.getYoutube().getThrottle().setEnabled(false);
        throttler = new YouTubeThrottler(validationProperties);

        // First call
        throttler.throttle();

        // Second call - should be quick when disabled
        long start = System.currentTimeMillis();
        throttler.throttle();
        long elapsed = System.currentTimeMillis() - start;

        // Should be quick when disabled
        assertTrue(elapsed < 50, "Throttle should skip delay when disabled, was " + elapsed + "ms");
    }

    @Test
    @DisplayName("Throttle should handle zero jitter")
    void throttleShouldHandleZeroJitter() {
        validationProperties.getYoutube().getThrottle().setJitterMs(0);
        throttler = new YouTubeThrottler(validationProperties);

        assertDoesNotThrow(() -> {
            throttler.throttle();
            throttler.throttle();
        });
    }

    @Test
    @DisplayName("Throttle should handle zero delay")
    void throttleShouldHandleZeroDelay() {
        validationProperties.getYoutube().getThrottle().setDelayBetweenItemsMs(0);
        throttler = new YouTubeThrottler(validationProperties);

        assertDoesNotThrow(() -> {
            throttler.throttle();
            throttler.throttle();
        });
    }
}
