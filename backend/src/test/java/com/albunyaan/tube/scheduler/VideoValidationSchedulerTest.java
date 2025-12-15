package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.SystemSettingsRepository;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
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
import static org.mockito.Mockito.lenient;

/**
 * Unit tests for VideoValidationScheduler
 *
 * Tests verify:
 * - Scheduler calls ContentValidationService.validateVideos() with correct parameters
 * - Cache eviction occurs only after successful validation (STATUS_COMPLETED)
 * - Cache eviction is skipped when validation fails (STATUS_FAILED) or throws exception
 * - Scheduler respects enable flag
 * - Scheduler respects circuit breaker state
 * - Concurrent runs are prevented
 * - Max items configuration is respected
 */
@ExtendWith(MockitoExtension.class)
class VideoValidationSchedulerTest {

    @Mock
    private ContentValidationService contentValidationService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private YouTubeCircuitBreaker circuitBreaker;

    @Mock
    private SystemSettingsRepository systemSettingsRepository;

    @Mock
    private Cache publicContentCache;

    @Mock
    private Cache videosCache;

    private ValidationProperties validationProperties;
    private VideoValidationScheduler scheduler;

    @BeforeEach
    void setUp() {
        validationProperties = createDefaultProperties();
        // By default, lock acquisition succeeds and no lock is held (use lenient to avoid strict mode errors)
        lenient().when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt())).thenReturn(true);
        lenient().when(systemSettingsRepository.releaseLock(anyString(), anyString())).thenReturn(true);
        lenient().when(systemSettingsRepository.isLockHeld(anyString())).thenReturn(false);

        scheduler = new VideoValidationScheduler(
                contentValidationService,
                cacheManager,
                validationProperties,
                circuitBreaker,
                systemSettingsRepository
        );
    }

    private ValidationProperties createDefaultProperties() {
        ValidationProperties props = new ValidationProperties();
        props.getVideo().getScheduler().setEnabled(true);
        props.getVideo().getScheduler().setCron("0 0 6 * * ?");
        props.getVideo().setMaxItemsPerRun(10);
        return props;
    }

    @Test
    @DisplayName("Scheduled validation should call ContentValidationService with SCHEDULED trigger")
    void scheduledValidation_shouldCallContentValidationServiceWithScheduledTrigger() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.setVideosChecked(10);
        mockRun.setVideosMarkedArchived(1);
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.scheduledValidation();

        // Assert
        ArgumentCaptor<String> triggerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<Integer> maxItemsCaptor = ArgumentCaptor.forClass(Integer.class);

        verify(contentValidationService).validateVideos(
                triggerCaptor.capture(),
                isNull(),
                anyString(),
                maxItemsCaptor.capture()
        );

        assertEquals(ValidationRun.TRIGGER_SCHEDULED, triggerCaptor.getValue());
        assertEquals(10, maxItemsCaptor.getValue()); // Configured max items
    }

    @Test
    @DisplayName("Should use configured max items per run")
    void scheduledValidation_shouldUseConfiguredMaxItems() {
        // Arrange
        validationProperties.getVideo().setMaxItemsPerRun(25);
        scheduler = new VideoValidationScheduler(
                contentValidationService, cacheManager, validationProperties, circuitBreaker, systemSettingsRepository);

        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.scheduledValidation();

        // Assert
        ArgumentCaptor<Integer> maxItemsCaptor = ArgumentCaptor.forClass(Integer.class);
        verify(contentValidationService).validateVideos(
                anyString(), any(), anyString(), maxItemsCaptor.capture());
        assertEquals(25, maxItemsCaptor.getValue());
    }

    @Test
    @DisplayName("Should skip validation when scheduler is disabled")
    void scheduledValidation_shouldSkipWhenDisabled() {
        // Arrange
        validationProperties.getVideo().getScheduler().setEnabled(false);
        scheduler = new VideoValidationScheduler(
                contentValidationService, cacheManager, validationProperties, circuitBreaker, systemSettingsRepository);

        // Act
        scheduler.scheduledValidation();

        // Assert - validation should NOT be called
        verify(contentValidationService, never()).validateVideos(
                anyString(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Should skip validation when circuit breaker is open")
    void scheduledValidation_shouldSkipWhenCircuitBreakerOpen() {
        // Arrange
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getStatus()).thenReturn(
                new YouTubeCircuitBreaker.CircuitBreakerStatus(
                        true, 60000, null, null, 1, 60, "TestException", "Rate limited", 1, 1));

        // Act
        scheduler.scheduledValidation();

        // Assert - validation should NOT be called
        verify(contentValidationService, never()).validateVideos(
                anyString(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Should prevent concurrent runs via distributed lock")
    void scheduledValidation_shouldPreventConcurrentRuns() throws InterruptedException {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Simulate lock contention: first call acquires, second call fails
        java.util.concurrent.atomic.AtomicBoolean lockHeld = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> lockHeld.compareAndSet(false, true));
        when(systemSettingsRepository.releaseLock(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    lockHeld.set(false);
                    return true;
                });

        // First call takes a long time
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(100); // Simulate long-running validation
                    return mockRun;
                });

        // Act - start first validation in a separate thread
        Thread firstRun = new Thread(() -> scheduler.scheduledValidation());
        firstRun.start();

        // Wait a bit for the first run to start
        Thread.sleep(20);

        // Attempt second run while first is still running
        scheduler.scheduledValidation();

        firstRun.join();

        // Assert - validation should only be called once
        verify(contentValidationService, times(1)).validateVideos(
                anyString(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Should report running status correctly")
    void isRunning_shouldReportCorrectly() {
        // Initially not running
        assertFalse(scheduler.isRunning());
    }

    @Test
    @DisplayName("Should evict public content cache after successful validation")
    void validation_shouldEvictPublicContentCache() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
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
    @DisplayName("Should handle missing caches gracefully")
    void validation_shouldHandleMissingCachesGracefully() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(null);

        // Act - should not throw
        assertDoesNotThrow(() -> scheduler.scheduledValidation());

        // Assert - validation was still called
        verify(contentValidationService).validateVideos(anyString(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Should not evict caches when service throws exception")
    void validation_shouldNotEvictCachesOnServiceException() {
        // Arrange
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(circuitBreaker.isRateLimitError(any())).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenThrow(new RuntimeException("Service failure"));

        // Act - should not throw
        assertDoesNotThrow(() -> scheduler.scheduledValidation());

        // Assert - caches should not be evicted when exception occurs
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("Should record rate limit error when exception is rate limit")
    void validation_shouldRecordRateLimitError() {
        // Arrange
        RuntimeException rateLimitError = new RuntimeException("Sign in to confirm you're not a bot");
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(circuitBreaker.isRateLimitError(rateLimitError)).thenReturn(true);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenThrow(rateLimitError);

        // Act
        scheduler.scheduledValidation();

        // Assert - rate limit error should be recorded
        verify(circuitBreaker).recordRateLimitError(rateLimitError);
    }

    @Test
    @DisplayName("Should not evict caches when validation returns FAILED status")
    void validation_shouldNotEvictCachesOnFailedStatus() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_FAILED);  // Validation failed
        mockRun.addDetail("errorMessage", "Database connection failed");

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenReturn(mockRun);

        // Act
        scheduler.scheduledValidation();

        // Assert - caches should NOT be evicted when status is FAILED
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("Manual trigger should work when scheduler is disabled")
    void manualTrigger_shouldWorkWhenSchedulerDisabled() {
        // Arrange
        validationProperties.getVideo().getScheduler().setEnabled(false);
        scheduler = new VideoValidationScheduler(
                contentValidationService, cacheManager, validationProperties, circuitBreaker, systemSettingsRepository);

        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_MANUAL, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        boolean result = scheduler.triggerManualRun();

        // Assert - manual trigger should work even when scheduler is disabled
        assertTrue(result);
        verify(contentValidationService).validateVideos(
                eq(ValidationRun.TRIGGER_MANUAL), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Manual trigger should fail when circuit breaker is open")
    void manualTrigger_shouldFailWhenCircuitBreakerOpen() {
        // Arrange
        when(circuitBreaker.isOpen()).thenReturn(true);

        // Act
        boolean result = scheduler.triggerManualRun();

        // Assert
        assertFalse(result);
        verify(contentValidationService, never()).validateVideos(
                anyString(), any(), anyString(), anyInt());
    }

    @Test
    @DisplayName("Manual trigger should fail when another run is in progress")
    void manualTrigger_shouldFailWhenRunInProgress() throws InterruptedException {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_MANUAL, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(circuitBreaker.isOpen()).thenReturn(false);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Simulate lock contention: first call acquires, second call fails
        java.util.concurrent.atomic.AtomicBoolean lockHeld = new java.util.concurrent.atomic.AtomicBoolean(false);
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt()))
                .thenAnswer(invocation -> lockHeld.compareAndSet(false, true));
        when(systemSettingsRepository.releaseLock(anyString(), anyString()))
                .thenAnswer(invocation -> {
                    lockHeld.set(false);
                    return true;
                });

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), anyInt()))
                .thenAnswer(invocation -> {
                    Thread.sleep(100);
                    return mockRun;
                });

        // Act - start first run in separate thread
        Thread firstRun = new Thread(() -> scheduler.triggerManualRun());
        firstRun.start();

        Thread.sleep(20);

        // Attempt second manual run
        boolean result = scheduler.triggerManualRun();

        firstRun.join();

        // Assert - second run should fail
        assertFalse(result);
    }
}
