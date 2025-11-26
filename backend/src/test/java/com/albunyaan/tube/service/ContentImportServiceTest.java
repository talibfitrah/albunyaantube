package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BatchValidationResult;
import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistDetailsDto;
import com.albunyaan.tube.dto.StreamDetailsDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.ValidationRun;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.ValidationRunRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ContentImportService.
 *
 * All tests use mocked dependencies (no real YouTube or Firestore calls).
 * Tests must complete within 30 seconds each (AGENTS.md requirement).
 */
@ExtendWith(MockitoExtension.class)
class ContentImportServiceTest {

    @Mock
    private YouTubeService youtubeService;

    @Mock
    private CategoryMappingService categoryMappingService;

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private ValidationRunRepository validationRunRepository;

    private ContentImportService service;

    @BeforeEach
    void setUp() {
        service = new ContentImportService(
                youtubeService,
                categoryMappingService,
                channelRepository,
                playlistRepository,
                videoRepository,
                validationRunRepository
        );
    }

    /**
     * Test 1: Import 10 channels successfully (all valid)
     * Verifies that all valid channels are imported with correct counts.
     */
    @Test
    void importSimpleFormatAsync_importsTenChannelsSuccessfully() throws Exception {
        // Given: 10 channels in simple format
        Map<String, String> channelsMap = IntStream.range(0, 10)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        // Mock: No existing channels
        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());

        // Mock: All channels validate successfully
        BatchValidationResult<ChannelDetailsDto> validationResult = new BatchValidationResult<>();
        for (String youtubeId : channelsMap.keySet()) {
            ChannelDetailsDto dto = createChannelDto(youtubeId, "Channel " + youtubeId.substring(2));
            validationResult.addValid(youtubeId, dto);
        }
        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(validationResult);

        // Mock: Category mapping
        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));

        // Mock: Channel save
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: All channels imported
        assertEquals(10, run.getChannelsImported());
        assertEquals(0, run.getChannelsSkipped());
        assertEquals(0, run.getChannelsValidationFailed());
        assertEquals(0, run.getChannelsFailed());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());
        verify(channelRepository, times(10)).save(any(Channel.class));
    }

    /**
     * Test 2: Import with 5 existing channels (skipped)
     * Verifies that duplicate channels are skipped correctly.
     */
    @Test
    void importSimpleFormatAsync_skipsFiveExistingChannels() throws Exception {
        // Given: 10 channels, 5 already exist
        Map<String, String> channelsMap = IntStream.range(0, 10)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        // Mock: First 5 channels exist, last 5 don't
        when(channelRepository.findByYoutubeId(argThat(id -> {
            if (id == null) return false;
            int num = Integer.parseInt(id.substring(2));
            return num < 5;
        }))).thenReturn(Optional.of(new Channel()));
        when(channelRepository.findByYoutubeId(argThat(id -> {
            if (id == null) return false;
            int num = Integer.parseInt(id.substring(2));
            return num >= 5;
        }))).thenReturn(Optional.empty());

        // Mock: New channels validate successfully
        BatchValidationResult<ChannelDetailsDto> validationResult = new BatchValidationResult<>();
        for (int i = 5; i < 10; i++) {
            String youtubeId = "UC" + i;
            validationResult.addValid(youtubeId, createChannelDto(youtubeId, "Channel " + i));
        }
        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(validationResult);

        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: 5 imported, 5 skipped
        assertEquals(5, run.getChannelsImported());
        assertEquals(5, run.getChannelsSkipped());
        assertEquals(0, run.getChannelsValidationFailed());
        assertEquals(0, run.getChannelsFailed());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());
        verify(channelRepository, times(5)).save(any(Channel.class));
    }

    /**
     * Test 3: Import with 3 invalid YouTube IDs (validation failed)
     * Verifies that non-existent YouTube content is marked as validation failed.
     */
    @Test
    void importSimpleFormatAsync_marksThreeInvalidYouTubeIdsAsValidationFailed() throws Exception {
        // Given: 10 channels, 3 don't exist on YouTube
        Map<String, String> channelsMap = IntStream.range(0, 10)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        // Mock: No existing channels
        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());

        // Mock: 7 valid, 3 not found on YouTube
        BatchValidationResult<ChannelDetailsDto> validationResult = new BatchValidationResult<>();
        for (int i = 0; i < 7; i++) {
            String youtubeId = "UC" + i;
            validationResult.addValid(youtubeId, createChannelDto(youtubeId, "Channel " + i));
        }
        validationResult.addNotFound("UC7");
        validationResult.addNotFound("UC8");
        validationResult.addNotFound("UC9");

        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(validationResult);
        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: 7 imported, 3 validation failed
        assertEquals(7, run.getChannelsImported());
        assertEquals(0, run.getChannelsSkipped());
        assertEquals(3, run.getChannelsValidationFailed());
        assertEquals(0, run.getChannelsFailed());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());

        // Verify reason counts
        @SuppressWarnings("unchecked")
        Map<String, Integer> reasonCounts = (Map<String, Integer>) run.getDetails().get("reasonCounts");
        assertNotNull(reasonCounts);
        assertTrue(reasonCounts.containsKey("CHANNEL_NOT_FOUND_ON_YOUTUBE"));
        assertEquals(3, reasonCounts.get("CHANNEL_NOT_FOUND_ON_YOUTUBE"));

        verify(channelRepository, times(7)).save(any(Channel.class));
    }

    /**
     * Test 4: Import with 2 Firestore write errors (failed)
     * Verifies that Firestore errors are handled correctly.
     */
    @Test
    void importSimpleFormatAsync_handlesTwoFirestoreWriteErrors() throws Exception {
        // Given: 5 channels
        Map<String, String> channelsMap = IntStream.range(0, 5)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());

        // Mock: All validate successfully
        BatchValidationResult<ChannelDetailsDto> validationResult = new BatchValidationResult<>();
        for (int i = 0; i < 5; i++) {
            validationResult.addValid("UC" + i, createChannelDto("UC" + i, "Channel " + i));
        }
        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(validationResult);
        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));

        // Mock: Firestore fails for 2 channels
        when(channelRepository.save(argThat((Channel c) ->
            c != null && (c.getYoutubeId().equals("UC0") || c.getYoutubeId().equals("UC1"))
        ))).thenThrow(new RuntimeException("Firestore write failed"));

        when(channelRepository.save(argThat((Channel c) ->
            c != null && !c.getYoutubeId().equals("UC0") && !c.getYoutubeId().equals("UC1")
        ))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: 3 imported, 2 failed
        assertEquals(3, run.getChannelsImported());
        assertEquals(0, run.getChannelsSkipped());
        assertEquals(0, run.getChannelsValidationFailed());
        assertEquals(2, run.getChannelsFailed());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());

        // Verify failed items stored
        @SuppressWarnings("unchecked")
        List<String> failedItemIds = (List<String>) run.getDetails().get("failedItemIds");
        assertNotNull(failedItemIds);
        assertEquals(2, failedItemIds.size());
        assertTrue(failedItemIds.contains("channel:UC0") || failedItemIds.contains("channel:UC1"));
    }

    /**
     * Test 5: Large dataset import (1000 items) with batching
     * Verifies batching logic handles large imports correctly.
     * Uses mocks to avoid actual processing time.
     */
    @Test
    void importSimpleFormatAsync_handlesThousandItemsWithBatching() throws Exception {
        // Given: 1000 channels
        Map<String, String> channelsMap = IntStream.range(0, 1000)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());

        // Mock: All validate successfully (batch validation will be called multiple times)
        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> batch = invocation.getArgument(0);
            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            for (String id : batch) {
                result.addValid(id, createChannelDto(id, "Channel " + id.substring(2)));
            }
            return result;
        });

        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(validationRunRepository.save(any(ValidationRun.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: All 1000 imported
        assertEquals(1000, run.getChannelsImported());
        assertEquals(0, run.getChannelsSkipped());
        assertEquals(0, run.getChannelsValidationFailed());
        assertEquals(0, run.getChannelsFailed());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());

        // Verify batch validation called multiple times (batch size is 500)
        verify(youtubeService, atLeast(2)).batchValidateChannelsDtoWithDetails(anyList());
        verify(channelRepository, times(1000)).save(any(Channel.class));
    }

    /**
     * Test 6: Mixed content types (channels, playlists, videos)
     * Verifies all three content types are processed correctly.
     */
    @Test
    void importSimpleFormatAsync_importsMixedContentTypes() throws Exception {
        // Given: 3 channels, 3 playlists, 3 videos
        Map<String, String> channelsMap = Map.of(
                "UC1", "Channel 1|Global",
                "UC2", "Channel 2|Global",
                "UC3", "Channel 3|Global"
        );
        Map<String, String> playlistsMap = Map.of(
                "PL1", "Playlist 1|Global",
                "PL2", "Playlist 2|Global",
                "PL3", "Playlist 3|Global"
        );
        Map<String, String> videosMap = Map.of(
                "VID1", "Video 1|Global",
                "VID2", "Video 2|Global",
                "VID3", "Video 3|Global"
        );
        List<Map<String, String>> simpleData = List.of(channelsMap, playlistsMap, videosMap);

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        // Mock: No existing content
        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());
        when(playlistRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());
        when(videoRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());

        // Mock: All validate successfully
        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> batch = invocation.getArgument(0);
            BatchValidationResult<ChannelDetailsDto> result = new BatchValidationResult<>();
            for (String id : batch) {
                result.addValid(id, createChannelDto(id, "Channel " + id));
            }
            return result;
        });

        when(youtubeService.batchValidatePlaylistsDtoWithDetails(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> batch = invocation.getArgument(0);
            BatchValidationResult<PlaylistDetailsDto> result = new BatchValidationResult<>();
            for (String id : batch) {
                result.addValid(id, createPlaylistDto(id, "Playlist " + id));
            }
            return result;
        });

        when(youtubeService.batchValidateVideosDtoWithDetails(anyList())).thenAnswer(invocation -> {
            @SuppressWarnings("unchecked")
            List<String> batch = invocation.getArgument(0);
            BatchValidationResult<StreamDetailsDto> result = new BatchValidationResult<>();
            for (String id : batch) {
                result.addValid(id, createVideoDto(id, "Video " + id));
            }
            return result;
        });

        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(playlistRepository.save(any(Playlist.class))).thenAnswer(invocation -> invocation.getArgument(0));
        when(videoRepository.save(any(Video.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: All content imported
        assertEquals(3, run.getChannelsImported());
        assertEquals(3, run.getPlaylistsImported());
        assertEquals(3, run.getVideosImported());
        assertEquals(9, run.getTotalImported());
        assertEquals(ValidationRun.STATUS_COMPLETED, run.getStatus());

        verify(channelRepository, times(3)).save(any(Channel.class));
        verify(playlistRepository, times(3)).save(any(Playlist.class));
        verify(videoRepository, times(3)).save(any(Video.class));
    }

    /**
     * Test 7: Reason counts correctly aggregated
     * Verifies that reason breakdown is stored correctly.
     */
    @Test
    void importSimpleFormatAsync_aggregatesReasonCountsCorrectly() throws Exception {
        // Given: Mixed outcomes - imported, skipped, validation failed
        Map<String, String> channelsMap = IntStream.range(0, 10)
                .boxed()
                .collect(Collectors.toMap(
                        i -> "UC" + i,
                        i -> "Channel " + i + "|Global"
                ));
        List<Map<String, String>> simpleData = List.of(
                channelsMap,
                Collections.emptyMap(),
                Collections.emptyMap()
        );

        ValidationRun run = new ValidationRun(ValidationRun.TRIGGER_IMPORT, "test-user", "test@example.com");

        // Mock: 3 existing, 4 valid, 3 not found
        when(channelRepository.findByYoutubeId(argThat(id -> {
            if (id == null) return false;
            int num = Integer.parseInt(id.substring(2));
            return num < 3;
        }))).thenReturn(Optional.of(new Channel()));
        when(channelRepository.findByYoutubeId(argThat(id -> {
            if (id == null) return false;
            int num = Integer.parseInt(id.substring(2));
            return num >= 3;
        }))).thenReturn(Optional.empty());

        BatchValidationResult<ChannelDetailsDto> validationResult = new BatchValidationResult<>();
        for (int i = 3; i < 7; i++) {
            validationResult.addValid("UC" + i, createChannelDto("UC" + i, "Channel " + i));
        }
        validationResult.addNotFound("UC7");
        validationResult.addNotFound("UC8");
        validationResult.addNotFound("UC9");

        when(youtubeService.batchValidateChannelsDtoWithDetails(anyList())).thenReturn(validationResult);
        when(categoryMappingService.mapCategoryNamesToIds(anyString())).thenReturn(List.of("cat-1"));
        when(channelRepository.save(any(Channel.class))).thenAnswer(invocation -> invocation.getArgument(0));

        // When: Import async
        service.importSimpleFormatAsync(run, simpleData, "APPROVED", "test-user");

        // Then: Verify reason counts
        @SuppressWarnings("unchecked")
        Map<String, Integer> reasonCounts = (Map<String, Integer>) run.getDetails().get("reasonCounts");
        assertNotNull(reasonCounts);

        // 3 already exist
        assertTrue(reasonCounts.containsKey("CHANNEL_ALREADY_EXISTS"));
        assertEquals(3, reasonCounts.get("CHANNEL_ALREADY_EXISTS"));

        // 3 not found on YouTube
        assertTrue(reasonCounts.containsKey("CHANNEL_NOT_FOUND_ON_YOUTUBE"));
        assertEquals(3, reasonCounts.get("CHANNEL_NOT_FOUND_ON_YOUTUBE"));

        // 4 imported successfully (implicit reason)
        assertEquals(4, run.getChannelsImported());
    }

    // Helper methods to create DTOs

    private ChannelDetailsDto createChannelDto(String id, String name) {
        ChannelDetailsDto dto = new ChannelDetailsDto();
        dto.setId(id);
        dto.setName(name);
        dto.setDescription("Description for " + name);
        dto.setThumbnailUrl("https://example.com/" + id + ".jpg");
        dto.setSubscriberCount(1000L);
        return dto;
    }

    private PlaylistDetailsDto createPlaylistDto(String id, String name) {
        PlaylistDetailsDto dto = new PlaylistDetailsDto();
        dto.setId(id);
        dto.setName(name);
        dto.setDescription("Description for " + name);
        dto.setThumbnailUrl("https://example.com/" + id + ".jpg");
        dto.setStreamCount(50L);
        dto.setUploaderName("Test Channel");
        dto.setUploaderUrl("https://youtube.com/channel/UC123");
        return dto;
    }

    private StreamDetailsDto createVideoDto(String id, String name) {
        StreamDetailsDto dto = new StreamDetailsDto();
        dto.setId(id);
        dto.setName(name);
        dto.setDescription("Description for " + name);
        dto.setThumbnailUrl("https://example.com/" + id + ".jpg");
        dto.setDuration(300L);
        dto.setViewCount(5000L);
        dto.setUploaderName("Test Channel");
        dto.setUploaderUrl("https://youtube.com/channel/UC123");
        return dto;
    }
}
