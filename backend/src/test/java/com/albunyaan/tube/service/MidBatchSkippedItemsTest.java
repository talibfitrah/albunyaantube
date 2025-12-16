package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Tests for mid-batch rate limit interruption behavior.
 *
 * These tests verify that when the circuit breaker opens mid-batch:
 * - Items 1-4 are processed and have their status updated (validated/archived/error)
 * - Items 5-10 are SKIPPED and do NOT have their status or lastValidatedAt updated
 * - Skipped items retain their prior state for retry on next run
 *
 * This simulates the scenario where YouTubeService.batchValidateVideosDtoWithDetails()
 * marks remaining items as "skipped" when the circuit breaker opens mid-batch.
 */
@ExtendWith(MockitoExtension.class)
class MidBatchSkippedItemsTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private ValidationRunRepository validationRunRepository;

    @Mock
    private YouTubeService youtubeService;

    @Mock
    private AuditLogService auditLogService;

    private ValidationProperties validationProperties;

    private ContentValidationService service;

    @BeforeEach
    void setUp() {
        validationProperties = new ValidationProperties();
        validationProperties.getVideo().setMaxItemsPerRun(20);

        service = new ContentValidationService(
                channelRepository,
                playlistRepository,
                videoRepository,
                youtubeService,
                auditLogService,
                validationRunRepository,
                validationProperties
        );
    }

    @Nested
    @DisplayName("Mid-Batch Skipped Items Tests")
    class MidBatchSkippedItemsTests {

        @Test
        @DisplayName("Skipped items should NOT have status or lastValidatedAt updated")
        void skippedItems_shouldNotBeUpdated() throws Exception {
            // Arrange: Create 10 videos to validate
            List<Video> videos = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                Video v = createVideo("video-" + i, "Video " + i);
                videos.add(v);
            }

            // Mock repository to return all 10 videos
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt()))
                    .thenReturn(videos);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt()))
                    .thenReturn(Collections.emptyList());

            // Simulate circuit breaker opening mid-batch:
            // - Videos 1-4 are validated successfully
            // - Videos 5-10 are SKIPPED (circuit breaker opened)
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            for (int i = 1; i <= 4; i++) {
                result.addValid("video-" + i, new StreamDetailsDto());
            }
            for (int i = 5; i <= 10; i++) {
                result.addSkipped("video-" + i);
            }

            when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateVideos("SCHEDULED", "system", "System", null);

            // Assert: Verify only 4 videos were saved (the processed ones)
            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository, times(4)).save(videoCaptor.capture());

            List<Video> savedVideos = videoCaptor.getAllValues();

            // All saved videos should be from the first 4 (processed)
            Set<String> savedIds = new HashSet<>();
            for (Video saved : savedVideos) {
                savedIds.add(saved.getYoutubeId());
                // Processed videos should have VALID status and lastValidatedAt set
                assertEquals(ValidationStatus.VALID, saved.getValidationStatus(),
                        "Processed video should be marked VALID");
                assertNotNull(saved.getLastValidatedAt(),
                        "Processed video should have lastValidatedAt set");
            }

            // Verify videos 1-4 were saved
            assertTrue(savedIds.contains("video-1"), "video-1 should be saved");
            assertTrue(savedIds.contains("video-2"), "video-2 should be saved");
            assertTrue(savedIds.contains("video-3"), "video-3 should be saved");
            assertTrue(savedIds.contains("video-4"), "video-4 should be saved");

            // Verify videos 5-10 were NOT saved (skipped)
            assertFalse(savedIds.contains("video-5"), "video-5 should NOT be saved (skipped)");
            assertFalse(savedIds.contains("video-6"), "video-6 should NOT be saved (skipped)");
            assertFalse(savedIds.contains("video-7"), "video-7 should NOT be saved (skipped)");
            assertFalse(savedIds.contains("video-8"), "video-8 should NOT be saved (skipped)");
            assertFalse(savedIds.contains("video-9"), "video-9 should NOT be saved (skipped)");
            assertFalse(savedIds.contains("video-10"), "video-10 should NOT be saved (skipped)");

            // Verify run statistics
            assertEquals(4, run.getVideosChecked(), "Only 4 videos should be counted as checked");
            assertEquals(0, run.getVideosMarkedArchived(), "No videos should be archived");
        }

        @Test
        @DisplayName("Skipped count should be recorded in validation run details")
        void skippedCount_shouldBeRecordedInDetails() throws Exception {
            // Arrange: Create 10 videos
            List<Video> videos = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                videos.add(createVideo("video-" + i, "Video " + i));
            }

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt()))
                    .thenReturn(videos);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt()))
                    .thenReturn(Collections.emptyList());

            // Simulate: 4 valid, 6 skipped
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            for (int i = 1; i <= 4; i++) {
                result.addValid("video-" + i, new StreamDetailsDto());
            }
            for (int i = 5; i <= 10; i++) {
                result.addSkipped("video-" + i);
            }

            when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);

            // Capture the validation run saves to verify details
            ArgumentCaptor<ValidationRun> runCaptor = ArgumentCaptor.forClass(ValidationRun.class);
            when(validationRunRepository.save(runCaptor.capture())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validateVideos("SCHEDULED", "system", "System", null);

            // Assert: Check that skippedVideosCount is in the details
            List<ValidationRun> savedRuns = runCaptor.getAllValues();
            ValidationRun finalRun = savedRuns.get(savedRuns.size() - 1);

            Map<String, Object> details = finalRun.getDetails();
            assertNotNull(details, "Details should not be null");
            assertEquals(6, details.get("skippedVideosCount"),
                    "skippedVideosCount should be 6");
        }

        @Test
        @DisplayName("All items skipped when circuit breaker is open at start")
        void allItemsSkipped_whenCircuitBreakerOpenAtStart() throws Exception {
            // Arrange: Create 5 videos
            List<Video> videos = new ArrayList<>();
            for (int i = 1; i <= 5; i++) {
                videos.add(createVideo("video-" + i, "Video " + i));
            }

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt()))
                    .thenReturn(videos);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt()))
                    .thenReturn(Collections.emptyList());

            // Simulate: Circuit breaker open at start - ALL items skipped
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            for (int i = 1; i <= 5; i++) {
                result.addSkipped("video-" + i);
            }

            when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateVideos("SCHEDULED", "system", "System", null);

            // Assert: No videos should be saved
            verify(videoRepository, never()).save(any(Video.class));

            // Verify run statistics - 0 checked since all were skipped
            assertEquals(0, run.getVideosChecked(), "No videos should be checked");
            assertEquals(0, run.getVideosMarkedArchived(), "No videos should be archived");
        }

        @Test
        @DisplayName("Mixed results: valid, notFound, error, and skipped items")
        void mixedResults_withSkippedItems() throws Exception {
            // Arrange: Create 10 videos
            List<Video> videos = new ArrayList<>();
            for (int i = 1; i <= 10; i++) {
                videos.add(createVideo("video-" + i, "Video " + i));
            }

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt()))
                    .thenReturn(videos);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt()))
                    .thenReturn(Collections.emptyList());

            // Simulate mixed results:
            // - video-1, video-2: VALID
            // - video-3: NOT_FOUND (should be archived)
            // - video-4: ERROR
            // - video-5 through video-10: SKIPPED (circuit breaker opened)
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addValid("video-1", new StreamDetailsDto());
            result.addValid("video-2", new StreamDetailsDto());
            result.addNotFound("video-3");
            result.addError("video-4", "Network timeout");
            for (int i = 5; i <= 10; i++) {
                result.addSkipped("video-" + i);
            }

            when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateVideos("SCHEDULED", "system", "System", null);

            // Assert: 4 videos should be saved (2 valid + 1 notFound + 1 error)
            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository, times(4)).save(videoCaptor.capture());

            Map<String, ValidationStatus> statusByVideo = new HashMap<>();
            for (Video saved : videoCaptor.getAllValues()) {
                statusByVideo.put(saved.getYoutubeId(), saved.getValidationStatus());
            }

            // Verify statuses
            assertEquals(ValidationStatus.VALID, statusByVideo.get("video-1"));
            assertEquals(ValidationStatus.VALID, statusByVideo.get("video-2"));
            assertEquals(ValidationStatus.ARCHIVED, statusByVideo.get("video-3"));
            assertEquals(ValidationStatus.ERROR, statusByVideo.get("video-4"));

            // Verify skipped items NOT saved
            assertFalse(statusByVideo.containsKey("video-5"));
            assertFalse(statusByVideo.containsKey("video-10"));

            // Verify run statistics
            assertEquals(4, run.getVideosChecked());
            assertEquals(1, run.getVideosMarkedArchived());
            assertEquals(1, run.getErrorCount());
        }

        @Test
        @DisplayName("Skipped items should be eligible for next validation run")
        void skippedItems_shouldBeEligibleForNextRun() throws Exception {
            // This test verifies the logic that skipped items remain eligible for validation
            // because their lastValidatedAt is NOT updated.

            // Arrange: Create videos where some have never been validated
            Video neverValidated1 = createVideo("video-1", "Video 1");
            neverValidated1.setValidationStatus(null);
            neverValidated1.setLastValidatedAt(null);

            Video neverValidated2 = createVideo("video-2", "Video 2");
            neverValidated2.setValidationStatus(null);
            neverValidated2.setLastValidatedAt(null);

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt()))
                    .thenReturn(List.of(neverValidated1, neverValidated2));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt()))
                    .thenReturn(Collections.emptyList());

            // First run: video-1 valid, video-2 skipped
            BatchValidationResult<StreamDetailsDto> firstResult = new BatchValidationResult<>();
            firstResult.addValid("video-1", new StreamDetailsDto());
            firstResult.addSkipped("video-2");

            when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenReturn(firstResult);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act - first run
            service.validateVideos("SCHEDULED", "system", "System", null);

            // Assert: video-1 was saved (status updated), video-2 was NOT saved
            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository, times(1)).save(videoCaptor.capture());

            Video savedVideo = videoCaptor.getValue();
            assertEquals("video-1", savedVideo.getYoutubeId());
            assertEquals(ValidationStatus.VALID, savedVideo.getValidationStatus());
            assertNotNull(savedVideo.getLastValidatedAt());

            // neverValidated2 (video-2) was NOT saved, so it retains:
            // - validationStatus: null
            // - lastValidatedAt: null
            // This means it will be picked up in the next validation run
            // (as the getVideosForValidation() method includes items with null lastValidatedAt)
            assertNull(neverValidated2.getValidationStatus(),
                    "Skipped video should retain null validationStatus");
            assertNull(neverValidated2.getLastValidatedAt(),
                    "Skipped video should retain null lastValidatedAt");
        }
    }

    // Helper method to create test video entities
    private Video createVideo(String youtubeId, String title) {
        Video video = new Video(youtubeId);
        video.setId(UUID.randomUUID().toString());
        video.setTitle(title);
        video.setStatus("APPROVED");
        video.setValidationStatus(null); // Never validated
        video.setLastValidatedAt(null);
        return video;
    }
}
