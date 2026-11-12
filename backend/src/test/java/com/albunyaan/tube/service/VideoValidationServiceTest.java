package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.lenient;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoValidationServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private ChannelOrchestrator channelOrchestrator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ValidationRunRepository validationRunRepository;

    @Mock
    private ValidationProperties validationProperties;

    @Mock
    private ValidationProperties.Video videoProperties;

    @Mock
    private PublicContentCacheService publicContentCacheService;

    private VideoValidationService service;

    @BeforeEach
    void setUp() {
        // Use lenient stubbing - not all tests use these stubs
        lenient().when(validationProperties.getVideo()).thenReturn(videoProperties);
        lenient().when(videoProperties.getMaxItemsPerRun()).thenReturn(10);
        service = new VideoValidationService(videoRepository, channelOrchestrator, auditLogService, validationRunRepository, validationProperties, publicContentCacheService);
    }

    @Test
    void validateStandaloneVideos_marksUnavailableOnlyForNotFound_andErrorsAsError() throws Exception {
        Video validVideo = new Video("keep-me");
        validVideo.setId("v-1");
        Video notFoundVideo = new Video("remove-me");
        notFoundVideo.setId("v-2");
        Video errorVideo = new Video("flaky");
        errorVideo.setId("v-3");

        when(videoRepository.findByStatus("APPROVED")).thenReturn(List.of(validVideo, notFoundVideo, errorVideo));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("keep-me");
        BatchValidationResult<StreamDetailsDto> validationResult = new BatchValidationResult<>();
        validationResult.addValid("keep-me", dto);
        validationResult.addNotFound("remove-me");
        validationResult.addError("flaky", "Network error: timeout");
        when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(validationResult);

        ValidationRun run = service.validateStandaloneVideos(
                ValidationRun.TRIGGER_SCHEDULED,
                null,
                null,
                null
        );

        assertEquals(3, run.getVideosChecked());
        assertEquals(1, run.getVideosMarkedUnavailable());
        assertEquals(1, run.getErrorCount());
        assertSame(ValidationStatus.VALID, validVideo.getValidationStatus());
        assertSame(ValidationStatus.UNAVAILABLE, notFoundVideo.getValidationStatus());
        assertSame(ValidationStatus.ERROR, errorVideo.getValidationStatus());

        verify(channelOrchestrator).batchValidateVideosDtoWithDetails(argThat(ids ->
                new HashSet<>(ids).equals(Set.of("keep-me", "remove-me", "flaky"))
        ));
        verify(auditLogService).logSystem(eq("video_marked_unavailable"), eq("video"), eq("v-2"), any());
        verify(auditLogService, never()).logSystem(eq("video_marked_unavailable"), eq("video"), eq("v-3"), any());
    }

    /** A video this run marks UNAVAILABLE leaves the Videos tab, so the kept tab order must be dropped. */
    @Test
    void validateStandaloneVideos_announcesAContentChangeWhenAVideoIsMarkedUnavailable() throws Exception {
        Video gone = new Video("remove-me");
        gone.setId("v-2");
        when(videoRepository.findByStatus("APPROVED")).thenReturn(List.of(gone));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BatchValidationResult<StreamDetailsDto> validationResult = new BatchValidationResult<>();
        validationResult.addNotFound("remove-me");
        when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(validationResult);

        service.validateStandaloneVideos(ValidationRun.TRIGGER_MANUAL, "admin", "admin@test.com", null);

        verify(publicContentCacheService).evictPublicContentCaches();
    }

    /** A failed announce is logged; the run it ends is still recorded COMPLETED with its counts. */
    @Test
    void validateStandaloneVideos_aFailedAnnounceStillCompletesTheRun() throws Exception {
        Video gone = new Video("remove-me");
        gone.setId("v-2");
        when(videoRepository.findByStatus("APPROVED")).thenReturn(List.of(gone));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BatchValidationResult<StreamDetailsDto> validationResult = new BatchValidationResult<>();
        validationResult.addNotFound("remove-me");
        when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(validationResult);
        doThrow(new RuntimeException("Redis down")).when(publicContentCacheService).evictPublicContentCaches();

        ValidationRun run = service.validateStandaloneVideos(ValidationRun.TRIGGER_MANUAL, "admin", "admin@test.com", null);

        assertEquals("COMPLETED", run.getStatus());
        assertEquals(1, run.getVideosChecked());
        assertEquals(1, run.getVideosMarkedUnavailable());
    }

    /** Nothing left the tab (valid and error videos stay listed), so there is no content change to announce. */
    @Test
    void validateStandaloneVideos_announcesNothingWhenNoVideoIsMarkedUnavailable() throws Exception {
        Video ok = new Video("keep-me");
        ok.setId("v-1");
        when(videoRepository.findByStatus("APPROVED")).thenReturn(List.of(ok));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));
        BatchValidationResult<StreamDetailsDto> validationResult = new BatchValidationResult<>();
        validationResult.addValid("keep-me", new StreamDetailsDto());
        when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(validationResult);

        service.validateStandaloneVideos(ValidationRun.TRIGGER_MANUAL, "admin", "admin@test.com", null);

        verify(publicContentCacheService, never()).evictPublicContentCaches();
    }

    @Test
    void validateSpecificVideos_countsUnavailableAndErrorsViaDetails() throws ExecutionException, InterruptedException, TimeoutException {
        Video valid = new Video("exists");
        Video missing = new Video("missing");
        Video error = new Video("error");

        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("exists");
        BatchValidationResult<StreamDetailsDto> validationResult = new BatchValidationResult<>();
        validationResult.addValid("exists", dto);
        validationResult.addNotFound("missing");
        validationResult.addError("error", "Network error: timeout");
        when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(validationResult);

        ValidationRun result = service.validateSpecificVideos(List.of(valid, missing, error), ValidationRun.TRIGGER_IMPORT);

        assertEquals(3, result.getVideosChecked());
        assertEquals(1, result.getVideosMarkedUnavailable());
        assertEquals(1, result.getErrorCount());
        verify(channelOrchestrator).batchValidateVideosDtoWithDetails(argThat(ids ->
                new HashSet<>(ids).equals(Set.of("exists", "missing", "error"))
        ));
        verify(videoRepository, never()).save(any(Video.class));
    }
}
