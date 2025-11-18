package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ChannelDetailsDto;
import com.albunyaan.tube.dto.PlaylistItemDto;
import com.albunyaan.tube.dto.StreamItemDto;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.schabi.newpipe.extractor.Image;
import org.schabi.newpipe.extractor.channel.ChannelInfo;
import org.schabi.newpipe.extractor.exceptions.ExtractionException;
import org.schabi.newpipe.extractor.playlist.PlaylistInfo;
import org.schabi.newpipe.extractor.stream.StreamInfo;
import org.schabi.newpipe.extractor.stream.StreamInfoItem;

import java.io.IOException;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ChannelOrchestrator
 *
 * Tests verify:
 * - Channel/playlist/video fetching
 * - DTO mapping
 * - Validation operations
 * - Batch operations
 * - Error handling
 */
@ExtendWith(MockitoExtension.class)
class ChannelOrchestratorTest {

    @Mock
    private YouTubeGateway gateway;

    private ChannelOrchestrator orchestrator;

    @BeforeEach
    void setUp() {
        orchestrator = new ChannelOrchestrator(gateway);
    }

    @Nested
    @DisplayName("Channel Operations")
    class ChannelOperationTests {

        @Test
        @DisplayName("Should fetch channel details successfully")
        void getChannelDetails_success() throws Exception {
            // Arrange
            String channelId = "UC123";
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getName()).thenReturn("Test Channel");
            when(channelInfo.getSubscriberCount()).thenReturn(10000L);

            when(gateway.fetchChannelInfo(channelId)).thenReturn(channelInfo);

            // Act
            ChannelInfo result = orchestrator.getChannelDetails(channelId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Channel", result.getName());
            assertEquals(10000L, result.getSubscriberCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getChannelDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchChannelInfo(anyString()))
                    .thenThrow(new ExtractionException("Channel not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getChannelDetails("invalid"));
            assertTrue(exception.getMessage().contains("Channel not found"));
        }
    }

    @Nested
    @DisplayName("Playlist Operations")
    class PlaylistOperationTests {

        @Test
        @DisplayName("Should fetch playlist details successfully")
        void getPlaylistDetails_success() throws Exception {
            // Arrange
            String playlistId = "PL123";
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(playlistInfo.getName()).thenReturn("Test Playlist");
            when(playlistInfo.getStreamCount()).thenReturn(50L);

            when(gateway.fetchPlaylistInfo(playlistId)).thenReturn(playlistInfo);

            // Act
            PlaylistInfo result = orchestrator.getPlaylistDetails(playlistId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Playlist", result.getName());
            assertEquals(50L, result.getStreamCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getPlaylistDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchPlaylistInfo(anyString()))
                    .thenThrow(new ExtractionException("Playlist not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getPlaylistDetails("invalid"));
            assertTrue(exception.getMessage().contains("Playlist not found"));
        }
    }

    @Nested
    @DisplayName("Video Operations")
    class VideoOperationTests {

        @Test
        @DisplayName("Should fetch video details successfully")
        void getVideoDetails_success() throws Exception {
            // Arrange
            String videoId = "abc123";
            StreamInfo streamInfo = mock(StreamInfo.class);
            when(streamInfo.getName()).thenReturn("Test Video");
            when(streamInfo.getViewCount()).thenReturn(100000L);
            when(streamInfo.getDuration()).thenReturn(600L);

            when(gateway.fetchStreamInfo(videoId)).thenReturn(streamInfo);

            // Act
            StreamInfo result = orchestrator.getVideoDetails(videoId);

            // Assert
            assertNotNull(result);
            assertEquals("Test Video", result.getName());
            assertEquals(100000L, result.getViewCount());
        }

        @Test
        @DisplayName("Should wrap extraction exception in IOException")
        void getVideoDetails_extractionError() throws Exception {
            // Arrange
            when(gateway.fetchStreamInfo(anyString()))
                    .thenThrow(new ExtractionException("Video not found"));

            // Act & Assert
            IOException exception = assertThrows(IOException.class,
                    () -> orchestrator.getVideoDetails("invalid"));
            assertTrue(exception.getMessage().contains("Video not found"));
        }
    }

    @Nested
    @DisplayName("Validation Operations")
    class ValidationTests {

        @Test
        @DisplayName("Should return channel info on successful validation")
        void validateAndFetchChannel_success() throws Exception {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(gateway.fetchChannelInfo(anyString())).thenReturn(channelInfo);

            // Act
            ChannelInfo result = orchestrator.validateAndFetchChannel("UC123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on channel validation failure")
        void validateAndFetchChannel_failure() throws Exception {
            // Arrange
            when(gateway.fetchChannelInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            ChannelInfo result = orchestrator.validateAndFetchChannel("invalid");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should return playlist info on successful validation")
        void validateAndFetchPlaylist_success() throws Exception {
            // Arrange
            PlaylistInfo playlistInfo = mock(PlaylistInfo.class);
            when(gateway.fetchPlaylistInfo(anyString())).thenReturn(playlistInfo);

            // Act
            PlaylistInfo result = orchestrator.validateAndFetchPlaylist("PL123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on playlist validation failure")
        void validateAndFetchPlaylist_failure() throws Exception {
            // Arrange
            when(gateway.fetchPlaylistInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            PlaylistInfo result = orchestrator.validateAndFetchPlaylist("invalid");

            // Assert
            assertNull(result);
        }

        @Test
        @DisplayName("Should return video info on successful validation")
        void validateAndFetchVideo_success() throws Exception {
            // Arrange
            StreamInfo streamInfo = mock(StreamInfo.class);
            when(gateway.fetchStreamInfo(anyString())).thenReturn(streamInfo);

            // Act
            StreamInfo result = orchestrator.validateAndFetchVideo("abc123");

            // Assert
            assertNotNull(result);
        }

        @Test
        @DisplayName("Should return null on video validation failure")
        void validateAndFetchVideo_failure() throws Exception {
            // Arrange
            when(gateway.fetchStreamInfo(anyString()))
                    .thenThrow(new ExtractionException("Not found"));

            // Act
            StreamInfo result = orchestrator.validateAndFetchVideo("invalid");

            // Assert
            assertNull(result);
        }
    }

    @Nested
    @DisplayName("Batch Validation")
    class BatchValidationTests {

        @Test
        @DisplayName("Should return empty map for null input")
        void batchValidateChannels_nullInput() {
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for empty input")
        void batchValidateChannels_emptyInput() {
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(Collections.emptyList());
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for null playlist input")
        void batchValidatePlaylists_nullInput() {
            Map<String, PlaylistInfo> result = orchestrator.batchValidatePlaylists(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should return empty map for null video input")
        void batchValidateVideos_nullInput() {
            Map<String, StreamInfo> result = orchestrator.batchValidateVideos(null);
            assertTrue(result.isEmpty());
        }

        @Test
        @DisplayName("Should use gateway executor for batch operations")
        void batchValidateChannels_usesExecutor() throws Exception {
            // Arrange
            when(gateway.runAsync(any())).thenAnswer(invocation -> {
                Runnable runnable = invocation.getArgument(0);
                runnable.run();
                return CompletableFuture.completedFuture(null);
            });

            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(gateway.fetchChannelInfo("UC1")).thenReturn(channelInfo);

            // Act
            Map<String, ChannelInfo> result = orchestrator.batchValidateChannels(List.of("UC1"));

            // Assert
            assertEquals(1, result.size());
            verify(gateway).runAsync(any());
        }
    }

    @Nested
    @DisplayName("DTO Mapping")
    class DtoMappingTests {

        @Test
        @DisplayName("Should map null channel to null DTO")
        void mapToChannelDetailsDto_nullInput() {
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should map channel info to DTO correctly")
        void mapToChannelDetailsDto_success() {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getUrl()).thenReturn("https://youtube.com/channel/UC123");
            when(channelInfo.getName()).thenReturn("Test Channel");
            when(channelInfo.getDescription()).thenReturn("Test Description");
            when(channelInfo.getSubscriberCount()).thenReturn(50000L);
            when(channelInfo.getTags()).thenReturn(List.of("tag1", "tag2"));
            when(channelInfo.getAvatars()).thenReturn(Collections.emptyList());
            when(channelInfo.getBanners()).thenReturn(Collections.emptyList());

            // Act
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(channelInfo);

            // Assert
            assertNotNull(result);
            assertEquals("Test Channel", result.getName());
            assertEquals("Test Description", result.getDescription());
            assertEquals(50000L, result.getSubscriberCount());
        }

        @Test
        @DisplayName("Should map channel with avatars correctly")
        void mapToChannelDetailsDto_withAvatars() {
            // Arrange
            ChannelInfo channelInfo = mock(ChannelInfo.class);
            when(channelInfo.getUrl()).thenReturn("https://youtube.com/channel/UC123");
            when(channelInfo.getName()).thenReturn("Test");
            when(channelInfo.getTags()).thenReturn(Collections.emptyList());

            Image avatar = mock(Image.class);
            when(avatar.getUrl()).thenReturn("https://example.com/avatar.jpg");
            when(channelInfo.getAvatars()).thenReturn(List.of(avatar));
            when(channelInfo.getBanners()).thenReturn(Collections.emptyList());

            // Act
            ChannelDetailsDto result = orchestrator.mapToChannelDetailsDto(channelInfo);

            // Assert
            assertEquals("https://example.com/avatar.jpg", result.getThumbnailUrl());
        }

        @Test
        @DisplayName("Should map null stream item to null DTO")
        void mapToStreamItemDto_nullInput() {
            StreamItemDto result = orchestrator.mapToStreamItemDto(null);
            assertNull(result);
        }

        @Test
        @DisplayName("Should map stream info item to DTO correctly")
        void mapToStreamItemDto_success() {
            // Arrange
            StreamInfoItem streamItem = mock(StreamInfoItem.class);
            when(streamItem.getUrl()).thenReturn("https://youtube.com/watch?v=abc123");
            when(streamItem.getName()).thenReturn("Test Video");
            when(streamItem.getUploaderName()).thenReturn("Test Channel");
            when(streamItem.getUploaderUrl()).thenReturn("https://youtube.com/channel/UC1");
            when(streamItem.getViewCount()).thenReturn(10000L);
            when(streamItem.getDuration()).thenReturn(300L);
            when(streamItem.getThumbnails()).thenReturn(Collections.emptyList());
            when(streamItem.getUploadDate()).thenReturn(null);

            // Act
            StreamItemDto result = orchestrator.mapToStreamItemDto(streamItem);

            // Assert
            assertNotNull(result);
            assertEquals("Test Video", result.getName());
            assertEquals("Test Channel", result.getUploaderName());
            assertEquals(10000L, result.getViewCount());
            assertEquals(300L, result.getDuration());
        }
    }
}
