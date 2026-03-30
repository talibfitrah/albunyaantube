package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.CategorySortDto;
import com.albunyaan.tube.dto.ContentSortDto;
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
        // initializeCategoryContentOrder now resolves child categories and uses findByCategoryIds
        when(categoryRepository.findByParentId("cat1")).thenReturn(Collections.emptyList());

        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setSubscribers(1000L);
        when(channelRepository.findByCategoryIds(eq(List.of("cat1")), eq(500))).thenReturn(List.of(ch));

        Playlist pl = new Playlist();
        pl.setId("pl1");
        pl.setItemCount(10);
        when(playlistRepository.findByCategoryIds(eq(List.of("cat1")), eq(500))).thenReturn(List.of(pl));

        when(videoRepository.findByCategoryIds(eq(List.of("cat1")), eq(500))).thenReturn(Collections.emptyList());

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

    // --- addMultipleContentToCategory ---

    @Test
    void addMultipleContentToCategory_happyPath() throws Exception {
        // Category exists
        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));

        // Channel exists (returned for contentExists, addCategoryIdToContent, resolveContentInfo)
        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setName("Test Channel");
        ch.setYoutubeId("yt-ch1");
        ch.setCategoryIds(new ArrayList<>());
        when(channelRepository.findById("ch1")).thenReturn(Optional.of(ch));

        // Sort-order entry doesn't exist yet
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.empty());
        when(orderRepository.countByCategoryId("cat1")).thenReturn(0L);

        // After add, getContentSortOrder returns the new entry
        CategoryContentOrder order = makeOrder("cat1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("cat1")).thenReturn(List.of(order));

        List<String[]> items = Collections.singletonList(new String[]{"ch1", "channel"});
        List<ContentSortDto> result = service.addMultipleContentToCategory("cat1", items);

        // Verify sort-order entry saved
        verify(orderRepository).save(any(CategoryContentOrder.class));
        // Verify categoryIds updated on channel
        assertTrue(ch.getCategoryIds().contains("cat1"));
        verify(channelRepository).save(ch);
        // Verify cache evicted
        verify(cacheService).evictPublicContentCaches();
        // Verify result returned
        assertEquals(1, result.size());
        assertEquals("ch1", result.get(0).getContentId());
    }

    @Test
    void addMultipleContentToCategory_throwsWhenCategoryNotFound() throws Exception {
        when(categoryRepository.findById("nonexistent")).thenReturn(Optional.empty());

        List<String[]> items = Collections.singletonList(new String[]{"ch1", "channel"});
        assertThrows(IllegalArgumentException.class, () ->
                service.addMultipleContentToCategory("nonexistent", items)
        );
        // No writes should have happened
        verify(orderRepository, never()).save(any());
        verify(cacheService, never()).evictPublicContentCaches();
    }

    @Test
    void addMultipleContentToCategory_throwsWhenContentNotFound() throws Exception {
        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));
        when(channelRepository.findById("nonexistent")).thenReturn(Optional.empty());

        List<String[]> items = Collections.singletonList(new String[]{"nonexistent", "channel"});
        IllegalArgumentException ex = assertThrows(IllegalArgumentException.class, () ->
                service.addMultipleContentToCategory("cat1", items)
        );
        assertTrue(ex.getMessage().contains("not found"));
        // Validation failed before any writes
        verify(orderRepository, never()).save(any());
    }

    @Test
    void addMultipleContentToCategory_rollsBackOnMidBatchFailure() throws Exception {
        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));

        // ch1 exists and will succeed
        Channel ch1 = new Channel();
        ch1.setId("ch1");
        ch1.setCategoryIds(new ArrayList<>());
        when(channelRepository.findById("ch1")).thenReturn(Optional.of(ch1));

        // pl1 exists for validation but save will fail
        Playlist pl1 = new Playlist();
        pl1.setId("pl1");
        pl1.setCategoryIds(new ArrayList<>());
        when(playlistRepository.findById("pl1")).thenReturn(Optional.of(pl1));

        // Sort-order entries don't exist yet
        when(orderRepository.findById(any())).thenReturn(Optional.empty());
        when(orderRepository.countByCategoryId("cat1")).thenReturn(0L, 1L);

        // pl1 save throws (ch1 save succeeds via Mockito default)
        doThrow(new RuntimeException("Firestore write failed")).when(playlistRepository).save(any(Playlist.class));

        // Rollback needs: removeContentFromCategory calls findById then deleteById
        String ch1DocId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        String pl1DocId = CategoryContentOrder.generateId("cat1", "playlist", "pl1");
        // For rollback, sort-order entries now exist
        when(orderRepository.findById(ch1DocId))
                .thenReturn(Optional.empty())  // first call in addContentToCategory
                .thenReturn(Optional.of(new CategoryContentOrder())); // second call in rollback removeContentFromCategory
        when(orderRepository.findById(pl1DocId))
                .thenReturn(Optional.empty())  // first call in addContentToCategory
                .thenReturn(Optional.of(new CategoryContentOrder())); // second call in rollback
        when(orderRepository.findByCategoryIdOrderByPosition("cat1")).thenReturn(Collections.emptyList());

        List<String[]> items = Arrays.asList(
                new String[]{"ch1", "channel"},
                new String[]{"pl1", "playlist"}
        );

        assertThrows(RuntimeException.class, () ->
                service.addMultipleContentToCategory("cat1", items)
        );

        // Verify rollback: sort-order entries deleted
        verify(orderRepository).deleteById(ch1DocId);
        verify(orderRepository).deleteById(pl1DocId);
        // Verify rollback: ch1 categoryIds reverted (removeCategoryIdFromContent called)
        // ch1 was in writtenCategoryIds, so its categoryIds should have been cleaned
        // Cache should NOT have been evicted (batch failed)
        verify(cacheService, never()).evictPublicContentCaches();
    }

    @Test
    void addMultipleContentToCategory_skipsAlreadyAssignedCategoryId() throws Exception {
        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));

        // Channel already has cat1 in categoryIds
        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setName("Test Channel");
        ch.setYoutubeId("yt-ch1");
        ch.setCategoryIds(new ArrayList<>(List.of("cat1")));
        when(channelRepository.findById("ch1")).thenReturn(Optional.of(ch));

        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.empty());
        when(orderRepository.countByCategoryId("cat1")).thenReturn(0L);

        CategoryContentOrder order = makeOrder("cat1", "ch1", "channel", 0);
        when(orderRepository.findByCategoryIdOrderByPosition("cat1")).thenReturn(List.of(order));

        List<String[]> items = Collections.singletonList(new String[]{"ch1", "channel"});
        service.addMultipleContentToCategory("cat1", items);

        // Channel should NOT be saved again since categoryId was already present
        verify(channelRepository, never()).save(any(Channel.class));
    }

    // --- removeContentFromCategoryAndUpdate ---

    @Test
    void removeContentFromCategoryAndUpdate_removesEntryAndUpdatesCategoryIds() throws Exception {
        // Channel has cat1 in categoryIds
        Channel ch = new Channel();
        ch.setId("ch1");
        ch.setName("Test Channel");
        ch.setYoutubeId("yt-ch1");
        ch.setCategoryIds(new ArrayList<>(List.of("cat1", "cat2")));
        when(channelRepository.findById("ch1")).thenReturn(Optional.of(ch));

        // Sort-order entry exists
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.of(new CategoryContentOrder()));
        when(orderRepository.findByCategoryIdOrderByPosition("cat1")).thenReturn(Collections.emptyList());

        List<ContentSortDto> result = service.removeContentFromCategoryAndUpdate("cat1", "ch1", "channel");

        // categoryIds updated: cat1 removed, cat2 remains
        verify(channelRepository).save(ch);
        assertFalse(ch.getCategoryIds().contains("cat1"));
        assertTrue(ch.getCategoryIds().contains("cat2"));
        // Sort-order entry deleted
        verify(orderRepository).deleteById(docId);
        // Cache evicted
        verify(cacheService).evictPublicContentCaches();
    }

    @Test
    void removeContentFromCategoryAndUpdate_handlesDeletedContent() throws Exception {
        // Content no longer exists (deleted)
        when(channelRepository.findById("ch-gone")).thenReturn(Optional.empty());

        // Sort-order entry exists
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch-gone");
        when(orderRepository.findById(docId)).thenReturn(Optional.of(new CategoryContentOrder()));
        when(orderRepository.findByCategoryIdOrderByPosition("cat1")).thenReturn(Collections.emptyList());

        // Should not throw — removeCategoryIdFromContent handles missing content gracefully
        List<ContentSortDto> result = service.removeContentFromCategoryAndUpdate("cat1", "ch-gone", "channel");

        // Sort-order entry still deleted even though content is gone
        verify(orderRepository).deleteById(docId);
        verify(cacheService).evictPublicContentCaches();
    }

    // --- contentExists ---

    @Test
    void contentExists_channel() throws Exception {
        Channel ch = new Channel();
        ch.setId("ch1");
        when(channelRepository.findById("ch1")).thenReturn(Optional.of(ch));
        when(channelRepository.findById("ch-missing")).thenReturn(Optional.empty());

        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));

        // Existing channel passes validation
        List<String[]> items = Collections.singletonList(new String[]{"ch1", "channel"});
        // contentExists is private, test via addMultipleContentToCategory validation
        // If validation fails, it throws
        // Setup remaining mocks for the full method
        String docId = CategoryContentOrder.generateId("cat1", "channel", "ch1");
        when(orderRepository.findById(docId)).thenReturn(Optional.empty());
        when(orderRepository.countByCategoryId("cat1")).thenReturn(0L);
        ch.setCategoryIds(new ArrayList<>());
        when(orderRepository.findByCategoryIdOrderByPosition("cat1"))
                .thenReturn(List.of(makeOrder("cat1", "ch1", "channel", 0)));

        // Should not throw
        service.addMultipleContentToCategory("cat1", items);

        // Missing channel fails validation
        List<String[]> missingItems = Collections.singletonList(new String[]{"ch-missing", "channel"});
        assertThrows(IllegalArgumentException.class, () ->
                service.addMultipleContentToCategory("cat1", missingItems)
        );
    }

    @Test
    void contentExists_unknownTypeFails() throws Exception {
        Category cat = makeCategory("cat1", "Quran", 0);
        when(categoryRepository.findById("cat1")).thenReturn(Optional.of(cat));

        List<String[]> items = Collections.singletonList(new String[]{"id1", "unknown_type"});
        assertThrows(IllegalArgumentException.class, () ->
                service.addMultipleContentToCategory("cat1", items)
        );
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
