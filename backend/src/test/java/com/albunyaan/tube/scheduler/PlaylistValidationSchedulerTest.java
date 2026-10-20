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
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * The oEmbed thumbnail heal runs on every enabled run, before (and independent of) the
 * bot-checked validator: breaker OPEN does not stop it, it runs before validatePlaylists
 * so the validator's full-save cannot write a stale /pl_c/ back, and its failure never
 * blocks validation. It also runs once after boot so a deploy heals the same day.
 */
@ExtendWith(MockitoExtension.class)
class PlaylistValidationSchedulerTest {

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

    private void stubCompletedValidation() {
        when(circuitBreaker.isOpen()).thenReturn(false);
        when(systemSettingsRepository.tryAcquireLock(anyString(), anyString(), anyInt())).thenReturn(true);
        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_SCHEDULED, null, "Test");
        run.complete(ValidationRun.STATUS_COMPLETED);
        when(contentValidationService.validatePlaylists(anyString(), any(), anyString(), anyInt())).thenReturn(run);
    }

    @Test
    void breakerOpen_healStillRuns_andEvicts_butNoValidationOrLock() throws Exception {
        when(circuitBreaker.isOpen()).thenReturn(true);
        when(circuitBreaker.getStatus()).thenReturn(mock(YouTubeCircuitBreaker.CircuitBreakerStatus.class));
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(2);

        scheduler.scheduledValidation();

        verify(thumbnailRepair).repairBrokenPlaylistThumbnails();
        verify(publicContentCacheService).evictPublicContentCaches();
        verify(contentValidationService, never()).validatePlaylists(anyString(), any(), anyString(), anyInt());
        verify(systemSettingsRepository, never()).tryAcquireLock(anyString(), anyString(), anyInt());
    }

    @Test
    void heal_runsBeforeValidatePlaylists() throws Exception {
        stubCompletedValidation();
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(0);

        scheduler.scheduledValidation();

        InOrder order = inOrder(thumbnailRepair, contentValidationService);
        order.verify(thumbnailRepair).repairBrokenPlaylistThumbnails();
        order.verify(contentValidationService).validatePlaylists(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void healThrows_validationStillRuns() throws Exception {
        stubCompletedValidation();
        when(thumbnailRepair.repairBrokenPlaylistThumbnails())
                .thenThrow(new ExecutionException("firestore down", new RuntimeException()));

        scheduler.scheduledValidation();

        verify(contentValidationService).validatePlaylists(anyString(), any(), anyString(), anyInt());
    }

    @Test
    void bootHeal_runsOnceAsyncAfterReady_andEvicts() throws Exception {
        when(thumbnailRepair.repairBrokenPlaylistThumbnails()).thenReturn(1);

        scheduler.healThumbnailsOnBoot();

        verify(thumbnailRepair).repairBrokenPlaylistThumbnails();
        verify(publicContentCacheService).evictPublicContentCaches();
        verifyNoInteractions(contentValidationService, systemSettingsRepository);
        Method boot = PlaylistValidationScheduler.class.getMethod("healThumbnailsOnBoot");
        assertArrayEquals(new Class<?>[]{ApplicationReadyEvent.class}, boot.getAnnotation(EventListener.class).value());
        assertEquals("validationExecutor", boot.getAnnotation(Async.class).value());
    }

    @Test
    void bootHeal_schedulerDisabled_doesNothing() throws Exception {
        validationProperties.getPlaylist().getScheduler().setEnabled(false);

        scheduler.healThumbnailsOnBoot();

        verifyNoInteractions(thumbnailRepair, publicContentCacheService);
    }
}
