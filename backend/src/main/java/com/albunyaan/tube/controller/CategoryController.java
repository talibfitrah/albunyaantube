package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.PublicContentCacheService;
import com.albunyaan.tube.service.SortOrderService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cache.annotation.CacheEvict;
import org.springframework.cache.annotation.Cacheable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

/**
 * FIREBASE-MIGRATE-03: Category Management Controller
 *
 * Endpoints for category CRUD operations.
 * Only admins can create/update/delete categories.
 * Moderators and public can read categories.
 */
@RestController
@RequestMapping("/api/admin/categories")
public class CategoryController {

    private static final Logger log = LoggerFactory.getLogger(CategoryController.class);

    private final CategoryRepository categoryRepository;
    private final AuditLogService auditLogService;
    private final PublicContentCacheService publicContentCacheService;
    private final SortOrderService sortOrderService;

    public CategoryController(CategoryRepository categoryRepository, AuditLogService auditLogService,
                              PublicContentCacheService publicContentCacheService,
                              SortOrderService sortOrderService) {
        this.categoryRepository = categoryRepository;
        this.auditLogService = auditLogService;
        this.publicContentCacheService = publicContentCacheService;
        this.sortOrderService = sortOrderService;
    }

    /**
     * Get all categories (hierarchical)
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'all'")
    public ResponseEntity<List<Category>> getAllCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Category> categories = categoryRepository.findAll();
        return ResponseEntity.ok(categories);
    }

    /**
     * Get top-level categories
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping("/top-level")
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'top-level'")
    public ResponseEntity<List<Category>> getTopLevelCategories() throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Category> categories = categoryRepository.findTopLevel();
        return ResponseEntity.ok(categories);
    }

    /**
     * Get subcategories of a parent
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping("/{parentId}/subcategories")
    @Cacheable(value = CacheConfig.CACHE_CATEGORIES, key = "#parentId + '-subcategories'")
    public ResponseEntity<List<Category>> getSubcategories(@PathVariable String parentId)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        List<Category> subcategories = categoryRepository.findByParentId(parentId);
        return ResponseEntity.ok(subcategories);
    }

    /**
     * Get category by ID
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping("/{id}")
    @Cacheable(value = CacheConfig.CACHE_CATEGORIES, key = "#id")
    public ResponseEntity<Category> getCategoryById(@PathVariable String id)
            throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        return categoryRepository.findById(id)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Create new category (admin only)
     * BACKEND-PERF-01: Evict all category caches on create
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = {CacheConfig.CACHE_CATEGORIES, CacheConfig.CACHE_CATEGORY_TREE}, allEntries = true)
    public ResponseEntity<Category> createCategory(
            @RequestBody Category category,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        category.setCreatedBy(user.getUid());
        category.setUpdatedBy(user.getUid());

        // Validate parent exists if specified
        if (category.getParentCategoryId() != null) {
            boolean parentExists = categoryRepository.existsById(category.getParentCategoryId());
            if (!parentExists) {
                return ResponseEntity.badRequest().build();
            }
        }

        Category saved = categoryRepository.save(category);
        try {
            publicContentCacheService.evictPublicContentCaches();
        } catch (Exception ce) {
            log.warn("Cache eviction failed after creating category {}: {}", saved.getId(), ce.getMessage());
        }
        auditLogService.log("category_created", "category", saved.getId(), user);
        return ResponseEntity.status(HttpStatus.CREATED).body(saved);
    }

    /**
     * Update category (admin only)
     * BACKEND-PERF-01: Evict all category caches on update
     */
    @PutMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = {CacheConfig.CACHE_CATEGORIES, CacheConfig.CACHE_CATEGORY_TREE}, allEntries = true)
    public ResponseEntity<Category> updateCategory(
            @PathVariable String id,
            @RequestBody Category category,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        Category existing = categoryRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        // Validate parent category if specified
        if (category.getParentCategoryId() != null) {
            // Check if parent category exists
            if (!categoryRepository.existsById(category.getParentCategoryId())) {
                return ResponseEntity.badRequest().build();
            }
            // Prevent self-reference
            if (category.getParentCategoryId().equals(id)) {
                return ResponseEntity.badRequest().build();
            }
            // Prevent circular reference (moving category under its own descendant)
            if (isDescendant(id, category.getParentCategoryId())) {
                return ResponseEntity.badRequest().build();
            }
        }

        existing.setName(category.getName());
        existing.setParentCategoryId(category.getParentCategoryId());
        existing.setIcon(category.getIcon());
        existing.setDisplayOrder(category.getDisplayOrder());
        existing.setLocalizedNames(category.getLocalizedNames());
        existing.setUpdatedBy(user.getUid());

        Category updated = categoryRepository.save(existing);
        try {
            publicContentCacheService.evictPublicContentCaches();
        } catch (Exception ce) {
            log.warn("Cache eviction failed after updating category {}: {}", id, ce.getMessage());
        }
        auditLogService.log("category_updated", "category", id, user);
        return ResponseEntity.ok(updated);
    }

    /**
     * Delete category (admin only)
     * BACKEND-PERF-01: Evict all category caches on delete
     */
    @DeleteMapping("/{id}")
    @PreAuthorize("hasRole('ADMIN')")
    @CacheEvict(value = {CacheConfig.CACHE_CATEGORIES, CacheConfig.CACHE_CATEGORY_TREE}, allEntries = true)
    public ResponseEntity<Void> deleteCategory(
            @PathVariable String id,
            @AuthenticationPrincipal FirebaseUserDetails user
    ) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        if (!categoryRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Check if category has subcategories
        List<Category> subcategories = categoryRepository.findByParentId(id);
        if (!subcategories.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        // Clean up sort orders before category deletion so dependent data is removed first.
        // If this fails, proceed with category deletion — orphaned order entries are harmless
        // (they reference a non-existent category and will be ignored).
        try {
            sortOrderService.deleteAllOrdersForCategory(id);
        } catch (Exception e) {
            log.warn("Failed to delete sort order entries for category {}: {}", id, e.getMessage());
        }
        categoryRepository.deleteById(id);
        try {
            publicContentCacheService.evictPublicContentCaches();
        } catch (Exception ce) {
            log.warn("Cache eviction failed after deleting category {}: {}", id, ce.getMessage());
        }
        auditLogService.log("category_deleted", "category", id, user);
        return ResponseEntity.noContent().build();
    }

    /**
     * Check if categoryId is a descendant of potentialAncestorId
     * Used to prevent circular references in category hierarchy
     */
    private boolean isDescendant(String categoryId, String potentialAncestorId) throws ExecutionException, InterruptedException, java.util.concurrent.TimeoutException {
        String currentId = potentialAncestorId;

        // Walk up the parent chain from potentialAncestorId
        while (currentId != null) {
            if (currentId.equals(categoryId)) {
                // Found categoryId in the ancestor chain - this would create a cycle
                return true;
            }

            // Get parent of current category
            Category category = categoryRepository.findById(currentId).orElse(null);
            if (category == null) {
                break;
            }

            currentId = category.getParentCategoryId();
        }

        return false;
    }
}

