package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ApprovalRequestDto;
import com.albunyaan.tube.dto.ApprovalResponseDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.dto.RejectionRequestDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.ApprovalRepository;
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
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for ApprovalService cursor-based pagination.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServicePaginationTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

    @Mock
    private VideoRepository videoRepository;

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private ApprovalRepository approvalRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private SortOrderService sortOrderService;

    private ApprovalService approvalService;

    @BeforeEach
    void setUp() {
        approvalService = new ApprovalService(
                channelRepository,
                playlistRepository,
                videoRepository,
                categoryRepository,
                approvalRepository,
                auditLogService,
                sortOrderService
        );
    }

    @Test
    void getPendingApprovals_channels_returnsNextCursor_whenHasMoreItems() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(5);
        String nextCursor = CursorUtils.encodeFromSnapshot(null, "createdAt");
        // Since we can't create a real snapshot, use encodeFromDocumentId
        nextCursor = createTestCursor("channel-5", "CHANNEL");
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, nextCursor, true);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_channels_returnsNullCursor_whenLastPage() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(3);
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        assertNull(response.getPageInfo().getNextCursor());
        assertFalse(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_channels_withCategory_usesCategoryQuery() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(2);
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);

        when(approvalRepository.findPendingChannelsByCategoryWithCursor(
                eq("islamic"), eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", "islamic", 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        verify(approvalRepository).findPendingChannelsByCategoryWithCursor(
                eq("islamic"), eq(5), isNull());
    }

    @Test
    void getPendingApprovals_channels_withCursor_passesCursorToRepository() throws Exception {
        // Arrange
        String cursor = createTestCursor("prev-channel", "CHANNEL");
        List<Channel> channels = createTestChannels(5);
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), eq(cursor)))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", null, 5, cursor
        );

        // Assert
        assertNotNull(response);
        verify(approvalRepository).findPendingChannelsWithCursor(eq(5), eq(cursor));
    }

    @Test
    void getPendingApprovals_playlists_returnsNextCursor_whenHasMoreItems() throws Exception {
        // Arrange
        List<Playlist> playlists = createTestPlaylists(5);
        String nextCursor = createTestCursor("playlist-5", "PLAYLIST");
        ApprovalRepository.PaginatedResult<Playlist> result =
                new ApprovalRepository.PaginatedResult<>(playlists, nextCursor, true);

        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "PLAYLIST", null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_playlists_withCategory_usesCategoryQuery() throws Exception {
        // Arrange
        List<Playlist> playlists = createTestPlaylists(2);
        ApprovalRepository.PaginatedResult<Playlist> result =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);

        when(approvalRepository.findPendingPlaylistsByCategoryWithCursor(
                eq("quran"), eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "PLAYLIST", "quran", 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        verify(approvalRepository).findPendingPlaylistsByCategoryWithCursor(
                eq("quran"), eq(5), isNull());
    }

    @Test
    void getPendingApprovals_mixedType_queriesAllCollections() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(2);
        List<Playlist> playlists = createTestPlaylists(2);
        List<Video> videos = createTestVideos(1);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(videos, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        verify(approvalRepository).findPendingChannelsWithCursor(eq(5), isNull());
        verify(approvalRepository).findPendingPlaylistsWithCursor(eq(5), isNull());
        verify(approvalRepository).findPendingVideosWithCursor(eq(5), isNull());
    }

    @Test
    void getPendingApprovals_mixedType_sortsBySubmittedAt() throws Exception {
        // Arrange - create items with different timestamps
        List<Channel> channels = new ArrayList<>();
        Channel channel1 = new Channel();
        channel1.setId("channel-1");
        channel1.setName("Old Channel");
        channel1.setStatus("PENDING");
        channel1.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1000, 0));
        channels.add(channel1);

        List<Playlist> playlists = new ArrayList<>();
        Playlist playlist1 = new Playlist();
        playlist1.setId("playlist-1");
        playlist1.setTitle("New Playlist");
        playlist1.setStatus("PENDING");
        playlist1.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(2000, 0));
        playlists.add(playlist1);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), any()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(anyInt(), any()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert - newer item (playlist) should come first
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        assertEquals("playlist-1", response.getData().get(0).getId());
        assertEquals("channel-1", response.getData().get(1).getId());
    }

    @Test
    void getPendingApprovals_cursorIncludesTypeInfo() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(5);
        String nextCursor = createTestCursor("channel-5", "CHANNEL");
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, nextCursor, true);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", null, 5, null
        );

        // Assert - verify cursor contains type info
        assertNotNull(response.getPageInfo().getNextCursor());
        CursorUtils.CursorData decodedCursor = CursorUtils.decode(response.getPageInfo().getNextCursor());
        assertNotNull(decodedCursor);
        assertEquals("CHANNEL", decodedCursor.getFieldAsString("type"));
    }

    @Test
    void getPendingApprovals_defaultsTo20ItemsWhenLimitNull() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(5);
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(20), isNull()))
                .thenReturn(result);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "CHANNEL", null, null, null
        );

        // Assert
        assertNotNull(response);
        verify(approvalRepository).findPendingChannelsWithCursor(eq(20), isNull());
    }

    // Helper methods to create test data

    private List<Channel> createTestChannels(int count) {
        List<Channel> channels = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Channel channel = new Channel();
            channel.setId("channel-" + i);
            channel.setYoutubeId("yt-channel-" + i);
            channel.setName("Test Channel " + i);
            channel.setStatus("PENDING");
            channel.setSubscribers((long) (count - i + 1) * 1000);
            channel.setCreatedAt(Timestamp.now());
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
            playlist.setStatus("PENDING");
            playlist.setItemCount((count - i + 1) * 10);
            playlist.setCreatedAt(Timestamp.now());
            playlists.add(playlist);
        }
        return playlists;
    }

    private String createTestCursor(String id, String type) {
        CursorUtils.CursorData cursorData = new CursorUtils.CursorData(id);
        cursorData.withField("type", type);
        cursorData.withField("createdAt", Timestamp.now());
        return CursorUtils.encode(cursorData);
    }

    @Test
    void getPendingApprovals_mixedType_hasNext_whenMoreChannels() throws Exception {
        // Arrange - channels have more items, playlists don't
        List<Channel> channels = createTestChannels(3);
        List<Playlist> playlists = createTestPlaylists(1);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, createTestCursor("channel-3", "CHANNEL"), true);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(4, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_mixedType_hasNext_whenMorePlaylists() throws Exception {
        // Arrange - playlists have more items, channels don't
        List<Channel> channels = createTestChannels(1);
        List<Playlist> playlists = createTestPlaylists(3);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, createTestCursor("playlist-3", "PLAYLIST"), true);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(4, response.getData().size());
        assertNotNull(response.getPageInfo().getNextCursor());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_mixedType_compositeCursor_includesAllCursors() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(3);
        List<Playlist> playlists = createTestPlaylists(3);
        List<Video> videos = createTestVideos(3);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, createTestCursor("channel-3", "CHANNEL"), true);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, createTestCursor("playlist-3", "PLAYLIST"), true);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(videos, createTestCursor("video-3", "VIDEO"), true);

        when(approvalRepository.findPendingChannelsWithCursor(eq(3), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(3), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(3), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 3, null
        );

        // Assert - verify composite cursor
        assertNotNull(response.getPageInfo().getNextCursor());
        CursorUtils.CursorData decodedCursor = CursorUtils.decode(response.getPageInfo().getNextCursor());
        assertNotNull(decodedCursor);
        assertEquals("MIXED", decodedCursor.getFieldAsString("type"));
    }

    @Test
    void getPendingApprovals_mixedType_withCompositeCursor_passesSeparateCursors() throws Exception {
        // Arrange - create a composite cursor
        String channelCursor = createTestCursor("prev-channel", "CHANNEL");
        String playlistCursor = createTestCursor("prev-playlist", "PLAYLIST");
        String videoCursor = createTestCursor("prev-video", "VIDEO");

        CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
        compositeCursor.withField("type", "MIXED");
        compositeCursor.withField("channelCursor", channelCursor);
        compositeCursor.withField("playlistCursor", playlistCursor);
        compositeCursor.withField("videoCursor", videoCursor);
        String cursor = CursorUtils.encode(compositeCursor);

        List<Channel> channels = createTestChannels(2);
        List<Playlist> playlists = createTestPlaylists(2);
        List<Video> videos = createTestVideos(1);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(videos, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), eq(channelCursor)))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), eq(playlistCursor)))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), eq(videoCursor)))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, cursor
        );

        // Assert
        assertNotNull(response);
        assertEquals(5, response.getData().size());
        verify(approvalRepository).findPendingChannelsWithCursor(eq(5), eq(channelCursor));
        verify(approvalRepository).findPendingPlaylistsWithCursor(eq(5), eq(playlistCursor));
        verify(approvalRepository).findPendingVideosWithCursor(eq(5), eq(videoCursor));
    }

    @Test
    void getPendingApprovals_mixedType_trimsToPageSize() throws Exception {
        // Arrange - return more items than page size
        List<Channel> channels = createTestChannelsWithDates(5, 1000); // older timestamps
        List<Playlist> playlists = createTestPlaylistsWithDates(5, 5000); // newer timestamps

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, createTestCursor("channel-5", "CHANNEL"), true);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, createTestCursor("playlist-5", "PLAYLIST"), true);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(3), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(3), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(3), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 3, null
        );

        // Assert - should only return 3 items (the page size)
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        assertTrue(response.getPageInfo().isHasNext());
    }

    @Test
    void getPendingApprovals_mixedType_emptyChannels_returnsOnlyPlaylists() throws Exception {
        // Arrange
        List<Channel> channels = new ArrayList<>();
        List<Playlist> playlists = createTestPlaylists(3);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        for (PendingApprovalDto dto : response.getData()) {
            assertEquals("PLAYLIST", dto.getType());
        }
    }

    @Test
    void getPendingApprovals_mixedType_emptyPlaylists_returnsOnlyChannels() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(3);
        List<Playlist> playlists = new ArrayList<>();

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        for (PendingApprovalDto dto : response.getData()) {
            assertEquals("CHANNEL", dto.getType());
        }
    }

    @Test
    void getPendingApprovals_mixedType_stableTiebreaker_whenEqualTimestamps() throws Exception {
        // Arrange - create items with identical timestamps
        long sameTime = 5000;

        List<Channel> channels = new ArrayList<>();
        Channel channel = new Channel();
        channel.setId("channel-zebra");
        channel.setName("Channel Zebra");
        channel.setStatus("PENDING");
        channel.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(sameTime, 0));
        channels.add(channel);

        List<Playlist> playlists = new ArrayList<>();
        Playlist playlist = new Playlist();
        playlist.setId("playlist-alpha");
        playlist.setTitle("Playlist Alpha");
        playlist.setStatus("PENDING");
        playlist.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(sameTime, 0));
        playlists.add(playlist);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);
        ApprovalRepository.PaginatedResult<Video> videoResult =
                new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false);

        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), any()))
                .thenReturn(playlistResult);
        when(approvalRepository.findPendingVideosWithCursor(anyInt(), any()))
                .thenReturn(videoResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert - stable tiebreaker should order by ID (alphabetically)
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        assertEquals("channel-zebra", response.getData().get(0).getId());
        assertEquals("playlist-alpha", response.getData().get(1).getId());
    }

    @Test
    void getPendingApprovals_mixedType_invalidCursor_throwsIllegalArgument() {
        // Act & Assert
        assertThrows(IllegalArgumentException.class, () -> {
            approvalService.getPendingApprovals(null, null, 5, "not-a-valid-cursor!");
        });
    }

    // Helper methods for creating test data with specific timestamps

    private List<Channel> createTestChannelsWithDates(int count, long baseTimeSeconds) {
        List<Channel> channels = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Channel channel = new Channel();
            channel.setId("channel-" + i);
            channel.setYoutubeId("yt-channel-" + i);
            channel.setName("Test Channel " + i);
            channel.setStatus("PENDING");
            channel.setSubscribers((long) (count - i + 1) * 1000);
            // Timestamps decrease as i increases (newer items first in array)
            channel.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(baseTimeSeconds + (count - i) * 100, 0));
            channels.add(channel);
        }
        return channels;
    }

    private List<Playlist> createTestPlaylistsWithDates(int count, long baseTimeSeconds) {
        List<Playlist> playlists = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Playlist playlist = new Playlist();
            playlist.setId("playlist-" + i);
            playlist.setYoutubeId("yt-playlist-" + i);
            playlist.setTitle("Test Playlist " + i);
            playlist.setStatus("PENDING");
            playlist.setItemCount((count - i + 1) * 10);
            // Timestamps decrease as i increases (newer items first in array)
            playlist.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(baseTimeSeconds + (count - i) * 100, 0));
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
            video.setStatus("PENDING");
            video.setDurationSeconds(120 * i);
            video.setCreatedAt(Timestamp.now());
            videos.add(video);
        }
        return videos;
    }

    private List<Video> createTestVideosWithDates(int count, long baseTimeSeconds) {
        List<Video> videos = new ArrayList<>();
        for (int i = 1; i <= count; i++) {
            Video video = new Video();
            video.setId("video-" + i);
            video.setYoutubeId("yt-video-" + i);
            video.setTitle("Test Video " + i);
            video.setStatus("PENDING");
            video.setDurationSeconds(120 * i);
            video.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(baseTimeSeconds + (count - i) * 100, 0));
            videos.add(video);
        }
        return videos;
    }

    // ==================== VIDEO PENDING TESTS ====================

    @Test
    void getPendingApprovals_videos_returnsVideoItems() throws Exception {
        List<Video> videos = createTestVideos(3);
        ApprovalRepository.PaginatedResult<Video> result =
                new ApprovalRepository.PaginatedResult<>(videos, null, false);

        when(approvalRepository.findPendingVideosWithCursor(eq(5), isNull()))
                .thenReturn(result);

        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "VIDEO", null, 5, null
        );

        assertNotNull(response);
        assertEquals(3, response.getData().size());
        for (PendingApprovalDto dto : response.getData()) {
            assertEquals("VIDEO", dto.getType());
        }
    }

    @Test
    void getPendingApprovals_videos_withCategory_usesCategoryQuery() throws Exception {
        List<Video> videos = createTestVideos(2);
        ApprovalRepository.PaginatedResult<Video> result =
                new ApprovalRepository.PaginatedResult<>(videos, null, false);

        when(approvalRepository.findPendingVideosByCategoryWithCursor(eq("quran"), eq(5), isNull()))
                .thenReturn(result);

        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "VIDEO", "quran", 5, null
        );

        assertNotNull(response);
        assertEquals(2, response.getData().size());
        verify(approvalRepository).findPendingVideosByCategoryWithCursor(eq("quran"), eq(5), isNull());
    }

    @Test
    void getPendingApprovals_invalidType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> {
            approvalService.getPendingApprovals("INVALID", null, 5, null);
        });
    }

    // ==================== MY SUBMISSIONS TESTS ====================

    @Test
    void getMySubmissions_channelType_queriesChannelsOnly() throws Exception {
        List<Channel> channels = createTestChannels(3);
        channels.forEach(c -> { c.setSubmittedBy("user1"); c.setStatus("PENDING"); });
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);

        when(approvalRepository.findChannelsBySubmitterAndStatus("user1", "PENDING", 20, null))
                .thenReturn(result);

        CursorPageDto<PendingApprovalDto> response = approvalService.getMySubmissions(
                "user1", "PENDING", "CHANNEL", null, null
        );

        assertNotNull(response);
        assertEquals(3, response.getData().size());
        for (PendingApprovalDto dto : response.getData()) {
            assertEquals("CHANNEL", dto.getType());
        }
        verify(approvalRepository).findChannelsBySubmitterAndStatus("user1", "PENDING", 20, null);
    }

    @Test
    void getMySubmissions_videoType_queriesVideosOnly() throws Exception {
        List<Video> videos = createTestVideos(2);
        videos.forEach(v -> { v.setSubmittedBy("user1"); v.setStatus("APPROVED"); });
        ApprovalRepository.PaginatedResult<Video> result =
                new ApprovalRepository.PaginatedResult<>(videos, null, false);

        when(approvalRepository.findVideosBySubmitterAndStatus("user1", "APPROVED", 20, null))
                .thenReturn(result);

        CursorPageDto<PendingApprovalDto> response = approvalService.getMySubmissions(
                "user1", "APPROVED", "VIDEO", null, null
        );

        assertNotNull(response);
        assertEquals(2, response.getData().size());
        for (PendingApprovalDto dto : response.getData()) {
            assertEquals("VIDEO", dto.getType());
        }
    }

    @Test
    void getMySubmissions_mixedType_mergesAllThreeCollections() throws Exception {
        // Channels at time 3000, playlists at 2000, videos at 1000
        List<Channel> channels = new ArrayList<>();
        Channel ch = new Channel();
        ch.setId("ch-1"); ch.setName("Channel"); ch.setStatus("PENDING"); ch.setSubmittedBy("user1");
        ch.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(3000, 0));
        channels.add(ch);

        List<Playlist> playlists = new ArrayList<>();
        Playlist pl = new Playlist();
        pl.setId("pl-1"); pl.setTitle("Playlist"); pl.setStatus("PENDING"); pl.setSubmittedBy("user1");
        pl.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(2000, 0));
        playlists.add(pl);

        List<Video> videos = new ArrayList<>();
        Video vid = new Video();
        vid.setId("vid-1"); vid.setTitle("Video"); vid.setStatus("PENDING"); vid.setSubmittedBy("user1");
        vid.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(1000, 0));
        videos.add(vid);

        when(approvalRepository.findChannelsBySubmitterAndStatus("user1", "PENDING", 20, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(channels, null, false));
        when(approvalRepository.findPlaylistsBySubmitterAndStatus("user1", "PENDING", 20, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(playlists, null, false));
        when(approvalRepository.findVideosBySubmitterAndStatus("user1", "PENDING", 20, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(videos, null, false));

        CursorPageDto<PendingApprovalDto> response = approvalService.getMySubmissions(
                "user1", "PENDING", null, null, null
        );

        assertNotNull(response);
        assertEquals(3, response.getData().size());
        // Newest first: channel (3000), playlist (2000), video (1000)
        assertEquals("ch-1", response.getData().get(0).getId());
        assertEquals("pl-1", response.getData().get(1).getId());
        assertEquals("vid-1", response.getData().get(2).getId());
    }

    @Test
    void getMySubmissions_mixedType_withCursor_passesSeparateCursors() throws Exception {
        String channelCursor = createTestCursor("prev-ch", "CHANNEL");
        String playlistCursor = createTestCursor("prev-pl", "PLAYLIST");
        String videoCursor = createTestCursor("prev-vid", "VIDEO");

        CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
        compositeCursor.withField("type", "MIXED");
        compositeCursor.withField("channelCursor", channelCursor);
        compositeCursor.withField("playlistCursor", playlistCursor);
        compositeCursor.withField("videoCursor", videoCursor);
        String cursor = CursorUtils.encode(compositeCursor);

        when(approvalRepository.findChannelsBySubmitterAndStatus("user1", "PENDING", 20, channelCursor))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));
        when(approvalRepository.findPlaylistsBySubmitterAndStatus("user1", "PENDING", 20, playlistCursor))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));
        when(approvalRepository.findVideosBySubmitterAndStatus("user1", "PENDING", 20, videoCursor))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));

        CursorPageDto<PendingApprovalDto> response = approvalService.getMySubmissions(
                "user1", "PENDING", null, null, cursor
        );

        assertNotNull(response);
        assertEquals(0, response.getData().size());
        verify(approvalRepository).findChannelsBySubmitterAndStatus("user1", "PENDING", 20, channelCursor);
        verify(approvalRepository).findPlaylistsBySubmitterAndStatus("user1", "PENDING", 20, playlistCursor);
        verify(approvalRepository).findVideosBySubmitterAndStatus("user1", "PENDING", 20, videoCursor);
    }

    @Test
    void getMySubmissions_invalidType_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> {
            approvalService.getMySubmissions("user1", "PENDING", "INVALID", null, null);
        });
    }

    @Test
    void getMySubmissions_invalidCursor_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> {
            approvalService.getMySubmissions("user1", "PENDING", null, null, "not-valid!");
        });
    }

    @Test
    void getMySubmissions_mixedType_trimsToPageSize() throws Exception {
        // 3 channels + 3 playlists + 3 videos, page size 5
        List<Channel> channels = createTestChannelsWithDates(3, 7000);
        channels.forEach(c -> c.setSubmittedBy("user1"));
        List<Playlist> playlists = createTestPlaylistsWithDates(3, 4000);
        playlists.forEach(p -> p.setSubmittedBy("user1"));
        List<Video> videos = createTestVideosWithDates(3, 1000);
        videos.forEach(v -> v.setSubmittedBy("user1"));

        when(approvalRepository.findChannelsBySubmitterAndStatus("user1", "PENDING", 5, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(channels, createTestCursor("channel-3", "CHANNEL"), true));
        when(approvalRepository.findPlaylistsBySubmitterAndStatus("user1", "PENDING", 5, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(playlists, createTestCursor("playlist-3", "PLAYLIST"), true));
        when(approvalRepository.findVideosBySubmitterAndStatus("user1", "PENDING", 5, null))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(videos, createTestCursor("video-3", "VIDEO"), true));

        CursorPageDto<PendingApprovalDto> response = approvalService.getMySubmissions(
                "user1", "PENDING", null, 5, null
        );

        assertNotNull(response);
        assertEquals(5, response.getData().size());
        assertTrue(response.getPageInfo().isHasNext());
    }

    // ==================== LIMIT CAP, APPROVE/REJECT VIDEO, METADATA TESTS ====================

    @Test
    void getPendingApprovals_limitCappedAt100() throws Exception {
        List<Channel> channels = createTestChannels(5);
        ApprovalRepository.PaginatedResult<Channel> result =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        when(approvalRepository.findPendingChannelsWithCursor(eq(100), isNull()))
                .thenReturn(result);

        approvalService.getPendingApprovals("CHANNEL", null, 500, null);

        verify(approvalRepository).findPendingChannelsWithCursor(eq(100), isNull());
    }

    @Test
    void approve_video_setsStatusApproved() throws Exception {
        Video video = new Video();
        video.setId("video-1");
        video.setTitle("Test Video");
        video.setStatus("PENDING");
        video.setCategoryIds(List.of("cat-1"));

        when(channelRepository.findById("video-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("video-1")).thenReturn(Optional.empty());
        when(videoRepository.findById("video-1")).thenReturn(Optional.of(video));

        ApprovalRequestDto request = new ApprovalRequestDto("Good content");
        ApprovalResponseDto response = approvalService.approve("video-1", request, "admin-uid", "admin@test.com");

        assertEquals("APPROVED", response.getStatus());
        assertEquals("admin-uid", response.getReviewedBy());
        verify(videoRepository).saveIfStatus(argThat(v -> "APPROVED".equals(v.getStatus())), eq("PENDING"));
        verify(auditLogService).logApproval(eq("video"), eq("video-1"), eq("admin-uid"), eq("admin@test.com"), any());
        verify(sortOrderService).addContentToCategory(eq("cat-1"), eq("video-1"), eq("video"));
    }

    @Test
    void reject_video_setsStatusRejected() throws Exception {
        Video video = new Video();
        video.setId("video-1");
        video.setTitle("Test Video");
        video.setStatus("PENDING");

        when(channelRepository.findById("video-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("video-1")).thenReturn(Optional.empty());
        when(videoRepository.findById("video-1")).thenReturn(Optional.of(video));

        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Not appropriate");
        ApprovalResponseDto response = approvalService.reject("video-1", request, "admin-uid", "admin@test.com");

        assertEquals("REJECTED", response.getStatus());
        assertEquals("admin-uid", response.getReviewedBy());
        verify(videoRepository).saveIfStatus(argThat(v -> "REJECTED".equals(v.getStatus())), eq("PENDING"));
        verify(sortOrderService).removeContentFromAllCategories("video-1", "video");
        verify(auditLogService).logRejection(eq("video"), eq("video-1"), eq("admin-uid"), eq("admin@test.com"), any());
    }

    @Test
    void getPendingApprovals_videos_populatesMetadata() throws Exception {
        Video video = new Video();
        video.setId("video-1");
        video.setYoutubeId("yt-abc123");
        video.setTitle("Test Video");
        video.setStatus("PENDING");
        video.setThumbnailUrl("https://img.youtube.com/thumb.jpg");
        video.setDurationSeconds(300);
        video.setViewCount(10000L);
        video.setChannelTitle("Test Channel");
        video.setCreatedAt(Timestamp.now());

        ApprovalRepository.PaginatedResult<Video> result =
                new ApprovalRepository.PaginatedResult<>(List.of(video), null, false);
        when(approvalRepository.findPendingVideosWithCursor(eq(20), isNull()))
                .thenReturn(result);

        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                "VIDEO", null, null, null
        );

        PendingApprovalDto dto = response.getData().get(0);
        assertEquals("VIDEO", dto.getType());
        assertEquals("Test Video", dto.getTitle());
        assertNotNull(dto.getMetadata());
        assertEquals("yt-abc123", dto.getMetadata().get("youtubeId"));
        assertEquals("https://img.youtube.com/thumb.jpg", dto.getMetadata().get("thumbnailUrl"));
        assertEquals(300, dto.getMetadata().get("durationSeconds"));
        assertEquals(10000L, dto.getMetadata().get("viewCount"));
        assertEquals("Test Channel", dto.getMetadata().get("channelTitle"));
    }

    @Test
    void getPendingApprovals_mixed_allEmpty_returnsEmptyResult() throws Exception {
        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));
        when(approvalRepository.findPendingVideosWithCursor(anyInt(), any()))
                .thenReturn(new ApprovalRepository.PaginatedResult<>(new ArrayList<>(), null, false));

        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        assertNotNull(response);
        assertEquals(0, response.getData().size());
        assertFalse(response.getPageInfo().isHasNext());
    }

    @Test
    void approve_video_withCategoryOverride_appliesOverride() throws Exception {
        Video video = new Video();
        video.setId("video-1");
        video.setTitle("Test Video");
        video.setStatus("PENDING");
        video.setCategoryIds(List.of("old-cat"));

        when(channelRepository.findById("video-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("video-1")).thenReturn(Optional.empty());
        when(videoRepository.findById("video-1")).thenReturn(Optional.of(video));

        ApprovalRequestDto request = new ApprovalRequestDto("Good content");
        request.setCategoryOverride("new-cat");
        approvalService.approve("video-1", request, "admin-uid", "admin@test.com");

        verify(videoRepository).saveIfStatus(argThat(v ->
            v.getCategoryIds() != null && v.getCategoryIds().contains("new-cat")
        ), eq("PENDING"));
        verify(sortOrderService).addContentToCategory(eq("new-cat"), eq("video-1"), eq("video"));
    }

    @Test
    void approve_alreadyApproved_throwsIllegalState() throws Exception {
        Channel channel = new Channel();
        channel.setId("ch-1");
        channel.setStatus("APPROVED");

        when(channelRepository.findById("ch-1")).thenReturn(Optional.of(channel));

        ApprovalRequestDto request = new ApprovalRequestDto("Re-approve");
        assertThrows(IllegalStateException.class, () ->
            approvalService.approve("ch-1", request, "admin-uid", "admin@test.com")
        );

        verify(channelRepository, never()).saveIfStatus(any(), any());
    }

    @Test
    void reject_alreadyRejected_throwsIllegalState() throws Exception {
        Playlist playlist = new Playlist();
        playlist.setId("pl-1");
        playlist.setStatus("REJECTED");

        when(channelRepository.findById("pl-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("pl-1")).thenReturn(Optional.of(playlist));

        RejectionRequestDto request = new RejectionRequestDto("LOW_QUALITY", "Already rejected");
        assertThrows(IllegalStateException.class, () ->
            approvalService.reject("pl-1", request, "admin-uid", "admin@test.com")
        );

        verify(playlistRepository, never()).saveIfStatus(any(), any());
    }

    @Test
    void approve_rejectedItem_throwsIllegalState() throws Exception {
        Video video = new Video();
        video.setId("vid-1");
        video.setStatus("REJECTED");

        when(channelRepository.findById("vid-1")).thenReturn(Optional.empty());
        when(playlistRepository.findById("vid-1")).thenReturn(Optional.empty());
        when(videoRepository.findById("vid-1")).thenReturn(Optional.of(video));

        ApprovalRequestDto request = new ApprovalRequestDto("Approve rejected item");
        assertThrows(IllegalStateException.class, () ->
            approvalService.approve("vid-1", request, "admin-uid", "admin@test.com")
        );

        verify(videoRepository, never()).saveIfStatus(any(), any());
    }
}
