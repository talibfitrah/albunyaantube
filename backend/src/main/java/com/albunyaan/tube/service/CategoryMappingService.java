package com.albunyaan.tube.service;

import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryRepository;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.stereotype.Service;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Service for mapping category names to IDs for import/export operations.
 * Supports:
 * - Case-insensitive name matching
 * - Multi-language matching (en, ar, nl)
 * - Subcategory matching via hierarchy
 * - Comma-separated category lists
 *
 * Performance optimization: Categories are preloaded into an in-memory map at startup
 * to avoid repeated findAll() calls on cache misses.
 */
@Service
public class CategoryMappingService {

    private static final Logger logger = LoggerFactory.getLogger(CategoryMappingService.class);

    private final CategoryRepository categoryRepository;

    /**
     * In-memory cache of all categories, keyed by category ID.
     * Preloaded at startup to avoid repeated database queries.
     */
    private final Map<String, Category> categoriesById = new ConcurrentHashMap<>();

    public CategoryMappingService(CategoryRepository categoryRepository) {
        this.categoryRepository = categoryRepository;
    }

    /**
     * Preload all categories into memory at startup.
     * Called automatically by Spring after bean construction.
     */
    @PostConstruct
    public void preloadCategories() {
        try {
            List<Category> allCategories = categoryRepository.findAll();
            categoriesById.clear();
            for (Category category : allCategories) {
                categoriesById.put(category.getId(), category);
            }
            logger.info("Preloaded {} categories into memory", categoriesById.size());
        } catch (Exception e) {
            logger.error("Failed to preload categories: {}", e.getMessage(), e);
            // Don't throw exception - allow service to start, will retry on first access
        }
    }

    /**
     * Refresh the in-memory category cache.
     * Call this after category modifications (create/update/delete).
     */
    public void refreshCategoryCache() {
        preloadCategories();
    }

    /**
     * Get all categories from in-memory cache.
     * If cache is empty, attempts to reload from database.
     */
    private Collection<Category> getAllCategories() {
        if (categoriesById.isEmpty()) {
            logger.warn("Category cache is empty, reloading from database");
            preloadCategories();
        }
        return categoriesById.values();
    }

    /**
     * Map a single category name to its ID.
     * Performs case-insensitive search across name and localizedNames (en, ar, nl).
     * Results are cached (including negative results) to prevent repeated lookups.
     *
     * @param categoryName Category name to search for
     * @return Category ID if found, null if not found
     */
    @Cacheable(value = "categoryNameMapping", key = "#categoryName")
    public String mapCategoryNameToId(String categoryName) {
        if (categoryName == null || categoryName.trim().isEmpty()) {
            return null;
        }

        String normalizedName = categoryName.trim();

        // Use in-memory cache instead of repository.findAll()
        Collection<Category> allCategories = getAllCategories();

        // Try exact match first (case-insensitive)
        Optional<Category> exactMatch = allCategories.stream()
            .filter(cat -> matchesCategoryName(cat, normalizedName))
            .findFirst();

        if (exactMatch.isPresent()) {
            logger.debug("Found category ID '{}' for name '{}'", exactMatch.get().getId(), categoryName);
            return exactMatch.get().getId();
        }

        logger.warn("No category found for name '{}'", categoryName);
        return null;
    }

    /**
     * Map comma-separated category names to list of IDs.
     * Skips unknown categories and logs warnings.
     *
     * @param commaSeparated Comma-separated category names (e.g., "Cat1,Cat2,SubCat")
     * @return List of category IDs (may be empty if no matches found)
     */
    public List<String> mapCategoryNamesToIds(String commaSeparated) {
        if (commaSeparated == null || commaSeparated.trim().isEmpty()) {
            return Collections.emptyList();
        }

        // Split by comma and trim each name
        String[] categoryNames = commaSeparated.split(",");
        List<String> categoryIds = new ArrayList<>();

        for (String categoryName : categoryNames) {
            String trimmedName = categoryName.trim();
            if (!trimmedName.isEmpty()) {
                String categoryId = mapCategoryNameToId(trimmedName);
                if (categoryId != null) {
                    categoryIds.add(categoryId);
                } else {
                    logger.warn("Skipping unknown category name '{}' during import", trimmedName);
                }
            }
        }

        return categoryIds;
    }

    /**
     * Get primary category name from a list of category IDs.
     * Selection logic:
     * 1. Most specific category (deepest in hierarchy)
     * 2. If same level, return first category
     *
     * @param categoryIds List of category IDs
     * @return Primary category name, or empty string if no categories
     */
    public String getPrimaryCategoryName(List<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return "";
        }

        // Fetch all categories from in-memory cache
        List<Category> categories = new ArrayList<>();
        for (String categoryId : categoryIds) {
            Category category = categoriesById.get(categoryId);
            if (category != null) {
                categories.add(category);
            }
        }

        if (categories.isEmpty()) {
            return "";
        }

        // Find most specific category (deepest in hierarchy)
        Category primaryCategory = categories.stream()
            .max(Comparator.comparingInt(cat -> getCategoryDepth(cat)))
            .orElse(categories.get(0));

        return primaryCategory.getName();
    }

    /**
     * Get comma-separated category names from list of category IDs.
     *
     * @param categoryIds List of category IDs
     * @return Comma-separated category names (e.g., "Cat1,Cat2,SubCat")
     */
    public String getCategoryNamesCommaSeparated(List<String> categoryIds) {
        if (categoryIds == null || categoryIds.isEmpty()) {
            return "";
        }

        List<String> categoryNames = new ArrayList<>();

        for (String categoryId : categoryIds) {
            Category category = categoriesById.get(categoryId);
            if (category != null) {
                categoryNames.add(category.getName());
            }
        }

        return String.join(",", categoryNames);
    }

    /**
     * Calculate category depth in hierarchy (0 = top-level, 1 = first subcategory, etc.)
     */
    private int getCategoryDepth(Category category) {
        int depth = 0;
        String parentId = category.getParentCategoryId();

        while (parentId != null && depth < 10) { // Max depth 10 to prevent infinite loops
            Category parent = categoriesById.get(parentId);
            if (parent != null) {
                parentId = parent.getParentCategoryId();
                depth++;
            } else {
                break;
            }
        }

        return depth;
    }

    /**
     * Check if category matches the given name (case-insensitive).
     * Checks against:
     * - category.name
     * - category.slug
     * - category.localizedNames (en, ar, nl)
     */
    private boolean matchesCategoryName(Category category, String searchName) {
        String lowerSearchName = searchName.toLowerCase();

        // Check name field
        if (category.getName() != null &&
            category.getName().toLowerCase().equals(lowerSearchName)) {
            return true;
        }

        // Check slug field
        if (category.getSlug() != null &&
            category.getSlug().toLowerCase().equals(lowerSearchName)) {
            return true;
        }

        // Check localized names (en, ar, nl)
        if (category.getLocalizedNames() != null) {
            for (String localizedName : category.getLocalizedNames().values()) {
                if (localizedName != null &&
                    localizedName.toLowerCase().equals(lowerSearchName)) {
                    return true;
                }
            }
        }

        return false;
    }

    /**
     * Validate that all category IDs exist in Firestore.
     *
     * @param categoryIds List of category IDs to validate
     * @return Map of categoryId -> exists (true/false)
     */
    public Map<String, Boolean> validateCategoryIds(List<String> categoryIds) {
        Map<String, Boolean> validationResults = new HashMap<>();

        if (categoryIds == null || categoryIds.isEmpty()) {
            return validationResults;
        }

        for (String categoryId : categoryIds) {
            boolean exists = categoriesById.containsKey(categoryId);
            validationResults.put(categoryId, exists);
        }

        return validationResults;
    }
}

