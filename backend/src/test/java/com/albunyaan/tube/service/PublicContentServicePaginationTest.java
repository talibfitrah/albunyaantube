package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.ContentItemDto;
import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
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
import java.util.Collections;
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

    @Mock
    private com.albunyaan.tube.repository.CategoryContentOrderRepository orderRepository;

    private PublicContentService publicContentService;

    @BeforeEach
    void setUp() {
        publicContentService = new PublicContentService(
                channelRepository,
                playlistRepository,
                videoRepository,
                categoryRepository,
                orderRepository,
                Runnable::run  // Direct executor for synchronous test execution
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

    @Test
    void getContent_homeWithCategory_fallbackPaginationLoadsPastFirstPage_whenOnlyOneTypeExists() throws Exception {
        when(categoryRepository.findByParentId("kids")).thenReturn(Collections.emptyList());
        when(channelRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());

        List<Playlist> playlists = createTestPlaylists(80);
        when(playlistRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(playlists);

        CursorPageDto<ContentItemDto> firstPage = publicContentService.getContent(
                "HOME", null, 50, "kids", null, null, null
        );
        CursorPageDto<ContentItemDto> secondPage = publicContentService.getContent(
                "HOME", firstPage.getPageInfo().getNextCursor(), 50, "kids", null, null, null
        );

        assertEquals(50, firstPage.getData().size());
        assertNotNull(firstPage.getPageInfo().getNextCursor());
        assertEquals(30, secondPage.getData().size());
        assertNull(secondPage.getPageInfo().getNextCursor());
        assertNotEquals(firstPage.getData().get(49).getId(), secondPage.getData().get(0).getId());
    }

    @Test
    void getContent_homeWithParentCategory_prefersParentOrderAndAppendsMissingChildContent() throws Exception {
        Category child = new Category();
        child.setId("child");
        child.setParentCategoryId("parent");
        when(categoryRepository.findByParentId("parent")).thenReturn(List.of(child));

        CategoryContentOrder parentEntry = new CategoryContentOrder("parent", "channel-1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("parent")).thenReturn(List.of(parentEntry));

        Channel parentChannel = new Channel();
        parentChannel.setId("channel-1");
        parentChannel.setYoutubeId("yt-parent");
        parentChannel.setName("Parent Ordered");
        parentChannel.setStatus("APPROVED");
        parentChannel.setCategoryIds(List.of("parent"));

        Channel childChannel = new Channel();
        childChannel.setId("channel-2");
        childChannel.setYoutubeId("yt-child");
        childChannel.setName("Child Fallback");
        childChannel.setStatus("APPROVED");
        childChannel.setCategoryIds(List.of("child"));

        when(channelRepository.findAllByIds(List.of("channel-1")))
                .thenReturn(java.util.Map.of("channel-1", parentChannel));
        when(channelRepository.findByCategoryIds(eq(List.of("parent", "child")), anyInt()))
                .thenReturn(List.of(parentChannel, childChannel));
        when(playlistRepository.findByCategoryIds(eq(List.of("parent", "child")), anyInt()))
                .thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("parent", "child")), anyInt()))
                .thenReturn(Collections.emptyList());

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "HOME", null, 10, "parent", null, null, null
        );

        assertEquals(2, response.getData().size());
        assertEquals("yt-parent", response.getData().get(0).getId());
        assertEquals("yt-child", response.getData().get(1).getId());
        verify(orderRepository, never()).findByCategoryIdsOrderByPosition(List.of("parent", "child"));
    }

    @Test
    void getContent_homeWithCategory_rejectsNegativeCursor() throws Exception {
        // Encode a negative offset as a cursor
        String negativeCursor = java.util.Base64.getEncoder()
                .encodeToString("-5".getBytes());

        when(categoryRepository.findByParentId("kids")).thenReturn(Collections.emptyList());

        // getCategoryContentItems resolves order via getEffectiveOrderEntries,
        // then falls back to findByCategoryIds when no order entries exist.
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("kids")))
                .thenReturn(Collections.emptyList());

        // Fallback fetches channels first (empty), then playlists (has items).
        // Once enough items are collected, videos are never fetched (early return).
        when(channelRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());
        List<Playlist> playlists = createTestPlaylists(10);
        when(playlistRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(playlists);

        // Should not throw — negative offset is clamped to 0
        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "HOME", negativeCursor, 5, "kids", null, null, null
        );

        assertNotNull(response);
        // Should return items starting from offset 0 (clamped)
        assertFalse(response.getData().isEmpty());
    }

    @Test
    void getContent_homeWithCategory_exactMultipleDoesNotEmitPhantomCursor() throws Exception {
        // Total items = 20, limit = 20. First page should NOT emit a next cursor
        // because there are no more items beyond the first page.
        when(categoryRepository.findByParentId("kids")).thenReturn(Collections.emptyList());

        // No stored sort order — fall back to findByCategoryIds
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("kids")))
                .thenReturn(Collections.emptyList());

        List<Playlist> playlists = createTestPlaylists(20);
        when(playlistRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(playlists);
        when(channelRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "HOME", null, 20, "kids", null, null, null
        );

        assertEquals(20, response.getData().size());
        // No phantom next cursor: we over-fetched by 1, got exactly 20, so hasNext=false
        assertNull(response.getPageInfo().getNextCursor());
        assertFalse(response.getPageInfo().isHasNext());
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

    // ============ cacheCursorKey tests ============

    @Test
    void cacheCursorKey_homeCursor_normalizesToNumericOffset() {
        // HOME type uses numeric offset cursors encoded as Base64
        String cursor = java.util.Base64.getEncoder().encodeToString("20".getBytes());
        String key = PublicContentService.cacheCursorKey("HOME", cursor);
        assertEquals("20", key);
    }

    @Test
    void cacheCursorKey_nullType_normalizesAsHome() {
        String cursor = java.util.Base64.getEncoder().encodeToString("10".getBytes());
        String key = PublicContentService.cacheCursorKey(null, cursor);
        assertEquals("10", key);
    }

    @Test
    void cacheCursorKey_blankType_normalizesAsHome() {
        String cursor = java.util.Base64.getEncoder().encodeToString("10".getBytes());
        String key = PublicContentService.cacheCursorKey("", cursor);
        assertEquals("10", key);
    }

    @Test
    void cacheCursorKey_channelsCursor_preservedAsIs() {
        // CHANNELS uses opaque Firestore cursors — must NOT be normalized
        String opaqueCursor = CursorUtils.encodeFromDocumentId("channel-42");
        String key = PublicContentService.cacheCursorKey("CHANNELS", opaqueCursor);
        assertEquals(opaqueCursor, key, "Opaque CHANNELS cursor must be preserved as-is");
    }

    @Test
    void cacheCursorKey_playlistsCursor_preservedAsIs() {
        String opaqueCursor = CursorUtils.encodeFromDocumentId("playlist-99");
        String key = PublicContentService.cacheCursorKey("PLAYLISTS", opaqueCursor);
        assertEquals(opaqueCursor, key, "Opaque PLAYLISTS cursor must be preserved as-is");
    }

    @Test
    void cacheCursorKey_videosCursor_preservedAsIs() {
        String opaqueCursor = CursorUtils.encodeFromDocumentId("video-17");
        String key = PublicContentService.cacheCursorKey("VIDEOS", opaqueCursor);
        assertEquals(opaqueCursor, key, "Opaque VIDEOS cursor must be preserved as-is");
    }

    @Test
    void cacheCursorKey_differentOpaqueCursors_produceDifferentKeys() {
        // This is the bug: before the fix, both would normalize to "0"
        String cursor1 = CursorUtils.encodeFromDocumentId("channel-1");
        String cursor2 = CursorUtils.encodeFromDocumentId("channel-2");

        String key1 = PublicContentService.cacheCursorKey("CHANNELS", cursor1);
        String key2 = PublicContentService.cacheCursorKey("CHANNELS", cursor2);

        assertNotEquals(key1, key2,
                "Different opaque cursors must produce different cache keys to avoid serving stale pages");
    }

    @Test
    void cacheCursorKey_nullCursor_returnsZero() {
        assertEquals("0", PublicContentService.cacheCursorKey("CHANNELS", null));
        assertEquals("0", PublicContentService.cacheCursorKey("HOME", null));
    }

    @Test
    void cacheCursorKey_emptyCursor_returnsZero() {
        assertEquals("0", PublicContentService.cacheCursorKey("VIDEOS", ""));
        assertEquals("0", PublicContentService.cacheCursorKey("HOME", ""));
    }

    // ============ normalizeCursor tests ============

    @Test
    void normalizeCursor_compoundHomeFeedCursor_includesOrderAndIdHash() {
        // Home feed cursors use "displayOrder:categoryId" format
        String cursor = java.util.Base64.getEncoder().encodeToString("5:cat-anasheed-uuid".getBytes());
        String result = PublicContentService.normalizeCursor(cursor);
        assertTrue(result.startsWith("5-"), "Should start with displayOrder: " + result);
    }

    @Test
    void normalizeCursor_compoundCursor_differentOrders_produceDifferentKeys() {
        String cursor5 = java.util.Base64.getEncoder().encodeToString("5:cat-abc".getBytes());
        String cursor10 = java.util.Base64.getEncoder().encodeToString("10:cat-xyz".getBytes());
        assertNotEquals(
                PublicContentService.normalizeCursor(cursor5),
                PublicContentService.normalizeCursor(cursor10),
                "Different displayOrder values must produce different cache keys"
        );
    }

    @Test
    void normalizeCursor_sameOrder_differentIds_produceDifferentKeys() {
        // Two categories with same displayOrder but different IDs must not collide
        String cursorA = java.util.Base64.getEncoder().encodeToString("5:cat-abc".getBytes());
        String cursorB = java.util.Base64.getEncoder().encodeToString("5:cat-xyz".getBytes());
        assertNotEquals(
                PublicContentService.normalizeCursor(cursorA),
                PublicContentService.normalizeCursor(cursorB),
                "Same displayOrder but different categoryId must produce different cache keys"
        );
    }

    @Test
    void normalizeCursor_plainIntegerCursor_parsesDirectly() {
        String cursor = java.util.Base64.getEncoder().encodeToString("20".getBytes());
        assertEquals("20", PublicContentService.normalizeCursor(cursor));
    }

    @Test
    void normalizeCursor_nullCursor_returnsZero() {
        assertEquals("0", PublicContentService.normalizeCursor(null));
    }

    @Test
    void normalizeCursor_emptyCursor_returnsZero() {
        assertEquals("0", PublicContentService.normalizeCursor(""));
    }

    @Test
    void normalizeCursor_garbageInput_returnsZero() {
        assertEquals("0", PublicContentService.normalizeCursor("not-base64-!!!"));
    }

    @Test
    void normalizeCursor_negativeOffset_clampedToZero() {
        String cursor = java.util.Base64.getEncoder().encodeToString("-5:cat-id".getBytes());
        String result = PublicContentService.normalizeCursor(cursor);
        assertTrue(result.startsWith("0-"), "Negative displayOrder should clamp to 0: " + result);
    }

    @Test
    void normalizeCursor_negativeSimpleOffset_clampedToZero() {
        String cursor = java.util.Base64.getEncoder().encodeToString("-3".getBytes());
        assertEquals("0", PublicContentService.normalizeCursor(cursor));
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
