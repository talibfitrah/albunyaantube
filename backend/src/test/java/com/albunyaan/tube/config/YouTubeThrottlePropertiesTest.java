package com.albunyaan.tube.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for YouTubeThrottleProperties
 *
 * Tests verify:
 * - Default values are correct
 * - calculateDelayWithJitter() works as expected
 */
class YouTubeThrottlePropertiesTest {

    private YouTubeThrottleProperties properties;

    @BeforeEach
    void setUp() {
        properties = new YouTubeThrottleProperties();
    }

    @Test
    @DisplayName("Should have correct default values")
    void defaultValues() {
        assertTrue(properties.isEnabled());
        assertEquals(3000, properties.getDelayBetweenItemsMs());
        assertEquals(1000, properties.getJitterMs());
    }

    @Test
    @DisplayName("Should return 0 delay when disabled")
    void calculateDelayWithJitter_whenDisabled_returnsZero() {
        properties.setEnabled(false);
        assertEquals(0, properties.calculateDelayWithJitter());
    }

    @Test
    @DisplayName("Should return 0 delay when delay is 0")
    void calculateDelayWithJitter_whenDelayZero_returnsZero() {
        properties.setEnabled(true);
        properties.setDelayBetweenItemsMs(0);
        assertEquals(0, properties.calculateDelayWithJitter());
    }

    @Test
    @DisplayName("Should return base delay when jitter is 0")
    void calculateDelayWithJitter_whenJitterZero_returnsBaseDelay() {
        properties.setEnabled(true);
        properties.setDelayBetweenItemsMs(1000);
        properties.setJitterMs(0);
        assertEquals(1000, properties.calculateDelayWithJitter());
    }

    @Test
    @DisplayName("Should return delay within expected range when jitter is set")
    void calculateDelayWithJitter_withJitter_returnsWithinRange() {
        properties.setEnabled(true);
        properties.setDelayBetweenItemsMs(1000);
        properties.setJitterMs(500);

        // Run multiple times to verify randomness is within bounds
        for (int i = 0; i < 100; i++) {
            long delay = properties.calculateDelayWithJitter();
            assertTrue(delay >= 1000, "Delay should be at least base delay");
            assertTrue(delay < 1500, "Delay should be less than base + jitter");
        }
    }

    @Test
    @DisplayName("Should allow setting all properties")
    void setters() {
        properties.setEnabled(false);
        properties.setDelayBetweenItemsMs(5000);
        properties.setJitterMs(2000);

        assertFalse(properties.isEnabled());
        assertEquals(5000, properties.getDelayBetweenItemsMs());
        assertEquals(2000, properties.getJitterMs());
    }
}
