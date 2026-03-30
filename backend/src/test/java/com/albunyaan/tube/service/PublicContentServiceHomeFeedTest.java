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
 *
 * The home feed only shows parent (top-level) categories. Subcategory content
 * is aggregated under the parent section. Methods use List-based category IDs
 * (parent + children) for aggregation.
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

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertTrue(result.getData().isEmpty());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_skipsEmptyCategories() throws Exception {
        Category cat = makeCategory("c1", "Quran", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        // No order entries and no content in category
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(Collections.emptyList());
        when(channelRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(Collections.emptyList());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertTrue(result.getData().isEmpty());
    }

    @Test
    void getHomeFeed_returnsCategoryWithContent() throws Exception {
        Category cat = makeCategory("c1", "Channels Live", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        // Set up order entries and batch-fetched content
        CategoryContentOrder entry = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(List.of(entry));
        when(orderRepository.countByCategoryIds(List.of("c1"))).thenReturn(1L);

        Channel channel = new Channel();
        channel.setId("ch1");
        channel.setYoutubeId("UC123");
        channel.setName("Test Channel");
        channel.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", channel));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertEquals(1, result.getData().size());
        HomeCategoryDto section = result.getData().get(0);
        assertEquals("c1", section.getId());
        assertEquals("Channels Live", section.getName());
        assertEquals(1, section.getItems().size());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_paginatesCategoriesWithCursor() throws Exception {
        // Create 3 parent categories, request 2 per page
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        Category cat3 = makeCategory("c3", "C", 2);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2, cat3));

        // Each category has one channel
        for (String catId : List.of("c1", "c2", "c3")) {
            CategoryContentOrder entry = new CategoryContentOrder(catId, "ch_" + catId, "channel", 0);
            when(orderRepository.findByCategoryIdsOrderByPosition(List.of(catId))).thenReturn(List.of(entry));
            when(orderRepository.countByCategoryIds(List.of(catId))).thenReturn(1L);

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
        CursorPageDto<HomeCategoryDto> page1 = service.getHomeFeed(null, 2, 10, null);

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
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(List.of(entry));
        when(orderRepository.countByCategoryIds(List.of("c1"))).thenReturn(42L);

        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setYoutubeId("UC123");
        ch.setName("Test");
        ch.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertEquals(42, result.getData().get(0).getTotalContentCount());
    }

    @Test
    void getHomeFeed_handlesSparseCategoriesAcrossBatches() throws Exception {
        // 8 parent categories: c1-c5 empty, c6-c8 have content. Request limit=3.
        // Verifies batch scanning continues past empty categories to find content.
        List<Category> cats = new ArrayList<>();
        for (int i = 1; i <= 8; i++) {
            cats.add(makeCategory("c" + i, "Cat " + i, i));
        }
        when(categoryRepository.findAll()).thenReturn(cats);

        // Default stubs: all categories empty
        when(orderRepository.findByCategoryIdsOrderByPosition(anyList())).thenReturn(Collections.emptyList());
        when(orderRepository.countByCategoryIds(anyList())).thenReturn(0L);
        when(channelRepository.findByCategoryIds(anyList(), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryIds(anyList(), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(anyList(), anyInt())).thenReturn(Collections.emptyList());

        when(channelRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        // Categories c6-c8 have one channel each via admin-defined order
        for (String catId : List.of("c6", "c7", "c8")) {
            CategoryContentOrder entry = new CategoryContentOrder(catId, "ch_" + catId, "channel", 0);
            when(orderRepository.findByCategoryIdsOrderByPosition(List.of(catId))).thenReturn(List.of(entry));
            when(orderRepository.countByCategoryIds(List.of(catId))).thenReturn(1L);

            Channel ch = new Channel();
            ch.setId("ch_" + catId);
            ch.setYoutubeId("UC_" + catId);
            ch.setName("Channel " + catId);
            ch.setStatus("APPROVED");
            when(channelRepository.findAllByIds(List.of("ch_" + catId))).thenReturn(Map.of("ch_" + catId, ch));
        }

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 3, 10, null);

        assertEquals(3, result.getData().size());
        assertEquals("c6", result.getData().get(0).getId());
        assertEquals("c7", result.getData().get(1).getId());
        assertEquals("c8", result.getData().get(2).getId());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_fallbackFillsVideosWhenChannelsPlaylistsSparse() throws Exception {
        // Category with no admin-sort order, no channels/playlists, only videos.
        Category cat = makeCategory("c1", "Videos Only", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        // No admin-defined order → falls back to per-type queries
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(Collections.emptyList());
        when(orderRepository.countByCategoryIds(List.of("c1"))).thenReturn(0L);

        // No channels or playlists in this category
        when(channelRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(Collections.emptyList());

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
        when(videoRepository.findByCategoryIds(eq(List.of("c1")), anyInt())).thenReturn(videos);

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertEquals(1, result.getData().size());
        HomeCategoryDto section = result.getData().get(0);
        assertEquals("c1", section.getId());
        assertEquals(5, section.getItems().size());
    }

    @Test
    void getHomeFeed_throwsWhenAllCategoriesFail() throws Exception {
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2));

        // All content fetches throw
        when(orderRepository.findByCategoryIdsOrderByPosition(anyList()))
                .thenThrow(new RuntimeException("Firestore unavailable"));
        when(orderRepository.countByCategoryIds(anyList())).thenReturn(0L);

        assertThrows(Exception.class, () -> service.getHomeFeed(null, 5, 10, null));
    }

    @Test
    void getHomeFeed_returnsSuccessfulCategoriesWhenSomeFail() throws Exception {
        // 3 parent categories: c1 succeeds, c2 throws, c3 succeeds.
        Category cat1 = makeCategory("c1", "Succeeds", 0);
        Category cat2 = makeCategory("c2", "Fails", 1);
        Category cat3 = makeCategory("c3", "Also Succeeds", 2);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2, cat3));

        // Default stubs for batch ID lookups
        when(channelRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(orderRepository.countByCategoryIds(anyList())).thenReturn(1L);

        // c1: has one channel via admin order
        CategoryContentOrder entry1 = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(List.of(entry1));
        Channel ch1 = new Channel();
        ch1.setId("ch1"); ch1.setYoutubeId("UC1"); ch1.setName("Chan 1"); ch1.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch1));

        // c2: throws RuntimeException
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c2")))
                .thenThrow(new RuntimeException("Firestore timeout"));

        // c3: has one channel via admin order
        CategoryContentOrder entry3 = new CategoryContentOrder("c3", "ch3", "channel", 0);
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c3"))).thenReturn(List.of(entry3));
        Channel ch3 = new Channel();
        ch3.setId("ch3"); ch3.setYoutubeId("UC3"); ch3.setName("Chan 3"); ch3.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch3"))).thenReturn(Map.of("ch3", ch3));

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertEquals(2, result.getData().size());
        assertEquals("c1", result.getData().get(0).getId());
        assertEquals("c3", result.getData().get(1).getId());
        assertNull(result.getPageInfo().getNextCursor());
    }

    @Test
    void getHomeFeed_fallsBackToItemSizeWhenCountFails() throws Exception {
        Category cat = makeCategory("c1", "Resilient", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));

        CategoryContentOrder entry = new CategoryContentOrder("c1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("c1"))).thenReturn(List.of(entry));
        // Count query throws
        when(orderRepository.countByCategoryIds(List.of("c1")))
                .thenThrow(new RuntimeException("Firestore count timeout"));

        Channel ch = new Channel();
        ch.setId("ch1"); ch.setYoutubeId("UC1"); ch.setName("Chan 1"); ch.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch1"))).thenReturn(Map.of("ch1", ch));
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        assertEquals(1, result.getData().size());
        assertEquals(1, result.getData().get(0).getTotalContentCount());
    }

    @Test
    void getHomeFeed_filtersByCategoryId() throws Exception {
        Category cat1 = makeCategory("quran", "Quran", 0);
        Category cat2 = makeCategory("kids", "Kids", 1);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2));

        when(channelRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(playlistRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(videoRepository.findAllByIds(anyList())).thenReturn(Collections.emptyMap());
        when(orderRepository.countByCategoryIds(List.of("quran"))).thenReturn(1L);

        CategoryContentOrder entry = new CategoryContentOrder("quran", "ch_quran", "channel", 0);
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("quran"))).thenReturn(List.of(entry));

        Channel ch = new Channel();
        ch.setId("ch_quran");
        ch.setYoutubeId("UC_quran");
        ch.setName("Channel quran");
        ch.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch_quran"))).thenReturn(Map.of("ch_quran", ch));

        // Filter by "quran" category
        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, "quran");

        assertEquals(1, result.getData().size());
        assertEquals("quran", result.getData().get(0).getId());
    }

    @Test
    void getHomeFeed_aggregatesSubcategoryContentUnderParent() throws Exception {
        // Parent "quran" has child "tafsir". Home feed should show only "quran"
        // with content aggregated from both quran and tafsir.
        Category parent = makeCategory("quran", "Quran", 0);
        Category child = makeCategory("tafsir", "Tafsir", 1);
        child.setParentCategoryId("quran");
        Category other = makeCategory("kids", "Kids", 2);
        when(categoryRepository.findAll()).thenReturn(List.of(parent, child, other));
        when(categoryRepository.findByParentId("quran")).thenReturn(List.of(child));

        // quran has sort order entries from both quran and tafsir
        CategoryContentOrder e1 = new CategoryContentOrder("quran", "ch_quran", "channel", 0);
        CategoryContentOrder e2 = new CategoryContentOrder("tafsir", "ch_tafsir", "channel", 0);
        // findByCategoryIdsOrderByPosition([quran, tafsir]) returns merged entries
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("quran", "tafsir")))
                .thenReturn(List.of(e1, e2));
        when(orderRepository.countByCategoryIds(List.of("quran", "tafsir"))).thenReturn(2L);

        Channel ch1 = new Channel();
        ch1.setId("ch_quran"); ch1.setYoutubeId("UC_quran");
        ch1.setName("Quran Channel"); ch1.setStatus("APPROVED");
        Channel ch2 = new Channel();
        ch2.setId("ch_tafsir"); ch2.setYoutubeId("UC_tafsir");
        ch2.setName("Tafsir Channel"); ch2.setStatus("APPROVED");
        when(channelRepository.findAllByIds(List.of("ch_quran", "ch_tafsir")))
                .thenReturn(Map.of("ch_quran", ch1, "ch_tafsir", ch2));
        when(channelRepository.findByCategoryIds(eq(List.of("quran", "tafsir")), anyInt()))
                .thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryIds(eq(List.of("quran", "tafsir")), anyInt()))
                .thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("quran", "tafsir")), anyInt()))
                .thenReturn(Collections.emptyList());

        // kids category is also a parent, set up separately
        when(orderRepository.findByCategoryIdsOrderByPosition(List.of("kids"))).thenReturn(Collections.emptyList());
        when(orderRepository.countByCategoryIds(List.of("kids"))).thenReturn(0L);
        when(channelRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());
        when(playlistRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());
        when(videoRepository.findByCategoryIds(eq(List.of("kids")), anyInt())).thenReturn(Collections.emptyList());

        CursorPageDto<HomeCategoryDto> result = service.getHomeFeed(null, 5, 10, null);

        // Only parent categories shown: quran (with aggregated content) and kids (empty, skipped)
        // tafsir is NOT a separate section — its content is under quran
        assertEquals(1, result.getData().size());
        assertEquals("quran", result.getData().get(0).getId());
        assertEquals(2, result.getData().get(0).getItems().size());
        assertEquals(2, result.getData().get(0).getTotalContentCount());
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
