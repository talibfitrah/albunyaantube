package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Video;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for PublicContentService.getHomeFeed().
 */
@ExtendWith(MockitoExtension.class)
class PublicContentServiceHomeFeedTest {

    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;

    private PublicContentService service;

    @BeforeEach
    void setUp() {
        service = new PublicContentService(
                channelRepository, playlistRepository, videoRepository,
                categoryRepository, orderRepository,
                Runnable::run  // Direct executor for synchronous test execution
        );
    }

    @Test
    void getHomeFeed_returnsEmptyWhenNoCategories() throws Exception {
        when(categoryRepository.findAll()).thenReturn(Collections.emptyList());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertTrue(result.getData().isEmpty());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_skipsEmptyCategories() throws Exception {
        Category cat = makeCategory("c1", "Quran", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        // No order entries and no content in category
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(Collections.emptyList());
        when(channelRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(Collections.emptyList());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertTrue(result.getData().isEmpty());
    }

    @Test
    void getHomeFeed_returnsCategoryWithContent() throws Exception {
        Category cat = makeCategory("c1", "Channels Live", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        // Set up order entries and batch-fetched content
        CategoryContentOrder entry = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(List.of(entry));
        when(orderRepository.countByCategoryId("c1")).thenReturn(1L);

        Channel channel = new Channel();
        channel.setId("ch1");
        channel.setYoutubeId("UC123");
        channel.setName("Test Channel");
        channel.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", channel));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertEquals(1, result.getData().size());
        HomeCategoryDto section = result.getData().get(0);
        assertEquals("c1", section.getId());
        assertEquals("Channels Live", section.getName());
        assertEquals(1, section.getItems().size());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_paginatesCategoriesWithCursor() throws Exception {
        // Create 3 categories, request 2 per page
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        Category cat3 = makeCategory("c3", "C", 2);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2, cat3));

        // Each category has one channel
        for (String catId : List.of("c1", "c2", "c3")) {
            CategoryContentOrder entry = new CategoryContentOrder(catId, "ch_" + catId, "channel", 0);
            when(orderRepository.findByCategoryIdOrderByPosition(catId)).thenReturn(List.of(entry));
            when(orderRepository.countByCategoryId(catId)).thenReturn(1L);

            Channel ch = new Channel();
            ch.setId("ch_" + catId);
            ch.setYoutubeId("UC_" + catId);
            ch.setName("Channel " + catId);
            ch.setStatus("APPROVED");
            when(channelRepository.findAllByIds(List.of("ch_" + catId))).thenReturn(Map.of("ch_" + catId, ch));
        }
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        // First page: 2 categories
        CursorPageDto<HomeCategoryDto> page1 = service.getHomeFeed(null, 2, 10);

        assertEquals(2, page1.getData().size());
        assertNotNull(page1.getPageInfo().getNextCursor());
        assertEquals("c1", page1.getData().get(0).getId());
        assertEquals("c2", page1.getData().get(1).getId());
    }

    @Test
    void getHomeFeed_usesTotalCountFromOrderRepository() throws Exception {
        Category cat = makeCategory("c1", "Quran", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        CategoryContentOrder entry = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(List.of(entry));
        when(orderRepository.countByCategoryId("c1")).thenReturn(42L);

        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setYoutubeId("UC123");
        ch.setName("Test");
        ch.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertEquals(42, result.getData().get(0).getTotalContentCount());
    }

    @Test
    void getHomeFeed_handlesSparseCategoriesAcrossBatches() throws Exception {
        // 8 categories: c1-c5 empty, c6-c8 have content. Request limit=3.
        // Verifies batch scanning continues past empty categories to find content.
        List<Category> cats = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            cats.add(makeCategory("c" + i, "Cat " + i, i));
        }
        when(categoryRepository.findAll()).thenReturn(cats);

        // Default stubs (anyString/anyList catch-alls): registered BEFORE specific matchers
        // so Mockito's last-wins-for-equal-specificity doesn't override the per-category stubs below.
        // Specific matchers (e.g., List.of("ch_c6")) always take priority over anyList().
        when(orderRepository.findByCategoryIdOrderByPosition(anyString())).thenReturn(Collections.emptyList());
        when(orderRepository.countByCategoryId(anyString())).thenReturn(0L);
        when(channelRepository.findByCategoryId(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryId(anyString(), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryId(anyString(), anyInt())).thenReturn(Collections.emptyList());

        // Categories c6-c8 have one channel each via admin-defined order
        when(channelRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        for (String catId : List.of("c6", "c7", "c8")) {
            CategoryContentOrder entry = new CategoryContentOrder(catId, "ch_" + catId, "channel", 0);
            when(orderRepository.findByCategoryIdOrderByPosition(catId)).thenReturn(List.of(entry));
            when(orderRepository.countByCategoryId(catId)).thenReturn(1L);

            Channel ch = new Channel();
            ch.setId("ch_" + catId);
            ch.setYoutubeId("UC_" + catId);
            ch.setName("Channel " + catId);
            ch.setStatus("APPROVED");
            when(channelRepository.findAllByIds(List.of("ch_" + catId))).thenReturn(Map.of("ch_" + catId, ch));
        }

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 3, 10);

        assertEquals(3, result.getData().size());
        assertEquals("c6", result.getData().get(0).getId());
        assertEquals("c7", result.getData().get(1).getId());
        assertEquals("c8", result.getData().get(2).getId());
        // No more categories beyond c8, so hasMore=false
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_fallbackFillsVideosWhenChannelsPlaylistsSparse() throws Exception {
        // Category with no admin-sort order, no channels/playlists, only videos.
        // Verifies the fallback path fills remaining slots with videos.
        Category cat = makeCategory("c1", "Videos Only", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        // No admin-defined order → falls back to per-type queries
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(Collections.emptyList());
        when(orderRepository.countByCategoryId("c1")).thenReturn(0L);

        // No channels or playlists in this category
        when(channelRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(Collections.emptyList());

        // 5 approved videos
        List<Video> videos = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            Video v = new Video();
            v.setId("v" + i);
            v.setYoutubeId("yt_v" + i);
            v.setTitle("Video " + i);
            v.setStatus("APPROVED");
            videos.add(v);
        }
        when(videoRepository.findByCategoryId(eq("c1"), anyInt())).thenReturn(videos);

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertEquals(1, result.getData().size());
        HomeCategoryDto section = result.getData().get(0);
        assertEquals("c1", section.getId());
        // With contentLimit=10: perType=3, channels=0, playlists=0, videoLimit=10-0-0=10
        // Only 5 videos available, so 5 items returned
        assertEquals(5, section.getItems().size());
    }

    @Test
    void getHomeFeed_throwsWhenAllCategoriesFail() throws Exception {
        // When every category content fetch fails, the method should throw
        // to prevent caching a degraded empty response.
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2));

        // All content fetches throw
        when(orderRepository.findByCategoryIdOrderByPosition(anyString()))
                .thenThrow(new RuntimeException("Firestore unavailable"));
        when(orderRepository.countByCategoryId(anyString())).thenReturn(0L);

        assertThrows(RuntimeException.class, () -> service.getHomeFeed(null, 5, 10));
    }

    @Test
    void getHomeFeed_returnsSuccessfulCategoriesWhenSomeFail() throws Exception {
        // 3 categories: c1 succeeds, c2 throws, c3 succeeds.
        // Verifies partial failure returns the successful ones.
        Category cat1 = makeCategory("c1", "Succeeds", 0);
        Category cat2 = makeCategory("c2", "Fails", 1);
        Category cat3 = makeCategory("c3", "Also Succeeds", 2);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2, cat3));

        // Default stubs for batch ID lookups
        when(channelRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(orderRepository.countByCategoryId(anyString())).thenReturn(1L);

        // c1: has one channel via admin order
        CategoryContentOrder entry1 = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(List.of(entry1));
        Channel ch1 = new Channel();
        ch1.setId("ch1"); ch1.setYoutubeId("UC1"); ch1.setName("Chan 1"); ch1.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch1));

        // c2: throws RuntimeException
        when(orderRepository.findByCategoryIdOrderByPosition("c2"))
                .thenThrow(new RuntimeException("Firestore timeout"));

        // c3: has one channel via admin order
        CategoryContentOrder entry3 = new CategoryContentOrder("c3", "ch3", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("c3")).thenReturn(List.of(entry3));
        Channel ch3 = new Channel();
        ch3.setId("ch3"); ch3.setYoutubeId("UC3"); ch3.setName("Chan 3"); ch3.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch3"))).thenReturn(Map.of("ch3", ch3));

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        // c1 and c3 succeed, c2 failed silently
        assertEquals(2, result.getData().size());
        assertEquals("c1", result.getData().get(0).getId());
        assertEquals("c3", result.getData().get(1).getId());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_fallsBackToItemSizeWhenCountFails() throws Exception {
        // Count future throws but items future succeeds.
        // Verifies totalContentCount falls back to items.size() instead of 0.
        Category cat = makeCategory("c1", "Resilient", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        CategoryContentOrder entry = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("c1")).thenReturn(List.of(entry));
        // Count query throws
        when(orderRepository.countByCategoryId("c1"))
                .thenThrow(new RuntimeException("Firestore count timeout"));

        Channel ch = new Channel();
        ch.setId("ch1"); ch.setYoutubeId("UC1"); ch.setName("Chan 1"); ch.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10);

        assertEquals(1, result.getData().size());
        // Count failed → fallback: totalCount = items.size() = 1
        assertEquals(1, result.getData().get(0).getTotalContentCount());
    }

    // --- Helper ---

    private Category makeCategory(String id, String name, int displayOrder) {
        Category cat = new Category();
        cat.setId(id);
        cat.setName(name);
        cat.setDisplayOrder(displayOrder);
        return cat;
    }
}
