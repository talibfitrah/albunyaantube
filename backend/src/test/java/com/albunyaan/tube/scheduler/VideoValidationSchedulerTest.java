package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.config.ValidationSchedulerProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.SchedulerLockService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for VideoValidationScheduler
 *
 * Tests verify:
 * - Scheduler respects enabled/disabled flag
 * - Scheduler acquires and releases distributed lock
 * - Scheduler uses configurable max items
 * - Cache eviction occurs only after successful validation (STATUS_COMPLETED)
 * - Cache eviction is skipped when validation fails or scheduler is disabled
 */
@ExtendWith(MockitoExtension.class)
class VideoValidationSchedulerTest {

    @Mock
    private ContentValidationService contentValidationService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private SchedulerLockService lockService;

    @Mock
    private Cache publicContentCache;

    @Mock
    private Cache videosCache;

    private ValidationSchedulerProperties schedulerProperties;
    private VideoValidationScheduler scheduler;

    @BeforeEach
    void setUp() {
        schedulerProperties = new ValidationSchedulerProperties();
        schedulerProperties.setEnabled(true);
        schedulerProperties.setMaxItemsPerRun(10);
        schedulerProperties.setLockTtlMinutes(120);
        scheduler = new VideoValidationScheduler(
                contentValidationService,
                cacheManager,
                schedulerProperties,
                lockService
        );
    }

    @Nested
    @DisplayName("Enable/Disable Flag Tests")
    class EnableDisableTests {

        @Test
        @DisplayName("Should skip validation when scheduler is disabled")
        void scheduledValidation_whenDisabled_shouldSkip() {
            // Arrange
            schedulerProperties.setEnabled(false);

            // Act
            scheduler.scheduledValidation();

            // Assert - should not attempt to acquire lock or run validation
            verify(lockService, never()).tryAcquireLock(anyString());
            verify(contentValidationService, never()).validateVideos(anyString(), any(), anyString(), anyInt());
        }

        @Test
        @DisplayName("Should run validation when scheduler is enabled")
        void scheduledValidation_whenEnabled_shouldRun() {
            // Arrange
            schedulerProperties.setEnabled(true);
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

            // Act
            scheduler.scheduledValidation();

            // Assert
            verify(lockService).tryAcquireLock(anyString());
            verify(contentValidationService).validateVideos(anyString(), any(), anyString(), anyInt());
            verify(lockService).releaseLock(anyString());
        }
    }

    @Nested
    @DisplayName("Distributed Lock Tests")
    class DistributedLockTests {

        @Test
        @DisplayName("Should skip validation when lock cannot be acquired")
        void scheduledValidation_whenLockNotAcquired_shouldSkip() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(false);

            // Act
            scheduler.scheduledValidation();

            // Assert - should not run validation
            verify(lockService).tryAcquireLock(anyString());
            verify(contentValidationService, never()).validateVideos(anyString(), any(), anyString(), anyInt());
            verify(lockService, never()).releaseLock(anyString());
        }

        @Test
        @DisplayName("Should release lock even when validation fails")
        void scheduledValidation_whenValidationFails_shouldReleaseLock() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Validation failed"));

            // Act
            scheduler.scheduledValidation();

            // Assert - lock should be released even on failure
            verify(lockService).tryAcquireLock(anyString());
            verify(lockService).releaseLock(anyString());
        }

        @Test
        @DisplayName("Should pass runId to lock service")
        void scheduledValidation_shouldPassRunIdToLockService() {
            // Arrange
            ArgumentCaptor<String> acquireCaptor = ArgumentCaptor.forClass(String.class);
            ArgumentCaptor<String> releaseCaptor = ArgumentCaptor.forClass(String.class);
            when(lockService.tryAcquireLock(acquireCaptor.capture())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);
            doNothing().when(lockService).releaseLock(releaseCaptor.capture());

            // Act
            scheduler.scheduledValidation();

            // Assert - same runId should be used for acquire and release
            String acquiredRunId = acquireCaptor.getValue();
            String releasedRunId = releaseCaptor.getValue();
            assertNotNull(acquiredRunId);
            assertEquals(acquiredRunId, releasedRunId, "Same runId should be used for acquire and release");
        }
    }

    @Nested
    @DisplayName("Max Items Configuration Tests")
    class MaxItemsTests {

        @Test
        @DisplayName("Should pass configured max items to validation service")
        void scheduledValidation_shouldUseConfiguredMaxItems() {
            // Arrange
            schedulerProperties.setMaxItemsPerRun(25);
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

            // Act
            scheduler.scheduledValidation();

            // Assert
            ArgumentCaptor<Integer> maxItemsCaptor = ArgumentCaptor.forClass(Integer.class);
            verify(contentValidationService).validateVideos(
                    eq(ValidationRun.TRIGGER_SCHEDULED),
                    isNull(),
                    anyString(),
                    maxItemsCaptor.capture()
            );
            assertEquals(25, maxItemsCaptor.getValue());
        }
    }

    @Nested
    @DisplayName("Cache Eviction Tests")
    class CacheEvictionTests {

        @Test
        @DisplayName("Should evict caches after successful validation")
        void scheduledValidation_onSuccess_shouldEvictCaches() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

            // Act
            scheduler.scheduledValidation();

            // Assert
            verify(publicContentCache).clear();
            verify(videosCache).clear();
        }

        @Test
        @DisplayName("Should not evict caches when validation fails")
        void scheduledValidation_onFailedStatus_shouldNotEvictCaches() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_FAILED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);

            // Act
            scheduler.scheduledValidation();

            // Assert - caches should NOT be evicted
            verify(cacheManager, never()).getCache(anyString());
        }

        @Test
        @DisplayName("Should not evict caches when validation throws exception")
        void scheduledValidation_onException_shouldNotEvictCaches() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenThrow(new RuntimeException("Service failure"));

            // Act
            scheduler.scheduledValidation();

            // Assert - caches should NOT be evicted
            verify(cacheManager, never()).getCache(anyString());
        }

        @Test
        @DisplayName("Should handle missing caches gracefully")
        void scheduledValidation_withMissingCaches_shouldNotThrow() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(null);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(null);

            // Act & Assert - should not throw
            assertDoesNotThrow(() -> scheduler.scheduledValidation());
        }
    }

    @Nested
    @DisplayName("Trigger Type Tests")
    class TriggerTypeTests {

        @Test
        @DisplayName("Should use SCHEDULED trigger type")
        void scheduledValidation_shouldUseScheduledTriggerType() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

            // Act
            scheduler.scheduledValidation();

            // Assert
            verify(contentValidationService).validateVideos(
                    eq(ValidationRun.TRIGGER_SCHEDULED),
                    isNull(),
                    anyString(),
                    anyInt()
            );
        }

        @Test
        @DisplayName("Should pass null for triggeredBy in scheduled runs")
        void scheduledValidation_shouldPassNullForTriggeredBy() {
            // Arrange
            when(lockService.tryAcquireLock(anyString())).thenReturn(true);

            ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
            mockRun.complete(ValidationRun.STATUS_COMPLETED);
            when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                    .thenReturn(mockRun);
            when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
            when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

            // Act
            scheduler.scheduledValidation();

            // Assert
            verify(contentValidationService).validateVideos(
                    anyString(),
                    isNull(),  // triggeredBy should be null
                    anyString(),
                    anyInt()
            );
        }
    }
}
