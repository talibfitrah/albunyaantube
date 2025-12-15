package com.albunyaan.tube.config;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for YouTubeCircuitBreakerProperties
 *
 * Tests verify:
 * - Default values are correct
 * - Cooldown calculation with exponential backoff
 * - Cooldown capped at maximum
 */
class YouTubeCircuitBreakerPropertiesTest {

    private YouTubeCircuitBreakerProperties properties;

    @BeforeEach
    void setUp() {
        properties = new YouTubeCircuitBreakerProperties();
    }

    @Nested
    @DisplayName("Default Value Tests")
    class DefaultValueTests {

        @Test
        @DisplayName("Should have correct default enabled value")
        void defaultEnabled_shouldBeTrue() {
            assertTrue(properties.isEnabled());
        }

        @Test
        @DisplayName("Should have correct default persistence collection")
        void defaultPersistenceCollection_shouldBeSystemSettings() {
            assertEquals("system_settings", properties.getPersistenceCollection());
        }

        @Test
        @DisplayName("Should have correct default persistence document ID")
        void defaultPersistenceDocumentId_shouldBeYoutubeCircuitBreaker() {
            assertEquals("youtube_circuit_breaker", properties.getPersistenceDocumentId());
        }

        @Test
        @DisplayName("Should have correct default cooldown base minutes")
        void defaultCooldownBaseMinutes_shouldBe60() {
            assertEquals(60, properties.getCooldownBaseMinutes());
        }

        @Test
        @DisplayName("Should have correct default cooldown max minutes")
        void defaultCooldownMaxMinutes_shouldBe2880() {
            assertEquals(2880, properties.getCooldownMaxMinutes());
        }

        @Test
        @DisplayName("Should have correct default backoff decay hours")
        void defaultBackoffDecayHours_shouldBe48() {
            assertEquals(48, properties.getBackoffDecayHours());
        }

        @Test
        @DisplayName("Should have correct default probe timeout seconds")
        void defaultProbeTimeoutSeconds_shouldBe30() {
            assertEquals(30, properties.getProbeTimeoutSeconds());
        }

        @Test
        @DisplayName("Should have correct default rolling window error threshold")
        void defaultRollingWindowErrorThreshold_shouldBe3() {
            assertEquals(3, properties.getRollingWindowErrorThreshold());
        }

        @Test
        @DisplayName("Should have correct default rolling window minutes")
        void defaultRollingWindowMinutes_shouldBe10() {
            assertEquals(10, properties.getRollingWindowMinutes());
        }

        @Test
        @DisplayName("Should have correct default max persistence retries")
        void defaultMaxPersistenceRetries_shouldBe3() {
            assertEquals(3, properties.getMaxPersistenceRetries());
        }
    }

    @Nested
    @DisplayName("Cooldown Calculation Tests")
    class CooldownCalculationTests {

        @Test
        @DisplayName("Level 0 should return base cooldown (60 minutes)")
        void calculateCooldown_level0_shouldReturnBase() {
            long cooldown = properties.calculateCooldownMinutes(0);
            assertEquals(60, cooldown);
        }

        @Test
        @DisplayName("Negative level should return base cooldown")
        void calculateCooldown_negativeLevel_shouldReturnBase() {
            long cooldown = properties.calculateCooldownMinutes(-1);
            assertEquals(60, cooldown);
        }

        @Test
        @DisplayName("Level 1 should return 360 minutes (6 hours)")
        void calculateCooldown_level1_shouldReturn360() {
            long cooldown = properties.calculateCooldownMinutes(1);
            assertEquals(360, cooldown);
        }

        @Test
        @DisplayName("Level 2 should return 720 minutes (12 hours)")
        void calculateCooldown_level2_shouldReturn720() {
            long cooldown = properties.calculateCooldownMinutes(2);
            assertEquals(720, cooldown);
        }

        @Test
        @DisplayName("Level 3 should return 1440 minutes (24 hours)")
        void calculateCooldown_level3_shouldReturn1440() {
            long cooldown = properties.calculateCooldownMinutes(3);
            assertEquals(1440, cooldown);
        }

        @Test
        @DisplayName("Level 4 should return max cooldown (2880 minutes / 48 hours)")
        void calculateCooldown_level4_shouldReturnMax() {
            long cooldown = properties.calculateCooldownMinutes(4);
            assertEquals(2880, cooldown);
        }

        @Test
        @DisplayName("Level beyond 4 should be capped at max")
        void calculateCooldown_highLevel_shouldCapAtMax() {
            long cooldown = properties.calculateCooldownMinutes(10);
            assertEquals(2880, cooldown);
        }

        @Test
        @DisplayName("Should respect custom base cooldown")
        void calculateCooldown_customBase_shouldUseCustom() {
            properties.setCooldownBaseMinutes(30);

            long cooldown = properties.calculateCooldownMinutes(0);
            assertEquals(30, cooldown);
        }

        @Test
        @DisplayName("Should respect custom max cooldown")
        void calculateCooldown_customMax_shouldCapAtCustomMax() {
            properties.setCooldownMaxMinutes(1000);

            // Level 3 is 1440 normally, but should cap at 1000
            long cooldown = properties.calculateCooldownMinutes(3);
            assertEquals(1000, cooldown);
        }
    }

    @Nested
    @DisplayName("Setter Tests")
    class SetterTests {

        @Test
        @DisplayName("Should set enabled")
        void setEnabled_shouldUpdateValue() {
            properties.setEnabled(false);
            assertFalse(properties.isEnabled());
        }

        @Test
        @DisplayName("Should set persistence collection")
        void setPersistenceCollection_shouldUpdateValue() {
            properties.setPersistenceCollection("custom_collection");
            assertEquals("custom_collection", properties.getPersistenceCollection());
        }

        @Test
        @DisplayName("Should set persistence document ID")
        void setPersistenceDocumentId_shouldUpdateValue() {
            properties.setPersistenceDocumentId("custom_doc");
            assertEquals("custom_doc", properties.getPersistenceDocumentId());
        }

        @Test
        @DisplayName("Should set cooldown base minutes")
        void setCooldownBaseMinutes_shouldUpdateValue() {
            properties.setCooldownBaseMinutes(120);
            assertEquals(120, properties.getCooldownBaseMinutes());
        }

        @Test
        @DisplayName("Should set cooldown max minutes")
        void setCooldownMaxMinutes_shouldUpdateValue() {
            properties.setCooldownMaxMinutes(5000);
            assertEquals(5000, properties.getCooldownMaxMinutes());
        }

        @Test
        @DisplayName("Should set backoff decay hours")
        void setBackoffDecayHours_shouldUpdateValue() {
            properties.setBackoffDecayHours(72);
            assertEquals(72, properties.getBackoffDecayHours());
        }

        @Test
        @DisplayName("Should set probe timeout seconds")
        void setProbeTimeoutSeconds_shouldUpdateValue() {
            properties.setProbeTimeoutSeconds(60);
            assertEquals(60, properties.getProbeTimeoutSeconds());
        }

        @Test
        @DisplayName("Should set rolling window error threshold")
        void setRollingWindowErrorThreshold_shouldUpdateValue() {
            properties.setRollingWindowErrorThreshold(5);
            assertEquals(5, properties.getRollingWindowErrorThreshold());
        }

        @Test
        @DisplayName("Should set rolling window minutes")
        void setRollingWindowMinutes_shouldUpdateValue() {
            properties.setRollingWindowMinutes(15);
            assertEquals(15, properties.getRollingWindowMinutes());
        }

        @Test
        @DisplayName("Should set max persistence retries")
        void setMaxPersistenceRetries_shouldUpdateValue() {
            properties.setMaxPersistenceRetries(5);
            assertEquals(5, properties.getMaxPersistenceRetries());
        }
    }
}
