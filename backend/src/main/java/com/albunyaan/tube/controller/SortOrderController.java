package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CategorySortDto;
import com.albunyaan.tube.dto.ContentSortDto;
import com.albunyaan.tube.service.SortOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Admin endpoints for managing sort order of categories and content within categories.
 *
 * Base path: /api/admin/sort
 */
@RestController
@RequestMapping("/api/admin/sort")
@PreAuthorize("hasRole('ADMIN')")
@Validated
public class SortOrderController {

    private static final Logger log = LoggerFactory.getLogger(SortOrderController.class);

    private final SortOrderService sortOrderService;

    public SortOrderController(SortOrderService sortOrderService) {
        this.sortOrderService = sortOrderService;
    }

    /**
     * GET /api/admin/sort/categories
     *
     * Get all categories in sort order with content counts.
     */
    @GetMapping("/categories")
    public ResponseEntity<List<CategorySortDto>> getCategorySortOrder()
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sortOrderService.getCategorySortOrder());
    }

    /**
     * PUT /api/admin/sort/categories/reorder
     *
     * Insert-and-shift a category to a new position.
     * All other categories shift down to accommodate.
     */
    @PutMapping("/categories/reorder")
    public ResponseEntity<List<CategorySortDto>> reorderCategory(
            @Valid @RequestBody ReorderCategoryRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            List<CategorySortDto> result = sortOrderService.reorderCategory(
                    request.categoryId, request.newPosition);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Category reorder failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    /**
     * GET /api/admin/sort/categories/{categoryId}/content
     *
     * Get content items within a category in sort order.
     * Initializes sort order from existing content if none has been set.
     */
    @GetMapping("/categories/{categoryId}/content")
    public ResponseEntity<List<ContentSortDto>> getContentSortOrder(
            @PathVariable String categoryId)
            throws ExecutionException, InterruptedException, TimeoutException {
        return ResponseEntity.ok(sortOrderService.getContentSortOrder(categoryId));
    }

    /**
     * PUT /api/admin/sort/categories/{categoryId}/content/reorder
     *
     * Insert-and-shift a content item to a new position within a category.
     * All other items in the category shift down to accommodate.
     */
    @PutMapping("/categories/{categoryId}/content/reorder")
    public ResponseEntity<List<ContentSortDto>> reorderContent(
            @PathVariable String categoryId,
            @Valid @RequestBody ReorderContentRequest request)
            throws ExecutionException, InterruptedException, TimeoutException {
        try {
            List<ContentSortDto> result = sortOrderService.reorderContentInCategory(
                    categoryId, request.contentId, request.contentType, request.newPosition);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Content reorder failed in category {}: {}", categoryId, e.getMessage());
            return ResponseEntity.badRequest().build();
        }
    }

    // ======================== REQUEST DTOs ========================

    public static class ReorderCategoryRequest {
        @NotNull(message = "Category ID cannot be null")
        @NotBlank(message = "Category ID cannot be blank")
        public String categoryId;

        @NotNull(message = "New position cannot be null")
        @Min(value = 0, message = "Position must be >= 0")
        public Integer newPosition;
    }

    public static class ReorderContentRequest {
        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String contentId;

        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        @Pattern(regexp = "^(channel|playlist|video)$", message = "Content type must be one of: channel, playlist, video")
        public String contentType;

        @NotNull(message = "New position cannot be null")
        @Min(value = 0, message = "Position must be >= 0")
        public Integer newPosition;
    }
}
