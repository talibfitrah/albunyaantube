package com.albunyaan.tube.scheduler;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.repository.SystemSettingsRepository;
import com.albunyaan.tube.service.ContentValidationService;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.YouTubeCircuitBreaker;
import com.albunyaan.tube.util.ThumbnailRepairMigration;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.scheduling.annotation.Async;

import java.lang.reflect.Method;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Who heals playlist thumbnails, and when. A breaker-closed run heals inside
 * {@code validatePlaylists} (ContentValidationServiceTest), so the scheduler does not heal
 * itself. With the breaker OPEN, and once after boot, the scheduler heals directly under its
 * distributed lock (short TTL); when another holder has the lock it skips, because the holder heals.
 */
@ExtendWith(MockitoExtension.class)
class PlaylistValidationSchedulerTest {

    private static final String LOCK_KEY = "playlist_validation_scheduler";

    @Mock private ContentValidationService contentValidationService;
    @Mock private PublicContentCacheService publicContentCacheService;
    @Mock private YouTubeCircuitBreaker circuitBreaker;
    @Mock private SystemSettingsRepository systemSettingsRepository;
    @Mock private ThumbnailRepairMigration thumbnailRepair;

    private ValidationProperties validationProperties;
    private PlaylistValidationScheduler scheduler;

    @BeforeEach
    void setUp() {
        validationProperties = new ValidationProperties();
        validationProperties.getPlaylist().getScheduler().setEnabled(true);
        scheduler = new PlaylistValidationScheduler(contentValidationService, publicContentCacheService,
                validationProperties, circuitBreaker, systemSettingsRepository, thumbnailRepair);
    }

    @Test
    void breakerOpen_healsUnderShortLock_evicts_noValidation() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getStatus()).thenReturn(mock(YouTubeCircuitBreaker.CircuitBreakerStatus.class));
        when(systemSettingsRepository.tryAcquireLock(eq(LOCK_KEY), anyString(), eq(300))).thenReturn(true);
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(2);

        scheduler.scheduledValidation();

        InOrder order = inOrder(systemSettingsRepository, thumbnailRepair, publicContentCacheService);
        order.verify(systemSettingsRepository).tryAcquireLock(eq(LOCK_KEY), anyString(), eq(300));
        order.verify(thumbnailRepair).repairBrokenPlaylistThumbnails();
        order.verify(publicContentCacheService).evictPublicContentCaches();
        order.verify(systemSettingsRepository).releaseLock(eq(LOCK_KEY), anyString());
        verify(contentValidationService, never()).validatePlaylists(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void breakerOpen_lockHeld_skipsHeal() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getStatus()).thenReturn(mock(YouTubeCircuitBreaker.CircuitBreakerStatus.class));
        when(systemSettingsRepository.tryAcquireLock(eq(LOCK_KEY), anyString(), anyInt())).thenReturn(false);

        scheduler.scheduledValidation();

        verifyNoInteractions(thumbnailRepair, publicContentCacheService);
        verify(systemSettingsRepository, never()).releaseLock(anyString(), anyString());
    }

    @Test
    void breakerClosed_schedulerLeavesTheHealToValidatePlaylists() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt())).thenReturn(true);
        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        run.complete(ValidationRun.STATUS_COMPLETED);
        when(contentValidationService.validatePlaylists(anyString(), any(), anyString(), anyInt())).thenReturn(run);

        scheduler.scheduledValidation();

        verify(contentValidationService).validatePlaylists(anyString(), any(), anyString(), anyInt());
        verifyNoInteractions(thumbnailRepair);
    }

    @Test
    void bootHeal_takesShortLockUnderItsOwnHolder_heals_evicts_releases() throws Exception {
        when(systemSettingsRepository.tryAcquireLock(eq(LOCK_KEY), endsWith("-boot"), eq(300))).thenReturn(true);
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(1);

        scheduler.healThumbnailsOnBoot();

        InOrder order = inOrder(systemSettingsRepository, thumbnailRepair, publicContentCacheService);
        order.verify(systemSettingsRepository).tryAcquireLock(eq(LOCK_KEY), endsWith("-boot"), eq(300));
        order.verify(thumbnailRepair).repairBrokenPlaylistThumbnails();
        order.verify(publicContentCacheService).evictPublicContentCaches();
        order.verify(systemSettingsRepository).releaseLock(eq(LOCK_KEY), endsWith("-boot"));
        verifyNoInteractions(contentValidationService);
        Method boot = PlaylistValidationScheduler.class.getMethod("healThumbnailsOnBoot");
        assertArrayEquals(new Class<?>[]{ApplicationReadyEvent.class}, boot.getAnnotation(EventListener.class).value());
        assertEquals("validationExecutor", boot.getAnnotation(Async.class).value());
    }

    @Test
    void bootHeal_lockHeld_skips() throws Exception {
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt())).thenReturn(false);

        scheduler.healThumbnailsOnBoot();

        verifyNoInteractions(thumbnailRepair, publicContentCacheService);
        verify(systemSettingsRepository, never()).releaseLock(anyString(), anyString());
    }

    @Test
    void bootHeal_healReturnsZero_doesNotEvict_stillReleases() throws Exception {
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt())).thenReturn(true);
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(0);

        scheduler.healThumbnailsOnBoot();

        verify(publicContentCacheService, never()).evictPublicContentCaches();
        verify(systemSettingsRepository).releaseLock(eq(LOCK_KEY), anyString());
    }

    @Test
    void bootHeal_schedulerDisabled_doesNothing() throws Exception {
        validationProperties.getPlaylist().getScheduler().setEnabled(false);

        scheduler.healThumbnailsOnBoot();

        verifyNoInteractions(thumbnailRepair, publicContentCacheService, systemSettingsRepository);
    }
}
