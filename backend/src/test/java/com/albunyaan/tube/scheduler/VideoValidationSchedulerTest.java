package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.service.ContentValidationService;
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

/**
 * Unit tests for VideoValidationScheduler
 *
 * Tests verify:
 * - Scheduler calls ContentValidationService.validateVideos() with correct parameters
 * - Cache eviction occurs only after successful validation (STATUS_COMPLETED)
 * - Cache eviction is skipped when validation fails (STATUS_FAILED) or throws exception
 */
@ExtendWith(MockitoExtension.class)
class VideoValidationSchedulerTest {

    @Mock
    private ContentValidationService contentValidationService;

    @Mock
    private CacheManager cacheManager;

    @Mock
    private Cache publicContentCache;

    @Mock
    private Cache videosCache;

    private VideoValidationScheduler scheduler;

    @BeforeEach
    void setUp() {
        scheduler = new VideoValidationScheduler(contentValidationService, cacheManager);
    }

    @Test
    @DisplayName("Morning validation should call ContentValidationService with SCHEDULED trigger")
    void morningValidation_shouldCallContentValidationServiceWithScheduledTrigger() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.setVideosChecked(10);
        mockRun.setVideosMarkedArchived(1);
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.morningValidation();

        // Assert
        ArgumentCaptor<String> triggerCaptor = ArgumentCaptor.forClass(String.class);
        ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);

        verify(contentValidationService).validateVideos(
                triggerCaptor.capture(),
                isNull(),
                displayNameCaptor.capture(),
                isNull()
        );

        assertEquals(ValidationRun.TRIGGER_SCHEDULED, triggerCaptor.getValue());
        assertTrue(displayNameCaptor.getValue().contains("morning"));
    }

    @Test
    @DisplayName("Afternoon validation should use correct display name")
    void afternoonValidation_shouldUseCorrectDisplayName() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.afternoonValidation();

        // Assert
        ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(contentValidationService).validateVideos(
                eq(ValidationRun.TRIGGER_SCHEDULED),
                isNull(),
                displayNameCaptor.capture(),
                isNull()
        );

        assertTrue(displayNameCaptor.getValue().contains("afternoon"));
    }

    @Test
    @DisplayName("Evening validation should use correct display name")
    void eveningValidation_shouldUseCorrectDisplayName() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.eveningValidation();

        // Assert
        ArgumentCaptor<String> displayNameCaptor = ArgumentCaptor.forClass(String.class);
        verify(contentValidationService).validateVideos(
                eq(ValidationRun.TRIGGER_SCHEDULED),
                isNull(),
                displayNameCaptor.capture(),
                isNull()
        );

        assertTrue(displayNameCaptor.getValue().contains("evening"));
    }

    @Test
    @DisplayName("Should evict public content cache after successful validation")
    void validation_shouldEvictPublicContentCache() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.morningValidation();

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

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(null);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(null);

        // Act - should not throw
        assertDoesNotThrow(() -> scheduler.morningValidation());

        // Assert - validation was still called
        verify(contentValidationService).validateVideos(anyString(), any(), anyString(), any());
    }

    @Test
    @DisplayName("Should not evict caches when service throws exception")
    void validation_shouldNotEvictCachesOnServiceException() {
        // Arrange
        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenThrow(new RuntimeException("Service failure"));

        // Act - should not throw
        assertDoesNotThrow(() -> scheduler.morningValidation());

        // Assert - caches should not be evicted when exception occurs
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("Should not evict caches when validation returns FAILED status")
    void validation_shouldNotEvictCachesOnFailedStatus() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_FAILED);  // Validation failed
        mockRun.addDetail("errorMessage", "Database connection failed");

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);

        // Act
        scheduler.morningValidation();

        // Assert - caches should NOT be evicted when status is FAILED
        verify(cacheManager, never()).getCache(anyString());
    }

    @Test
    @DisplayName("Should pass null for triggeredBy in scheduled runs")
    void validation_shouldPassNullForTriggeredBy() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.morningValidation();

        // Assert - second argument (triggeredBy) should be null for scheduled runs
        verify(contentValidationService).validateVideos(
                eq(ValidationRun.TRIGGER_SCHEDULED),
                isNull(),  // triggeredBy should be null
                anyString(),
                isNull()   // maxItems should be null (use default)
        );
    }

    @Test
    @DisplayName("Should pass null for maxItems to use service default")
    void validation_shouldPassNullForMaxItemsToUseDefault() {
        // Arrange
        ValidationRun mockRun = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        mockRun.complete(ValidationRun.STATUS_COMPLETED);

        when(contentValidationService.validateVideos(anyString(), any(), anyString(), any()))
                .thenReturn(mockRun);
        when(cacheManager.getCache(CacheConfig.CACHE_PUBLIC_CONTENT)).thenReturn(publicContentCache);
        when(cacheManager.getCache(CacheConfig.CACHE_VIDEOS)).thenReturn(videosCache);

        // Act
        scheduler.morningValidation();

        // Assert - fourth argument (maxItems) should be null to use service default
        verify(contentValidationService).validateVideos(
                anyString(),
                any(),
                anyString(),
                isNull()  // maxItems should be null
        );
    }
}
