package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CategorySortDto;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.model.CategoryContentOrder;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.Playlist;
import com.albunyaan.tube.repository.CategoryContentOrderRepository;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * Unit tests for SortOrderService.
 */
@ExtendWith(MockitoExtension.class)
class SortOrderServiceTest {

    @Mock private CategoryRepository categoryRepository;
    @Mock private CategoryContentOrderRepository orderRepository;
    @Mock private ChannelRepository channelRepository;
    @Mock private PlaylistRepository playlistRepository;
    @Mock private VideoRepository videoRepository;
    @Mock private PublicContentCacheService cacheService;

    private SortOrderService service;

    @BeforeEach
    void setUp() {
        service = new SortOrderService(
                categoryRepository, orderRepository,
                channelRepository, playlistRepository, videoRepository,
                cacheService
        );
    }

    // --- getCategorySortOrder ---

    @Test
    void getCategorySortOrder_returnsCategoriesWithCounts() throws Exception {
        Category cat1 = makeCategory("c1", "Quran", 0);
        Category cat2 = makeCategory("c2", "Anasheed", 1);
        when(categoryRepository.findAll()).thenReturn(List.of(cat1, cat2));
        when(orderRepository.countAllGroupedByCategoryId()).thenReturn(Map.of("c1", 3L, "c2", 7L));

        List<CategorySortDto> result = service.getCategorySortOrder();

        assertEquals(2, result.size());
        assertEquals("c1", result.get(0).getId());
        assertEquals(3, result.get(0).getContentCount());
        assertEquals("c2", result.get(1).getId());
        assertEquals(7, result.get(1).getContentCount());
    }

    @Test
    void getCategorySortOrder_zeroCountForUnknownCategory() throws Exception {
        Category cat = makeCategory("c1", "Quran", 0);
        when(categoryRepository.findAll()).thenReturn(List.of(cat));
        when(orderRepository.countAllGroupedByCategoryId()).thenReturn(Collections.emptyMap());

        List<CategorySortDto> result = service.getCategorySortOrder();

        assertEquals(1, result.size());
        assertEquals(0, result.get(0).getContentCount());
    }

    // --- reorderCategory ---

    @Test
    void reorderCategory_insertAndShift() throws Exception {
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        Category cat3 = makeCategory("c3", "C", 2);
        when(categoryRepository.findAll()).thenReturn(new ArrayList<>(List.of(cat1, cat2, cat3)));
        when(orderRepository.countAllGroupedByCategoryId()).thenReturn(Collections.emptyMap());

        // Move cat3 from position 2 to position 0
        service.reorderCategory("c3", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).batchSave(captor.capture());

        List<Category> saved = captor.getValue();
        assertEquals(3, saved.size());
        assertEquals("c3", saved.get(0).getId());
        assertEquals(0, saved.get(0).getDisplayOrder());
        assertEquals("c1", saved.get(1).getId());
        assertEquals(1, saved.get(1).getDisplayOrder());
        assertEquals("c2", saved.get(2).getId());
        assertEquals(2, saved.get(2).getDisplayOrder());
    }

    @Test
    void reorderCategory_throwsForUnknownCategory() throws Exception {
        when(categoryRepository.findAll()).thenReturn(Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () ->
                service.reorderCategory("nonexistent", 0)
        );
    }

    @Test
    void reorderCategory_clampsPositionToValidRange() throws Exception {
        Category cat1 = makeCategory("c1", "A", 0);
        Category cat2 = makeCategory("c2", "B", 1);
        when(categoryRepository.findAll()).thenReturn(new ArrayList<>(List.of(cat1, cat2)));
        when(orderRepository.countAllGroupedByCategoryId()).thenReturn(Collections.emptyMap());

        // Position 999 should be clamped to max valid index (1)
        service.reorderCategory("c1", 999);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<Category>> captor = ArgumentCaptor.forClass(List.class);
        verify(categoryRepository).batchSave(captor.capture());

        List<Category> saved = captor.getValue();
        assertEquals("c2", saved.get(0).getId());
        assertEquals("c1", saved.get(1).getId());
    }

    // --- reorderContentInCategory ---

    @Test
    void reorderContentInCategory_insertAndShift() throws Exception {
        CategoryContentOrder e1 = makeOrder("cat1", "ch1", "channel", 0);
        CategoryContentOrder e2 = makeOrder("cat1", "ch2", "channel", 1);
        CategoryContentOrder e3 = makeOrder("cat1", "ch3", "channel", 2);
        when(orderRepository.findByCategoryIdOrderByPosition("cat1"))
                .thenReturn(new ArrayList<>(List.of(e1, e2, e3)));

        // Move ch3 from position 2 to position 0
        service.reorderContentInCategory("cat1", "ch3", "channel", 0);

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CategoryContentOrder>> captor = ArgumentCaptor.forClass(List.class);
        // batchSave called twice: once for reorder, once for getContentSortOrder re-read
        verify(orderRepository, atLeastOnce()).batchSave(captor.capture());

        List<CategoryContentOrder> saved = captor.getAllValues().get(0);
        assertEquals(3, saved.size());
        assertEquals("ch3", saved.get(0).getContentId());
        assertEquals(0, saved.get(0).getPosition());
        assertEquals("ch1", saved.get(1).getContentId());
        assertEquals(1, saved.get(1).getPosition());
        assertEquals("ch2", saved.get(2).getContentId());
        assertEquals(2, saved.get(2).getPosition());
    }

    @Test
    void reorderContentInCategory_throwsForUnknownContent() throws Exception {
        when(orderRepository.findByCategoryIdOrderByPosition("cat1"))
                .thenReturn(Collections.emptyList());

        assertThrows(IllegalArgumentException.class, () ->
                service.reorderContentInCategory("cat1", "nonexistent", "channel", 0)
        );
    }

    // --- addContentToCategory ---

    @Test
    void addContentToCategory_appendsAtEnd() throws Exception {
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.empty());
        when(orderRepository.countByCategoryId("cat1")).thenReturn(5L);

        service.addContentToCategory("cat1", "ch1", "channel");

        ArgumentCaptor<CategoryContentOrder> captor = ArgumentCaptor.forClass(CategoryContentOrder.class);
        verify(orderRepository).save(captor.capture());
        assertEquals(5, captor.getValue().getPosition());
    }

    @Test
    void addContentToCategory_skipsIfAlreadyExists() throws Exception {
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.of(new CategoryContentOrder()));

        service.addContentToCategory("cat1", "ch1", "channel");

        verify(orderRepository, never()).save(any());
    }

    // --- removeContentFromCategory ---

    @Test
    void removeContentFromCategory_renumbersRemaining() throws Exception {
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.of(new CategoryContentOrder()));
        CategoryContentOrder remaining1 = makeOrder("cat1", "ch2", "channel", 1);
        CategoryContentOrder remaining2 = makeOrder("cat1", "ch3", "channel", 2);
        when(orderRepository.findByCategoryIdOrderByPosition("cat1"))
                .thenReturn(List.of(remaining1, remaining2));

        service.removeContentFromCategory("cat1", "ch1", "channel");

        verify(orderRepository).deleteById(docId);
        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CategoryContentOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderRepository).batchSave(captor.capture());
        assertEquals(0, captor.getValue().get(0).getPosition());
        assertEquals(1, captor.getValue().get(1).getPosition());
    }

    // --- initializeCategoryContentOrder ---

    @Test
    void initializeCategoryContentOrder_seedsInDefaultOrder() throws Exception {
        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setSubscribers(1000L);
        when(channelRepository.findByCategoryId(eq("cat1"), eq(500))).thenReturn(List.of(ch));

        Playlist pl = new Playlist();
        pl.setId("pl1");
        pl.setItemCount(10);
        when(playlistRepository.findByCategoryId(eq("cat1"), eq(500))).thenReturn(List.of(pl));

        when(videoRepository.findByCategoryId(eq("cat1"), eq(500))).thenReturn(Collections.emptyList());

        service.initializeCategoryContentOrder("cat1");

        @SuppressWarnings("unchecked")
        ArgumentCaptor<List<CategoryContentOrder>> captor = ArgumentCaptor.forClass(List.class);
        verify(orderRepository).batchSave(captor.capture());

        List<CategoryContentOrder> saved = captor.getValue();
        assertEquals(2, saved.size());
        assertEquals("ch1", saved.get(0).getContentId());
        assertEquals("channel", saved.get(0).getContentType());
        assertEquals(0, saved.get(0).getPosition());
        assertEquals("pl1", saved.get(1).getContentId());
        assertEquals("playlist", saved.get(1).getContentType());
        assertEquals(1, saved.get(1).getPosition());
    }

    // --- Helpers ---

    private Category makeCategory(String id, String name, int displayOrder) {
        Category cat = new Category();
        cat.setId(id);
        cat.setName(name);
        cat.setDisplayOrder(displayOrder);
        return cat;
    }

    private CategoryContentOrder makeOrder(String categoryId, String contentId, String contentType, int position) {
        CategoryContentOrder order = new CategoryContentOrder(categoryId, contentId, contentType, position);
        return order;
    }
}
