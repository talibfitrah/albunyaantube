package com.albunyaan.tube.service;

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

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class VideoValidationServiceTest {

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private YouTubeService youtubeService;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private ValidationRunRepository validationRunRepository;

    private VideoValidationService service;

    @BeforeEach
    void setUp() {
        service = new VideoValidationService(videoRepository, youtubeService, auditLogService, validationRunRepository);
    }

    @Test
    void validateStandaloneVideos_marksUnavailableBasedOnDtoPresence() throws Exception {
        Video validVideo = new Video("keep-me");
        validVideo.setId("v-1");
        Video missingVideo = new Video("remove-me");
        missingVideo.setId("v-2");

        when(videoRepository.findByStatus("APPROVED")).thenReturn(List.of(validVideo, missingVideo));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("keep-me");
        when(youtubeService.batchValidateVideosDto(anyList())).thenReturn(Map.of("keep-me", dto));

        ValidationRun run = service.validateStandaloneVideos(
                ValidationRun.TRIGGER_SCHEDULED,
                null,
                null,
                null
        );

        assertEquals(2, run.getVideosChecked());
        assertEquals(1, run.getVideosMarkedUnavailable());
        assertSame(ValidationStatus.VALID, validVideo.getValidationStatus());
        assertSame(ValidationStatus.UNAVAILABLE, missingVideo.getValidationStatus());

        verify(youtubeService).batchValidateVideosDto(List.of("keep-me", "remove-me"));
        verify(auditLogService).logSystem(eq("video_marked_unavailable"), eq("video"), eq("v-2"), any());
    }

    @Test
    void validateSpecificVideos_countsUnavailableViaDtoMap() throws ExecutionException, InterruptedException, TimeoutException {
        Video valid = new Video("exists");
        Video missing = new Video("missing");

        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId("exists");
        when(youtubeService.batchValidateVideosDto(anyList())).thenReturn(Map.of("exists", dto));

        ValidationRun result = service.validateSpecificVideos(List.of(valid, missing), ValidationRun.TRIGGER_IMPORT);

        assertEquals(2, result.getVideosChecked());
        assertEquals(1, result.getVideosMarkedUnavailable());
        verify(youtubeService).batchValidateVideosDto(List.of("exists", "missing"));
        verify(videoRepository, never()).save(any(Video.class));
    }
}
