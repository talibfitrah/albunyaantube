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

    @Mock
    private com.albunyaan.tube.search.SearchIndex searchIndex;

    private PublicContentService publicContentService;

    @BeforeEach
    void setUp() {
        publicContentService = new PublicContentService(
                channelRepository,
                playlistRepository,
                videoRepository,
                categoryRepository,
                orderRepository,
                Runnable::run,  // Direct executor for synchronous test execution
                searchIndex,
                mock(LiveCategoryService.class)
        );
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
        // Filters run in memory, so the Firestore cursor query is never used
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
    void normalizeCursor_compoundHomeFeedCursor_isItsOwnKey() {
        // Home feed cursors use "displayOrder:categoryId" format; the whole cursor keys the page (B1)
        String cursor = java.util.Base64.getEncoder().encodeToString("5:cat-anasheed-uuid".getBytes());
        assertEquals(cursor, PublicContentService.normalizeCursor(cursor));
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
    void normalizeCursor_orderWithoutId_isItsOwnKey() {
        String cursor = java.util.Base64.getEncoder().encodeToString("20".getBytes());
        assertEquals(cursor, PublicContentService.normalizeCursor(cursor));
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
    void normalizeCursor_garbageInput_isNeverCached() {
        // null key = the @Cacheable condition skips the cache; "0" was the first page's key (B1)
        assertNull(PublicContentService.normalizeCursor("not-base64-!!!"));
    }

    @Test
    void normalizeCursor_negativeOrder_neverTakesTheKeyOfOrderZero() {
        // The page reads -5 as -5: clamping only the key to 0 cached it under "0:cat-id"'s key (B1)
        String negative = java.util.Base64.getEncoder().encodeToString("-5:cat-id".getBytes());
        String zero = java.util.Base64.getEncoder().encodeToString("0:cat-id".getBytes());
        assertNotEquals(PublicContentService.normalizeCursor(zero), PublicContentService.normalizeCursor(negative));
    }

    @Test
    void normalizeCursor_negativeOrderWithoutId_neverTakesTheFirstPageKey() {
        String cursor = java.util.Base64.getEncoder().encodeToString("-3".getBytes());
        assertNotEquals("0", PublicContentService.normalizeCursor(cursor));
    }

    @Test
    void getContent_videos_mostPopular_sortsByViewsDescending() throws Exception {
        List<Video> videos = createTestVideos(3);
        videos.get(0).setViewCount(10L);
        videos.get(1).setViewCount(30L);
        videos.get(2).setViewCount(20L);
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(videos);

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", null, 5, null, null, null, "MOST_POPULAR");

        assertEquals(List.of("yt-video-2", "yt-video-3", "yt-video-1"),
                response.getData().stream().map(ContentItemDto::getId).toList());
    }

    @Test
    void getContent_videos_popular_sortsByViewsDescending() throws Exception {
        List<Video> videos = createTestVideos(3);
        videos.get(0).setViewCount(10L);
        videos.get(1).setViewCount(30L);
        videos.get(2).setViewCount(20L);
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(videos);

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", null, 5, null, null, null, "POPULAR");

        assertEquals(List.of("yt-video-2", "yt-video-3", "yt-video-1"),
                response.getData().stream().map(ContentItemDto::getId).toList());
    }

    @Test
    void getContent_videos_mostPopular_pagesWithoutRepeats() throws Exception {
        List<Video> videos = createTestVideos(7);
        for (int i = 0; i < 7; i++) videos.get(i).setViewCount((long) (i * 37 % 7)); // views 0,2,4,6,1,3,5
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt()))
                .thenAnswer(inv -> videos.subList(0, Math.min(inv.<Integer>getArgument(0), videos.size())));

        assertEquals(List.of("yt-video-4", "yt-video-7", "yt-video-3", "yt-video-6", "yt-video-2", "yt-video-5", "yt-video-1"),
                allPages(null, null, "MOST_POPULAR"));
    }

    @Test
    void getContent_videos_categoryLengthFilter_pagesWithoutRepeats() throws Exception {
        List<Video> videos = createTestVideos(8);
        for (int i = 0; i < 8; i++) videos.get(i).setDurationSeconds(i % 2 == 0 ? 60 : 1800); // odd ids SHORT
        when(categoryRepository.findByParentId("kids")).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryOrderByUploadedAtDesc(eq("kids"), anyInt()))
                .thenAnswer(inv -> videos.subList(0, Math.min(inv.<Integer>getArgument(1), videos.size())));

        assertEquals(List.of("yt-video-1", "yt-video-3", "yt-video-5", "yt-video-7"),
                allPages("kids", "SHORT", null));
    }

    @Test
    void getContent_videos_sortedCursorPastTheWindow_endsTheListWithoutReads() throws Exception {
        String pastWindow = java.util.Base64.getEncoder().encodeToString("200".getBytes());

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", pastWindow, 20, null, null, null, "MOST_POPULAR");

        assertTrue(response.getData().isEmpty());
        assertNull(response.getPageInfo().getNextCursor());
        verifyNoInteractions(videoRepository);
    }

    @Test
    void getContent_videos_sortedMalformedCursor_servesTheFirstPageItsCacheKeyMayShare() throws Exception {
        // cacheCursorKey("VIDEOS", "0") is the first page's key "0": an empty answer here would be cached for it.
        when(videoRepository.findAllByOrderByUploadedAtDesc(anyInt())).thenReturn(createTestVideos(3));

        CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                "VIDEOS", "0", 20, null, null, null, "MOST_POPULAR");

        assertEquals(3, response.getData().size());
    }

    /** Every VIDEOS page, limit 3, following nextCursor; fails on a page that repeats an id. */
    private List<String> allPages(String category, String length, String sort) throws Exception {
        List<String> ids = new ArrayList<>();
        String cursor = null;
        for (int page = 0; page < 10; page++) {
            CursorPageDto<ContentItemDto> response = publicContentService.getContent(
                    "VIDEOS", cursor, 3, category, length, null, sort);
            for (ContentItemDto item : response.getData()) {
                assertFalse(ids.contains(item.getId()), "page " + (page + 1) + " repeats " + item.getId() + " after " + ids);
                ids.add(item.getId());
            }
            cursor = response.getPageInfo().getNextCursor();
            if (cursor == null) return ids;
        }
        return fail("no last page after 10 pages: " + ids);
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
