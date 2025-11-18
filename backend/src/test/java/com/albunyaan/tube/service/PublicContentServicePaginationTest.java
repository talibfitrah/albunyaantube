package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.util.CursorUtils;
import com.google.cloud.Timestamp;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PublicContentService cursor-based pagination.
 */
@ExtendWith(MockitoExtension.class)
class PublicContentServicePaginationTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private CategoryRepository categoryRepository;

    private PublicContentService publicContentService;

    @BeforeEach
    void setUp() {
        publicContentService = new PublicContentService(
                channelRepository,
                playlistRepository,
                videoRepository,
                categoryRepository
        );
    }

    @Test
    void getContent_channels_returnsNextCursor_whenHasMoreItems() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(5);
        String nextCursor = CursorUtils.encodeFromDocumentId("channel-5");
        ChannelRepository.PaginatedResult<Channel> result =
                new ChannelRepository.PaginatedResult<>(channels, nextCursor, true);

        when(channelRepository.findApprovedBySubscribersDescWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "CHANNELS", null, 5, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getContent_channels_returnsNullCursor_whenLastPage() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(3);
        ChannelRepository.PaginatedResult<Channel> result =
                new ChannelRepository.PaginatedResult<>(channels, null, false);

        when(channelRepository.findApprovedBySubscribersDescWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "CHANNELS", null, 5, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        assertNull(response.getPageInfo().getNextCursor());
        assertFalse(response.getPageInfo().isHasNext());
    }

    @Test
    void getContent_channels_withCategory_usesCategoryQuery() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(2);
        ChannelRepository.PaginatedResult<Channel> result =
                new ChannelRepository.PaginatedResult<>(channels, null, false);

        when(channelRepository.findApprovedByCategoryAndSubscribersDescWithCursor(
                eq("islamic"), eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "CHANNELS", null, 5, "islamic", null, null, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        verify(channelRepository).findApprovedByCategoryAndSubscribersDescWithCursor(
                eq("islamic"), eq(5), isNull());
    }

    @Test
    void getContent_channels_withCursor_passesCursorToRepository() throws Exception {
        // Arrange
        String cursor = CursorUtils.encodeFromDocumentId("prev-channel");
        List<Channel> channels = createTestChannels(5);
        ChannelRepository.PaginatedResult<Channel> result =
                new ChannelRepository.PaginatedResult<>(channels, null, false);

        when(channelRepository.findApprovedBySubscribersDescWithCursor(eq(5), eq(cursor)))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "CHANNELS", cursor, 5, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        verify(channelRepository).findApprovedBySubscribersDescWithCursor(eq(5), eq(cursor));
    }

    @Test
    void getContent_playlists_returnsNextCursor_whenHasMoreItems() throws Exception {
        // Arrange
        List<Playlist> playlists = createTestPlaylists(5);
        String nextCursor = CursorUtils.encodeFromDocumentId("playlist-5");
        PlaylistRepository.PaginatedResult<Playlist> result =
                new PlaylistRepository.PaginatedResult<>(playlists, nextCursor, true);

        when(playlistRepository.findApprovedByItemCountDescWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "PLAYLISTS", null, 5, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getContent_videos_returnsNextCursor_whenHasMoreItems() throws Exception {
        // Arrange
        List<Video> videos = createTestVideos(5);
        String nextCursor = CursorUtils.encodeFromDocumentId("video-5");
        VideoRepository.PaginatedResult<Video> result =
                new VideoRepository.PaginatedResult<>(videos, nextCursor, true);

        when(videoRepository.findApprovedByUploadedAtDescWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", null, 5, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getContent_videos_withFilters_fallsBackToLegacyPagination() throws Exception {
        // Arrange - when filters are applied, legacy pagination is used
        List<Video> videos = createTestVideos(3);

        // Legacy method uses the non-cursor repository method
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt()))
                .thenReturn(videos);

        // Act - apply length filter
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", null, 5, null, "SHORT", null, null
        );

        // Assert
        assertNotNull(response);
        // Legacy pagination generates fake cursor
        verify(videoRepository, never()).findApprovedByUploadedAtDescWithCursor(anyInt(), any());
    }

    @Test
    void getContent_home_usesMixedContentApproach() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(1);
        List<Playlist> playlists = createTestPlaylists(2);
        List<Video> videos = createTestVideos(3);

        when(channelRepository.findAllByOrderBySubscribersDesc(anyInt())).thenReturn(channels);
        when(playlistRepository.findAllByOrderByItemCountDesc(anyInt())).thenReturn(playlists);
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(videos);

        // Act
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "HOME", null, 6, null, null, null, null
        );

        // Assert
        assertNotNull(response);
        // HOME type uses legacy approach with mixed content
        verify(channelRepository).findAllByOrderBySubscribersDesc(anyInt());
        verify(playlistRepository).findAllByOrderByItemCountDesc(anyInt());
        verify(videoRepository).findAllByOrderByUploadedAtDesc(anyInt());
    }

    // Helper methods to create test data

    private List<Channel> createTestChannels(int count) {
        List<Channel> channels = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Channel channel = new Channel();
            channel.setId("channel-" + i);
            channel.setYoutubeId("yt-channel-" + i);
            channel.setName("Test Channel " + i);
            channel.setStatus("APPROVED");
            channel.setSubscribers((long) (count - i + 1) * 1000);
            channels.add(channel);
        }
        return channels;
    }

    private List<Playlist> createTestPlaylists(int count) {
        List<Playlist> playlists = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Playlist playlist = new Playlist();
            playlist.setId("playlist-" + i);
            playlist.setYoutubeId("yt-playlist-" + i);
            playlist.setTitle("Test Playlist " + i);
            playlist.setStatus("APPROVED");
            playlist.setItemCount((count - i + 1) * 10);
            playlists.add(playlist);
        }
        return playlists;
    }

    private List<Video> createTestVideos(int count) {
        List<Video> videos = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Video video = new Video();
            video.setId("video-" + i);
            video.setYoutubeId("yt-video-" + i);
            video.setTitle("Test Video " + i);
            video.setStatus("APPROVED");
            video.setDurationSeconds(300);
            video.setUploadedAt(Timestamp.now());
            videos.add(video);
        }
        return videos;
    }
}
