package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.util.TestDataBuilder;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;

/**
 * BACKEND-TEST-01: Category Integration Tests
 *
 * Tests CategoryRepository with real Firestore emulator.
 */
public class CategoryIntegrationTest extends BaseIntegrationTest {

    @Autowired
    private CategoryRepository categoryRepository;

    @Test
    public void testSaveAndFindCategory() throws Exception {
        // Given
        Category category = TestDataBuilder.createCategory("Quran");

        // When
        Category saved = categoryRepository.save(category);

        // Then
        assertNotNull(saved.getId());
        assertEquals("Quran", saved.getName());

        // Verify we can find it
        Optional<Category> found = categoryRepository.findById(saved.getId());
        assertTrue(found.isPresent());
        assertEquals("Quran", found.get().getName());
    }

    @Test
    public void testFindAllCategories() throws Exception {
        // Given
        categoryRepository.save(TestDataBuilder.createCategory("Quran"));
        categoryRepository.save(TestDataBuilder.createCategory("Tafsir"));
        categoryRepository.save(TestDataBuilder.createCategory("Hadith"));

        // When
        List<Category> categories = categoryRepository.findAll();

        // Then
        assertEquals(3, categories.size());
    }

    @Test
    public void testFindTopLevelCategories() throws Exception {
        // Given
        Category parent = TestDataBuilder.createCategory("Islamic Studies");
        parent = categoryRepository.save(parent);

        categoryRepository.save(TestDataBuilder.createCategory("Quran"));
        categoryRepository.save(TestDataBuilder.createSubcategory("Tajweed", parent.getId()));

        // When
        List<Category> topLevel = categoryRepository.findTopLevel();

        // Then
        assertEquals(2, topLevel.size());
        assertTrue(topLevel.stream().anyMatch(c -> "Quran".equals(c.getName())));
        assertTrue(topLevel.stream().anyMatch(c -> "Islamic Studies".equals(c.getName())));
    }

    @Test
    public void testFindByParentId() throws Exception {
        // Given
        Category parent = TestDataBuilder.createCategory("Islamic Studies");
        parent = categoryRepository.save(parent);
        final String parentId = parent.getId();

        categoryRepository.save(TestDataBuilder.createSubcategory("Quran", parentId));
        categoryRepository.save(TestDataBuilder.createSubcategory("Hadith", parentId));
        categoryRepository.save(TestDataBuilder.createCategory("Standalone Category"));

        // When
        List<Category> subcategories = categoryRepository.findByParentId(parentId);

        // Then
        assertEquals(2, subcategories.size());
        assertTrue(subcategories.stream().allMatch(c -> parentId.equals(c.getParentCategoryId())));
    }

    @Test
    public void testUpdateCategory() throws Exception {
        // Given
        Category category = TestDataBuilder.createCategory("Quran");
        category = categoryRepository.save(category);

        // When
        category.setName("Quran Recitation");
        category.setIcon("new-icon");
        Category updated = categoryRepository.save(category);

        // Then
        assertEquals("Quran Recitation", updated.getName());
        assertEquals("new-icon", updated.getIcon());

        // Verify the update persisted
        Optional<Category> found = categoryRepository.findById(category.getId());
        assertTrue(found.isPresent());
        assertEquals("Quran Recitation", found.get().getName());
    }

    @Test
    public void testDeleteCategory() throws Exception {
        // Given
        Category category = TestDataBuilder.createCategory("Temporary");
        category = categoryRepository.save(category);
        String categoryId = category.getId();

        // When
        categoryRepository.deleteById(categoryId);

        // Then
        assertFalse(documentExists("categories", categoryId));
        Optional<Category> found = categoryRepository.findById(categoryId);
        assertFalse(found.isPresent());
    }

    @Test
    public void testExistsById() throws Exception {
        // Given
        Category category = TestDataBuilder.createCategory("Test");
        category = categoryRepository.save(category);

        // When/Then
        assertTrue(categoryRepository.existsById(category.getId()));
        assertFalse(categoryRepository.existsById("non-existent-id"));
    }

    @Test
    public void testCategoryHierarchy() throws Exception {
        // Given - Create 3-level hierarchy
        Category root = TestDataBuilder.createCategory("Islamic Studies");
        root = categoryRepository.save(root);

        Category level1 = TestDataBuilder.createSubcategory("Quran", root.getId());
        level1 = categoryRepository.save(level1);

        Category level2 = TestDataBuilder.createSubcategory("Tajweed", level1.getId());
        level2 = categoryRepository.save(level2);

        // When
        List<Category> topLevel = categoryRepository.findTopLevel();
        List<Category> level1Children = categoryRepository.findByParentId(root.getId());
        List<Category> level2Children = categoryRepository.findByParentId(level1.getId());

        // Then
        assertEquals(1, topLevel.size());
        assertEquals("Islamic Studies", topLevel.get(0).getName());

        assertEquals(1, level1Children.size());
        assertEquals("Quran", level1Children.get(0).getName());

        assertEquals(1, level2Children.size());
        assertEquals("Tajweed", level2Children.get(0).getName());
    }
}
