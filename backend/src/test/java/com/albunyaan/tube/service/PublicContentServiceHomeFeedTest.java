package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CursorPageDto;
import com.albunyaan.tube.dto.HomeCategoryDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
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
                categoryRepository, orderRepository
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

    // --- Helper ---

    private Category makeCategory(String id, String name, int displayOrder) {
        Category cat = new Category();
        cat.setId(id);
        cat.setName(name);
        cat.setDisplayOrder(displayOrder);
        return cat;
    }
}
