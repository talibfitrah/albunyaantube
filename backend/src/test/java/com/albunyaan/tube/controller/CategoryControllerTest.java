package com.albunyaan.tube.controller;

import com.albunyaan.tube.exception.LiveCategoryException;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ExecutionException;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * BACKEND-REG-01: Unit tests for CategoryController
 */
@ExtendWith(MockitoExtension.class)
class CategoryControllerTest {

    @Mock
    private CategoryRepository categoryRepository;

    @Mock
    private AuditLogService auditLogService;

    @Mock
    private com.albunyaan.tube.service.PublicContentCacheService publicContentCacheService;

    @Mock
    private com.albunyaan.tube.service.SortOrderService sortOrderService;

    @Mock
    private com.albunyaan.tube.service.LiveCategoryGuard liveCategoryGuard;

    @InjectMocks
    private CategoryController categoryController;

    private FirebaseUserDetails adminUser;
    private Category parentCategory;
    private Category subCategory;

    @BeforeEach
    void setUp() {
        // Create admin user
        adminUser = new FirebaseUserDetails("admin-uid", "admin@test.com", "admin", true);

        // Create parent category
        parentCategory = new Category("Quran", null);
        parentCategory.setId("quran");
        parentCategory.setDisplayOrder(1);

        // Create subcategory
        subCategory = new Category("Tafsir", "quran");
        subCategory.setId("tafsir");
        subCategory.setDisplayOrder(1);
    }

    @Test
    void getAllCategories_shouldReturnAllCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Category> categories = Arrays.asList(parentCategory, subCategory);
        when(categoryRepository.findAll()).thenReturn(categories);

        // Act
        ResponseEntity<List<Category>> response = categoryController.getAllCategories();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(2, response.getBody().size());
        verify(categoryRepository).findAll();
    }

    @Test
    void getTopLevelCategories_shouldReturnOnlyTopLevel() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Category> topLevelCategories = Arrays.asList(parentCategory);
        when(categoryRepository.findTopLevel()).thenReturn(topLevelCategories);

        // Act
        ResponseEntity<List<Category>> response = categoryController.getTopLevelCategories();

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertTrue(response.getBody().get(0).isTopLevel());
    }

    @Test
    void getSubcategories_shouldReturnChildCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        List<Category> subcategories = Arrays.asList(subCategory);
        when(categoryRepository.findByParentId("quran")).thenReturn(subcategories);

        // Act
        ResponseEntity<List<Category>> response = categoryController.getSubcategories("quran");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals(1, response.getBody().size());
        assertEquals("quran", response.getBody().get(0).getParentCategoryId());
    }

    @Test
    void getCategoryById_shouldReturnCategory_whenExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(categoryRepository.findById("quran")).thenReturn(Optional.of(parentCategory));

        // Act
        ResponseEntity<Category> response = categoryController.getCategoryById("quran");

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Quran", response.getBody().getName());
    }

    @Test
    void getCategoryById_shouldReturn404_whenNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(categoryRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Category> response = categoryController.getCategoryById("nonexistent");

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    }

    @Test
    void createCategory_shouldCreateTopLevelCategory() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Category newCategory = new Category("Hadith", null);
        when(categoryRepository.save(any(Category.class))).thenReturn(newCategory);

        // Act
        ResponseEntity<Category> response = categoryController.createCategory(newCategory, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("admin-uid", newCategory.getCreatedBy());
        assertEquals("admin-uid", newCategory.getUpdatedBy());
        verify(categoryRepository).save(newCategory);
        verify(auditLogService).log(eq("category_created"), eq("category"), any(), eq(adminUser));
    }

    @Test
    void createCategory_shouldCreateSubcategory_whenParentExists() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Category newSubCategory = new Category("Tafsir", "quran");
        when(categoryRepository.existsById("quran")).thenReturn(true);
        when(categoryRepository.save(any(Category.class))).thenReturn(newSubCategory);

        // Act
        ResponseEntity<Category> response = categoryController.createCategory(newSubCategory, adminUser);

        // Assert
        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals("quran", newSubCategory.getParentCategoryId());
        verify(categoryRepository).existsById("quran");
        verify(categoryRepository).save(newSubCategory);
    }

    @Test
    void createCategory_shouldReturnBadRequest_whenParentDoesNotExist() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Category newSubCategory = new Category("Tafsir", "nonexistent");
        when(categoryRepository.existsById("nonexistent")).thenReturn(false);

        // Act
        ResponseEntity<Category> response = categoryController.createCategory(newSubCategory, adminUser);

        // Assert
        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void updateCategory_shouldUpdateExistingCategory() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Category updates = new Category("Updated Name", null);
        updates.setDisplayOrder(5);
        when(categoryRepository.findById("quran")).thenReturn(Optional.of(parentCategory));
        when(categoryRepository.save(any(Category.class))).thenReturn(parentCategory);

        // Act
        ResponseEntity<Category> response = categoryController.updateCategory("quran", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.OK, response.getStatusCode());
        assertEquals("Updated Name", parentCategory.getName());
        assertEquals(5, parentCategory.getDisplayOrder());
        assertEquals("admin-uid", parentCategory.getUpdatedBy());
        verify(categoryRepository).save(parentCategory);
    }

    @Test
    void updateCategory_shouldReturn404_whenCategoryNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        Category updates = new Category("Updated Name", null);
        when(categoryRepository.findById("nonexistent")).thenReturn(Optional.empty());

        // Act
        ResponseEntity<Category> response = categoryController.updateCategory("nonexistent", updates, adminUser);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void deleteCategory_shouldDeleteCategory_whenNoSubcategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(categoryRepository.existsById("quran")).thenReturn(true);
        when(categoryRepository.findByParentId("quran")).thenReturn(Collections.emptyList());

        // Act
        ResponseEntity<Void> response = categoryController.deleteCategory("quran", adminUser);

        // Assert
        assertEquals(HttpStatus.NO_CONTENT, response.getStatusCode());
        verify(categoryRepository).deleteById("quran");
        verify(auditLogService).log(eq("category_deleted"), eq("category"), eq("quran"), eq(adminUser));
    }

    @Test
    void deleteCategory_shouldReturnConflict_whenHasSubcategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(categoryRepository.existsById("quran")).thenReturn(true);
        when(categoryRepository.findByParentId("quran")).thenReturn(Arrays.asList(subCategory));

        // Act
        ResponseEntity<Void> response = categoryController.deleteCategory("quran", adminUser);

        // Assert
        assertEquals(HttpStatus.CONFLICT, response.getStatusCode());
        verify(categoryRepository, never()).deleteById(any());
    }

    @Test
    void deleteCategory_shouldReturn404_whenCategoryNotFound() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        // Arrange
        when(categoryRepository.existsById("nonexistent")).thenReturn(false);

        // Act
        ResponseEntity<Void> response = categoryController.deleteCategory("nonexistent", adminUser);

        // Assert
        assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
        verify(categoryRepository, never()).deleteById(any());
    }

    // ---- Live channels (spec §3, §4.2, D8, R18) ----

    private static Category live(String name, String parentId) {
        Category c = new Category(name, parentId);
        c.setType(Category.TYPE_LIVE);
        return c;
    }

    @Test
    void createLiveTopLevelIsSavedWithItsType() throws Exception {
        when(categoryRepository.findMaxDisplayOrder()).thenReturn(16);
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        ResponseEntity<Category> response = categoryController.createCategory(live("Channels Live", null), adminUser);

        assertEquals(HttpStatus.CREATED, response.getStatusCode());
        assertEquals(Category.TYPE_LIVE, response.getBody().getType());
    }

    @Test
    void createLiveWithAParentIsRefusedWithShape() throws Exception {
        when(categoryRepository.existsById("parent-1")).thenReturn(true);

        LiveCategoryException e = assertThrows(LiveCategoryException.class,
                () -> categoryController.createCategory(live("Live kids", "parent-1"), adminUser));

        assertEquals(LiveCategoryException.SHAPE, e.getCode());
        assertEquals(HttpStatus.BAD_REQUEST, e.getStatus());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createSubcategoryUnderALiveParentIsRefusedWithShape() throws Exception {
        when(categoryRepository.existsById("live-1")).thenReturn(true);
        when(categoryRepository.findById("live-1")).thenReturn(Optional.of(live("Channels Live", null)));

        LiveCategoryException e = assertThrows(LiveCategoryException.class,
                () -> categoryController.createCategory(new Category("Kids", "live-1"), adminUser));

        assertEquals(LiveCategoryException.SHAPE, e.getCode());
        verify(categoryRepository, never()).save(any());
    }

    @Test
    void createWithAnUnknownTypeIs400() throws Exception {
        Category odd = new Category("Odd", null);
        odd.setType("PODCAST");

        ResponseEntity<Category> response = categoryController.createCategory(odd, adminUser);

        assertEquals(HttpStatus.BAD_REQUEST, response.getStatusCode());
        verify(categoryRepository, never()).save(any());
    }

    /** Review Focus 2: the old dashboard (and any PUT without "type") keeps the live type. */
    @Test
    void anUpdateWithoutTypeKeepsTheLiveType() throws Exception {
        Category existing = live("Channels Live", null);
        existing.setId("live-1");
        when(categoryRepository.findById("live-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.findByParentId("live-1")).thenReturn(List.of());
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        Category body = new Category("Live Channels", null);   // never calls setType
        ResponseEntity<Category> response = categoryController.updateCategory("live-1", body, adminUser);

        assertEquals(Category.TYPE_LIVE, response.getBody().getType());
        assertEquals("Live Channels", response.getBody().getName());
    }

    /** R18: the kill switch is an explicit "type": null, always allowed. */
    @Test
    void anUpdateWithANullTypeClearsIt() throws Exception {
        Category existing = live("Channels Live", null);
        existing.setId("live-1");
        when(categoryRepository.findById("live-1")).thenReturn(Optional.of(existing));
        when(categoryRepository.save(any(Category.class))).thenAnswer(i -> i.getArgument(0));

        Category body = new Category("Channels Live", null);
        body.setType(null);
        ResponseEntity<Category> response = categoryController.updateCategory("live-1", body, adminUser);

        assertNull(response.getBody().getType());
    }

    @Test
    void makingACategoryWithChildrenLiveIsRefusedWithShape() throws Exception {
        Category existing = new Category("Kids", null);
        existing.setId("kids");
        when(categoryRepository.findById("kids")).thenReturn(Optional.of(existing));
        when(categoryRepository.findByParentId("kids")).thenReturn(List.of(new Category("Cartoons", "kids")));

        LiveCategoryException e = assertThrows(LiveCategoryException.class,
                () -> categoryController.updateCategory("kids", live("Kids", null), adminUser));

        assertEquals(LiveCategoryException.SHAPE, e.getCode());
        verify(categoryRepository, never()).save(any());
    }

    /** Row 17: setting LIVE on an ordinary category runs the members rule before anything is saved. */
    @Test
    void settingLiveOnAnOrdinaryCategoryChecksItsMembers() throws Exception {
        Category existing = new Category("Live Kids", null);
        existing.setId("kids");
        when(categoryRepository.findById("kids")).thenReturn(Optional.of(existing));
        when(categoryRepository.findByParentId("kids")).thenReturn(List.of());
        doThrow(new LiveCategoryException(HttpStatus.BAD_REQUEST, LiveCategoryException.MEMBERS_INVALID, "x", Map.of()))
                .when(liveCategoryGuard).checkMembersForLive("kids");

        assertThrows(LiveCategoryException.class, () -> categoryController.updateCategory("kids", live("Live Kids", null), adminUser));
        verify(categoryRepository, never()).save(any());
    }
}
