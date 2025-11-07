package com.albunyaan.tube.controller;

import com.albunyaan.tube.config.CacheConfig;
import com.albunyaan.tube.model.Category;
import com.albunyaan.tube.repository.CategoryRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
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

    private final CategoryRepository categoryRepository;
    private final AuditLogService auditLogService;

    public CategoryController(CategoryRepository categoryRepository, AuditLogService auditLogService) {
        this.categoryRepository = categoryRepository;
        this.auditLogService = auditLogService;
    }

    /**
     * Get all categories (hierarchical)
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'all'")
    public ResponseEntity<List<Category>> getAllCategories() throws ExecutionException, InterruptedException {
        List<Category> categories = categoryRepository.findAll();
        return ResponseEntity.ok(categories);
    }

    /**
     * Get top-level categories
     * BACKEND-PERF-01: Cached for 1 hour
     */
    @GetMapping("/top-level")
    @Cacheable(value = CacheConfig.CACHE_CATEGORY_TREE, key = "'top-level'")
    public ResponseEntity<List<Category>> getTopLevelCategories() throws ExecutionException, InterruptedException {
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
            throws ExecutionException, InterruptedException {
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
            throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
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
    ) throws ExecutionException, InterruptedException {
        Category existing = categoryRepository.findById(id).orElse(null);
        if (existing == null) {
            return ResponseEntity.notFound().build();
        }

        existing.setName(category.getName());
        existing.setParentCategoryId(category.getParentCategoryId());
        existing.setIcon(category.getIcon());
        existing.setDisplayOrder(category.getDisplayOrder());
        existing.setLocalizedNames(category.getLocalizedNames());
        existing.setUpdatedBy(user.getUid());

        Category updated = categoryRepository.save(existing);
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
    ) throws ExecutionException, InterruptedException {
        if (!categoryRepository.existsById(id)) {
            return ResponseEntity.notFound().build();
        }

        // Check if category has subcategories
        List<Category> subcategories = categoryRepository.findByParentId(id);
        if (!subcategories.isEmpty()) {
            return ResponseEntity.status(HttpStatus.CONFLICT).build();
        }

        categoryRepository.deleteById(id);
        auditLogService.log("category_deleted", "category", id, user);
        return ResponseEntity.noContent().build();
    }
}

