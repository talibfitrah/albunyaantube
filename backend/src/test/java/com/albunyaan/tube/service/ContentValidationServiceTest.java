package com.albunyaan.tube.service;

import com.albunyaan.tube.config.ValidationProperties;
import com.albunyaan.tube.dto.*;
import com.albunyaan.tube.model.*;
import com.albunyaan.tube.repository.*;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ContentValidationService
 *
 * Tests verify:
 * - Content selection for validation (includes ERROR items after 24h)
 * - Metadata refresh on valid content
 * - Proper archiving vs error handling
 * - Playlist status case handling (lowercase vs uppercase)
 * - Items ordered by lastValidatedAt to prevent validation starvation
 */
@ExtendWith(MockitoExtension.class)
class ContentValidationServiceTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private ValidationRunRepository validationRunRepository;

    @Mock
    private ChannelOrchestrator channelOrchestrator;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private PublicContentCacheService publicContentCacheService;

    @Mock
    private StreamIndexService streamIndexService;

    private ValidationProperties validationProperties;

    private ContentValidationService service;

    @BeforeEach
    void setUp() {
        validationProperties = new ValidationProperties();
        validationProperties.getVideo().setMaxItemsPerRun(10); // Use small value for tests

        service = new ContentValidationService(
                channelRepository,
                playlistRepository,
                videoRepository,
                channelOrchestrator,
                auditLogService,
                validationRunRepository,
                validationProperties,
                publicContentCacheService,
                streamIndexService
        );
    }

    @Nested
    @DisplayName("Channel Validation Tests")
    class ChannelValidationTests {

        @Test
        @DisplayName("Should mark channel as VALID and refresh metadata when found")
        void validateChannel_whenFound_shouldMarkValidAndRefreshMetadata() throws Exception {
            // Arrange
            Channel channel = createChannel("UC123", "Old Name", ValidationStatus.VALID);
            channel.setSubscribers(100L);
            channel.setVideoCount(10);

            // Mocks use findByStatusOrderByLastValidatedAtAsc (ordered by lastValidatedAt ASC to prevent starvation)
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            ChannelDetailsDto dto = new ChannelDetailsDto();
            dto.setName("Updated Name");
            dto.setSubscriberCount(5000L);
            dto.setStreamCount(50L);
            dto.setThumbnailUrl("https://example.com/thumb.jpg");

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addValid("UC123", dto);

            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert
            ArgumentCaptor<Channel> channelCaptor = ArgumentCaptor.forClass(Channel.class);
            verify(channelRepository).save(channelCaptor.capture());

            Channel savedChannel = channelCaptor.getValue();
            assertEquals(ValidationStatus.VALID, savedChannel.getValidationStatus());
            assertEquals("Updated Name", savedChannel.getName());
            assertEquals(5000L, savedChannel.getSubscribers());
            assertEquals(50, savedChannel.getVideoCount());
            assertEquals("https://example.com/thumb.jpg", savedChannel.getThumbnailUrl());
        }

        @Test
        @DisplayName("Should mark channel as ARCHIVED when not found on YouTube")
        void validateChannel_whenNotFound_shouldMarkArchived() throws Exception {
            // Arrange
            Channel channel = createChannel("UC123", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("UC123");

            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert
            ArgumentCaptor<Channel> channelCaptor = ArgumentCaptor.forClass(Channel.class);
            verify(channelRepository).save(channelCaptor.capture());

            Channel savedChannel = channelCaptor.getValue();
            assertEquals(ValidationStatus.ARCHIVED, savedChannel.getValidationStatus());
            assertEquals(1, run.getChannelsMarkedArchived());
        }

        @Test
        @DisplayName("Should mark channel as ERROR for transient errors")
        void validateChannel_whenError_shouldMarkError() throws Exception {
            // Arrange
            Channel channel = createChannel("UC123", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addError("UC123", "Network timeout");

            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert
            ArgumentCaptor<Channel> channelCaptor = ArgumentCaptor.forClass(Channel.class);
            verify(channelRepository).save(channelCaptor.capture());

            Channel savedChannel = channelCaptor.getValue();
            assertEquals(ValidationStatus.ERROR, savedChannel.getValidationStatus());
            assertEquals(1, run.getErrorCount());
        }

        @Test
        @DisplayName("Should skip already archived channels")
        void validateChannel_whenArchived_shouldSkip() throws Exception {
            // Arrange
            Channel archived = createChannel("UC123", "Archived Channel", ValidationStatus.ARCHIVED);
            Channel approved = createChannel("UC456", "Approved Channel", null);

            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(archived, approved));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addValid("UC456", new ChannelDetailsDto());

            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert
            // Only UC456 should be validated (UC123 skipped due to ARCHIVED status)
            ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
            verify(channelOrchestrator).batchValidateChannelsDtoWithDetails(idsCaptor.capture());

            List<String> validatedIds = idsCaptor.getValue();
            assertEquals(1, validatedIds.size());
            assertEquals("UC456", validatedIds.get(0));
        }

        @Test
        @DisplayName("Should include ERROR items after 24 hours")
        void validateChannel_errorAfter24h_shouldRetry() throws Exception {
            // Arrange
            Channel errorChannel = createChannel("UC123", "Error Channel", ValidationStatus.ERROR);
            // Set last validated to 25 hours ago
            Instant twentyFiveHoursAgo = Instant.now().minus(25, ChronoUnit.HOURS);
            errorChannel.setLastValidatedAt(Timestamp.ofTimeSecondsAndNanos(
                    twentyFiveHoursAgo.getEpochSecond(), twentyFiveHoursAgo.getNano()));

            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(errorChannel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addValid("UC123", new ChannelDetailsDto());

            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert - channel should be included for retry
            ArgumentCaptor<List<String>> idsCaptor = ArgumentCaptor.forClass(List.class);
            verify(channelOrchestrator).batchValidateChannelsDtoWithDetails(idsCaptor.capture());

            List<String> validatedIds = idsCaptor.getValue();
            assertEquals(1, validatedIds.size());
            assertEquals("UC123", validatedIds.get(0));
        }
    }

    @Nested
    @DisplayName("Playlist Validation Tests")
    class PlaylistValidationTests {

        @Test
        @DisplayName("Should query both lowercase and uppercase status for playlists (backward compatibility)")
        void validatePlaylist_shouldQueryBothCasesForBackwardCompatibility() throws Exception {
            // Arrange - both queries return empty lists
            // Uses findByStatusOrderByLastValidatedAtAsc (ordered by lastValidatedAt ASC to prevent starvation)
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validatePlaylists("MANUAL", "test-user", "Test User", 100);

            // Assert - verify BOTH cases are queried for backward compatibility
            // (Legacy playlists may use lowercase, newer ones use uppercase)
            verify(playlistRepository).findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt());
            verify(playlistRepository).findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt());
        }

        @Test
        @DisplayName("Should refresh playlist metadata when found")
        void validatePlaylist_whenFound_shouldRefreshMetadata() throws Exception {
            // Arrange
            Playlist playlist = createPlaylist("PL123", "Old Title", null);
            playlist.setItemCount(10);

            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(playlist));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());

            PlaylistDetailsDto dto = new PlaylistDetailsDto();
            dto.setName("Updated Title");
            dto.setStreamCount(50L);
            dto.setThumbnailUrl("https://example.com/playlist.jpg");

            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            result.addValid("PL123", dto);

            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validatePlaylists("MANUAL", "test-user", "Test User", 100);

            // Assert
            ArgumentCaptor<Playlist> playlistCaptor = ArgumentCaptor.forClass(Playlist.class);
            verify(playlistRepository).save(playlistCaptor.capture());

            Playlist savedPlaylist = playlistCaptor.getValue();
            assertEquals(ValidationStatus.VALID, savedPlaylist.getValidationStatus());
            assertEquals("Updated Title", savedPlaylist.getTitle());
            assertEquals(50, savedPlaylist.getItemCount());
        }
    }

    @Nested
    @DisplayName("Video Validation Tests")
    class VideoValidationTests {

        @Test
        @DisplayName("Should refresh video metadata when found")
        void validateVideo_whenFound_shouldRefreshMetadata() throws Exception {
            // Arrange
            Video video = createVideo("abc123", "Old Title", null);
            video.setViewCount(100L);

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(video));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            StreamDetailsDto dto = new StreamDetailsDto();
            dto.setName("Updated Title");
            dto.setViewCount(50000L);
            dto.setDuration(600L);
            dto.setThumbnailUrl("https://example.com/video.jpg");
            dto.setUploaderName("Channel Name");

            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addValid("abc123", dto);

            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validateVideos("MANUAL", "test-user", "Test User", 100);

            // Assert
            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository).save(videoCaptor.capture());

            Video savedVideo = videoCaptor.getValue();
            assertEquals(ValidationStatus.VALID, savedVideo.getValidationStatus());
            assertEquals("Updated Title", savedVideo.getTitle());
            assertEquals(50000L, savedVideo.getViewCount());
            assertEquals(600, savedVideo.getDurationSeconds());
            assertEquals("Channel Name", savedVideo.getChannelTitle());
        }
    }

    @Nested
    @DisplayName("Validate All Content Tests")
    class ValidateAllContentTests {

        @Test
        @DisplayName("Should validate all content types with per-type limits")
        void validateAllContent_shouldValidateAllTypesWithLimits() throws Exception {
            // Arrange - uses findByStatusOrderByLastValidatedAtAsc (ordered by lastValidatedAt ASC)
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateAllContent("MANUAL", "test-user", "Test User", 90);

            // Assert
            assertEquals("COMPLETED", run.getStatus());
            verify(channelRepository).findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt());
            verify(playlistRepository).findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt());
            verify(playlistRepository).findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt());
            verify(videoRepository).findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt());
        }

        @Test
        @DisplayName("Should process all items by default (no implicit per-type cap)")
        void validateAllContent_defaultShouldProcessAllItems() throws Exception {
            // Arrange: two items per type
            Channel c1 = createChannel("UC1", "Channel 1", null);
            Channel c2 = createChannel("UC2", "Channel 2", null);
            Playlist p1 = createPlaylist("PL1", "Playlist 1", null);
            Playlist p2 = createPlaylist("PL2", "Playlist 2", null);
            Video v1 = createVideo("V1", "Video 1", null);
            Video v2 = createVideo("V2", "Video 2", null);

            // Uses findByStatusOrderByLastValidatedAtAsc (ordered by lastValidatedAt ASC to prevent starvation)
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(c1, c2));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(p1, p2));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(v1, v2));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> channelResult = new BatchValidationResult<>();
            channelResult.addValid("UC1", new ChannelDetailsDto());
            channelResult.addValid("UC2", new ChannelDetailsDto());
            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(channelResult);

            BatchValidationResult<PlaylistDetailsDto> playlistResult = new BatchValidationResult<>();
            playlistResult.addValid("PL1", new PlaylistDetailsDto());
            playlistResult.addValid("PL2", new PlaylistDetailsDto());
            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(playlistResult);

            BatchValidationResult<StreamDetailsDto> videoResult = new BatchValidationResult<>();
            videoResult.addValid("V1", new StreamDetailsDto());
            videoResult.addValid("V2", new StreamDetailsDto());
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(videoResult);

            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateAllContent("MANUAL", "tester", "Tester", null);

            // Assert: all IDs were sent for validation and counted
            verify(channelOrchestrator).batchValidateChannelsDtoWithDetails(argThat(ids ->
                    ids.size() == 2 && ids.containsAll(List.of("UC1", "UC2"))));
            verify(channelOrchestrator).batchValidatePlaylistsDtoWithDetails(argThat(ids ->
                    ids.size() == 2 && ids.containsAll(List.of("PL1", "PL2"))));
            verify(channelOrchestrator).batchValidateVideosDtoWithDetails(argThat(ids ->
                    ids.size() == 2 && ids.containsAll(List.of("V1", "V2"))));

            assertEquals(2, run.getChannelsChecked());
            assertEquals(2, run.getPlaylistsChecked());
            assertEquals(2, run.getVideosChecked());
            assertEquals("COMPLETED", run.getStatus());
        }

        @Test
        @DisplayName("Should include lowercase-approved videos in validation")
        void validateVideos_shouldHandleLowercaseApprovedStatus() throws Exception {
            // Arrange: simulate legacy lowercase status in Firestore
            Video lowerCaseVideo = createVideo("VLOWER", "Lowercase Status", null);
            // Force raw status to lowercase to simulate stored data
            var statusField = Video.class.getDeclaredField("status");
            statusField.setAccessible(true);
            statusField.set(lowerCaseVideo, "approved");

            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(lowerCaseVideo));

            BatchValidationResult<StreamDetailsDto> videoResult = new BatchValidationResult<>();
            videoResult.addNotFound("VLOWER");
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(videoResult);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            ValidationRun run = service.validateVideos("MANUAL", "tester", "Tester", null);

            // Assert: the lowercase item was included and processed
            verify(channelOrchestrator).batchValidateVideosDtoWithDetails(argThat(ids ->
                    ids.size() == 1 && ids.contains("VLOWER")));
            assertEquals(1, run.getVideosChecked());
            assertEquals(1, run.getVideosMarkedArchived());
        }
    }

    @Nested
    @DisplayName("Cache Eviction Tests")
    class CacheEvictionTests {

        // --- validateChannels ---

        @Test
        @DisplayName("Should evict public-content caches when at least one channel is archived")
        void validateChannels_anyArchives_evictsPublicContentCaches() throws Exception {
            Channel channel = createChannel("UCabc", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("UCabc");
            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validateChannels("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, atLeastOnce()).evictPublicContentCaches();
        }

        @Test
        @DisplayName("Should NOT evict public-content caches when no channels are archived")
        void validateChannels_noArchives_doesNotEvictCaches() throws Exception {
            Channel channel = createChannel("UCabc", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            // Channel is valid on YouTube — no archive happens
            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addValid("UCabc", new ChannelDetailsDto());
            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validateChannels("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, never()).evictPublicContentCaches();
        }

        // --- validatePlaylists ---

        @Test
        @DisplayName("Should evict public-content caches when at least one playlist is archived")
        void validatePlaylists_anyArchives_evictsPublicContentCaches() throws Exception {
            Playlist playlist = createPlaylist("PLxyz", "Test Playlist", null);
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(playlist));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("PLxyz");
            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validatePlaylists("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, atLeastOnce()).evictPublicContentCaches();
        }

        @Test
        @DisplayName("Should NOT evict public-content caches when no playlists are archived")
        void validatePlaylists_noArchives_doesNotEvictCaches() throws Exception {
            Playlist playlist = createPlaylist("PLxyz", "Test Playlist", null);
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(playlist));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());

            // Playlist is valid on YouTube — no archive happens
            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            result.addValid("PLxyz", new PlaylistDetailsDto());
            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validatePlaylists("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, never()).evictPublicContentCaches();
        }

        // --- validateVideos ---

        @Test
        @DisplayName("Should evict public-content caches when at least one video is archived")
        void validateVideos_anyArchives_evictsPublicContentCaches() throws Exception {
            Video video = createVideo("dQw4w9WgXcQ", "Test Video", null);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(video));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("dQw4w9WgXcQ");
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validateVideos("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, atLeastOnce()).evictPublicContentCaches();
        }

        @Test
        @DisplayName("Should NOT evict public-content caches when no videos are archived")
        void validateVideos_noArchives_doesNotEvictCaches() throws Exception {
            Video video = createVideo("dQw4w9WgXcQ", "Test Video", null);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(video));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            // Video is valid on YouTube — no archive happens
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addValid("dQw4w9WgXcQ", new StreamDetailsDto());
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            service.validateVideos("MANUAL", "test-user", "Test User", 100);

            verify(publicContentCacheService, never()).evictPublicContentCaches();
        }

        // --- Cache eviction resilience (N6) ---

        @Test
        @DisplayName("Run must complete and channel must be archived even when cache eviction throws")
        void validateChannels_evictionThrows_runStillCompletes() throws Exception {
            // Arrange: a channel that YouTube doesn't know about → will be archived.
            Channel channel = createChannel("UCabc", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("UCabc");
            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Simulate Redis being down
            doThrow(new RuntimeException("Redis down")).when(publicContentCacheService).evictPublicContentCaches();

            // Should NOT throw; run must complete normally
            ValidationRun run = assertDoesNotThrow(() ->
                    service.validateChannels("MANUAL", "test-user", "Test User", 100));

            assertEquals("COMPLETED", run.getStatus());

            // Channel was still persisted as ARCHIVED before the failed eviction
            ArgumentCaptor<Channel> channelCaptor = ArgumentCaptor.forClass(Channel.class);
            verify(channelRepository).save(channelCaptor.capture());
            assertEquals(ValidationStatus.ARCHIVED, channelCaptor.getValue().getValidationStatus());
        }

        @Test
        @DisplayName("Run must complete and playlist must be archived even when cache eviction throws")
        void validatePlaylists_evictionThrows_runStillCompletes() throws Exception {
            // Arrange: a playlist that YouTube doesn't know about → will be archived.
            Playlist playlist = createPlaylist("PLxyz", "Test Playlist", null);
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(playlist));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("PLxyz");
            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Simulate Redis being down
            doThrow(new RuntimeException("Redis down")).when(publicContentCacheService).evictPublicContentCaches();

            ValidationRun run = assertDoesNotThrow(() ->
                    service.validatePlaylists("MANUAL", "test-user", "Test User", 100));

            assertEquals("COMPLETED", run.getStatus());

            ArgumentCaptor<Playlist> playlistCaptor = ArgumentCaptor.forClass(Playlist.class);
            verify(playlistRepository).save(playlistCaptor.capture());
            assertEquals(ValidationStatus.ARCHIVED, playlistCaptor.getValue().getValidationStatus());
        }

        @Test
        @DisplayName("Run must complete and video must be archived even when cache eviction throws")
        void validateVideos_evictionThrows_runStillCompletes() throws Exception {
            // Arrange: a video that YouTube doesn't know about → will be archived.
            Video video = createVideo("dQw4w9WgXcQ", "Test Video", null);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(video));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("dQw4w9WgXcQ");
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Simulate Redis being down
            doThrow(new RuntimeException("Redis down")).when(publicContentCacheService).evictPublicContentCaches();

            ValidationRun run = assertDoesNotThrow(() ->
                    service.validateVideos("MANUAL", "test-user", "Test User", 100));

            assertEquals("COMPLETED", run.getStatus());

            ArgumentCaptor<Video> videoCaptor = ArgumentCaptor.forClass(Video.class);
            verify(videoRepository).save(videoCaptor.capture());
            assertEquals(ValidationStatus.ARCHIVED, videoCaptor.getValue().getValidationStatus());
        }
    }

    @Nested
    @DisplayName("Archive Index Cleanup Tests")
    class ArchiveIndexCleanupTests {

        @Test
        @DisplayName("Should call removeSource(CHANNEL) when channel is archived")
        void validateChannels_archivedChannel_callsRemoveSource() throws Exception {
            // Arrange: channel that YouTube reports as not found
            Channel channel = createChannel("UCabc", "Test Channel", null);
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(channel));
            when(channelRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("UCabc");
            when(channelOrchestrator.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validateChannels("MANUAL", "test-user", "Test User", 100);

            // Assert: stream index must be purged for this channel
            verify(streamIndexService).removeSource("CHANNEL", "UCabc");
        }

        @Test
        @DisplayName("Should call removeSource(PLAYLIST) when playlist is archived")
        void validatePlaylists_archivedPlaylist_callsRemoveSource() throws Exception {
            // Arrange: playlist that YouTube reports as not found
            Playlist playlist = createPlaylist("PLxyz", "Test Playlist", null);
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(List.of(playlist));
            when(playlistRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("PLxyz");
            when(channelOrchestrator.batchValidatePlaylistsDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validatePlaylists("MANUAL", "test-user", "Test User", 100);

            // Assert: stream index must be purged for this playlist
            verify(streamIndexService).removeSource("PLAYLIST", "PLxyz");
        }

        @Test
        @DisplayName("Should call markStreamArchived() when video is archived")
        void validateVideos_archivedVideo_callsMarkStreamArchived() throws Exception {
            // Arrange: video that YouTube reports as not found
            Video video = createVideo("dQw4w9WgXcQ", "Test Video", null);
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("APPROVED"), anyInt())).thenReturn(List.of(video));
            when(videoRepository.findByStatusOrderByLastValidatedAtAsc(eq("approved"), anyInt())).thenReturn(Collections.emptyList());

            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            result.addNotFound("dQw4w9WgXcQ");
            when(channelOrchestrator.batchValidateVideosDtoWithDetails(anyList())).thenReturn(result);
            when(validationRunRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

            // Act
            service.validateVideos("MANUAL", "test-user", "Test User", 100);

            // Assert: stream index entry must be marked archived for this video
            verify(streamIndexService).markStreamArchived("dQw4w9WgXcQ");
        }
    }

    // Helper methods to create test entities

    private Channel createChannel(String youtubeId, String name, ValidationStatus status) {
        Channel channel = new Channel(youtubeId);
        channel.setId(UUID.randomUUID().toString());
        channel.setName(name);
        channel.setStatus("APPROVED");
        channel.setValidationStatus(status);
        if (status == null) {
            channel.setLastValidatedAt(null);
        } else {
            // Set lastValidatedAt to 2 days ago so it qualifies for re-validation
            Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
            channel.setLastValidatedAt(Timestamp.ofTimeSecondsAndNanos(
                    twoDaysAgo.getEpochSecond(), twoDaysAgo.getNano()));
        }
        return channel;
    }

    private Playlist createPlaylist(String youtubeId, String title, ValidationStatus status) {
        Playlist playlist = new Playlist(youtubeId);
        playlist.setId(UUID.randomUUID().toString());
        playlist.setTitle(title);
        playlist.setStatus("approved"); // lowercase for playlists
        playlist.setValidationStatus(status);
        if (status == null) {
            playlist.setLastValidatedAt(null);
        } else {
            Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
            playlist.setLastValidatedAt(Timestamp.ofTimeSecondsAndNanos(
                    twoDaysAgo.getEpochSecond(), twoDaysAgo.getNano()));
        }
        return playlist;
    }

    private Video createVideo(String youtubeId, String title, ValidationStatus status) {
        Video video = new Video(youtubeId);
        video.setId(UUID.randomUUID().toString());
        video.setTitle(title);
        video.setStatus("APPROVED");
        video.setValidationStatus(status);
        if (status == null) {
            video.setLastValidatedAt(null);
        } else {
            Instant twoDaysAgo = Instant.now().minus(2, ChronoUnit.DAYS);
            video.setLastValidatedAt(Timestamp.ofTimeSecondsAndNanos(
                    twoDaysAgo.getEpochSecond(), twoDaysAgo.getNano()));
        }
        return video;
    }
}
