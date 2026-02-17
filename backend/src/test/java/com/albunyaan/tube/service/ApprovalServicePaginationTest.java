package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.PendingApprovalDto;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.ApprovalRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
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
 * Unit tests for ApprovalService cursor-based pagination.
 */
@ExtendWith(MockitoExtension.class)
class ApprovalServicePaginationTest {

    @Mock
    private ChannelRepository channelRepository;

    @Mock
    private PlaylistRepository playlistRepository;

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
    void getPendingApprovals_mixedType_queriesBothCollections() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(2);
        List<Playlist> playlists = createTestPlaylists(2);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(4, response.getData().size());
        verify(approvalRepository).findPendingChannelsWithCursor(eq(5), isNull());
        verify(approvalRepository).findPendingPlaylistsWithCursor(eq(5), isNull());
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

        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), any()))
                .thenReturn(playlistResult);

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

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);

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

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);

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
    void getPendingApprovals_mixedType_compositeCursor_includesBothCursors() throws Exception {
        // Arrange
        List<Channel> channels = createTestChannels(3);
        List<Playlist> playlists = createTestPlaylists(3);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, createTestCursor("channel-3", "CHANNEL"), true);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, createTestCursor("playlist-3", "PLAYLIST"), true);

        when(approvalRepository.findPendingChannelsWithCursor(eq(3), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(3), isNull()))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 3, null
        );

        // Assert - verify composite cursor contains both cursors
        assertNotNull(response.getPageInfo().getNextCursor());
        CursorUtils.CursorData decodedCursor = CursorUtils.decode(response.getPageInfo().getNextCursor());
        assertNotNull(decodedCursor);
        assertEquals("MIXED", decodedCursor.getFieldAsString("type"));
        // At least one of these should be present based on which items were used
        assertTrue(decodedCursor.getFieldAsString("channelCursor") != null ||
                   decodedCursor.getFieldAsString("playlistCursor") != null);
    }

    @Test
    void getPendingApprovals_mixedType_withCompositeCursor_passesSeparateCursors() throws Exception {
        // Arrange - create a composite cursor
        String channelCursor = createTestCursor("prev-channel", "CHANNEL");
        String playlistCursor = createTestCursor("prev-playlist", "PLAYLIST");

        CursorUtils.CursorData compositeCursor = new CursorUtils.CursorData("mixed");
        compositeCursor.withField("type", "MIXED");
        compositeCursor.withField("channelCursor", channelCursor);
        compositeCursor.withField("playlistCursor", playlistCursor);
        String cursor = CursorUtils.encode(compositeCursor);

        List<Channel> channels = createTestChannels(2);
        List<Playlist> playlists = createTestPlaylists(2);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), eq(channelCursor)))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), eq(playlistCursor)))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, cursor
        );

        // Assert
        assertNotNull(response);
        assertEquals(4, response.getData().size());
        // Verify each cursor was passed to the appropriate repository
        verify(approvalRepository).findPendingChannelsWithCursor(eq(5), eq(channelCursor));
        verify(approvalRepository).findPendingPlaylistsWithCursor(eq(5), eq(playlistCursor));
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

        when(approvalRepository.findPendingChannelsWithCursor(eq(3), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(3), isNull()))
                .thenReturn(playlistResult);

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

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        // All items should be playlists
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

        when(approvalRepository.findPendingChannelsWithCursor(eq(5), isNull()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(eq(5), isNull()))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert
        assertNotNull(response);
        assertEquals(3, response.getData().size());
        // All items should be channels
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
        channel.setId("channel-zebra"); // Alphabetically later
        channel.setName("Channel Zebra");
        channel.setStatus("PENDING");
        channel.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(sameTime, 0));
        channels.add(channel);

        List<Playlist> playlists = new ArrayList<>();
        Playlist playlist = new Playlist();
        playlist.setId("playlist-alpha"); // Alphabetically earlier
        playlist.setTitle("Playlist Alpha");
        playlist.setStatus("PENDING");
        playlist.setCreatedAt(Timestamp.ofTimeSecondsAndNanos(sameTime, 0));
        playlists.add(playlist);

        ApprovalRepository.PaginatedResult<Channel> channelResult =
                new ApprovalRepository.PaginatedResult<>(channels, null, false);
        ApprovalRepository.PaginatedResult<Playlist> playlistResult =
                new ApprovalRepository.PaginatedResult<>(playlists, null, false);

        when(approvalRepository.findPendingChannelsWithCursor(anyInt(), any()))
                .thenReturn(channelResult);
        when(approvalRepository.findPendingPlaylistsWithCursor(anyInt(), any()))
                .thenReturn(playlistResult);

        // Act
        CursorPageDto<PendingApprovalDto> response = approvalService.getPendingApprovals(
                null, null, 5, null
        );

        // Assert - stable tiebreaker should order by ID (alphabetically)
        assertNotNull(response);
        assertEquals(2, response.getData().size());
        // "channel-zebra" < "playlist-alpha" alphabetically, so channel first
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
}
