package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.CategorySortDto;
import com.albunyaan.tube.dto.ContentSortDto;
import com.albunyaan.tube.service.SortOrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.*;

import java.util.ArrayList;
import java.util.List;

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
    public ResponseEntity<List<CategorySortDto>> getCategorySortOrder() {
        try {
            return ResponseEntity.ok(sortOrderService.getCategorySortOrder());
        } catch (Exception e) {
            log.error("Failed to get category sort order", e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * PUT /api/admin/sort/categories/reorder
     *
     * Insert-and-shift a category to a new position.
     * All other categories shift down to accommodate.
     */
    @PutMapping("/categories/reorder")
    public ResponseEntity<List<CategorySortDto>> reorderCategory(
            @Valid @RequestBody ReorderCategoryRequest request) {
        try {
            List<CategorySortDto> result = sortOrderService.reorderCategory(
                    request.categoryId, request.newPosition);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Category reorder failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to reorder category {}", request.categoryId, e);
            return ResponseEntity.internalServerError().build();
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
            @PathVariable String categoryId) {
        try {
            return ResponseEntity.ok(sortOrderService.getContentSortOrder(categoryId));
        } catch (IllegalArgumentException e) {
            log.warn("Invalid request for content sort order in category {}: {}", categoryId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to get content sort order for category {}", categoryId, e);
            return ResponseEntity.internalServerError().build();
        }
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
            @Valid @RequestBody ReorderContentRequest request) {
        try {
            List<ContentSortDto> result = sortOrderService.reorderContentInCategory(
                    categoryId, request.contentId, request.contentType, request.newPosition);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Content reorder failed in category {}: {}", categoryId, e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to reorder content in category {}", categoryId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * POST /api/admin/sort/categories/{categoryId}/content/add
     *
     * Add content items directly to a category's sort order.
     * Also updates each content item's categoryIds to include this category.
     */
    @PostMapping("/categories/{categoryId}/content/add")
    public ResponseEntity<List<ContentSortDto>> addContentToCategory(
            @PathVariable String categoryId,
            @Valid @RequestBody AddContentRequest request) {
        try {
            // Map controller DTOs to service-level type (String[] = {contentId, contentType})
            List<String[]> serviceItems = new ArrayList<>();
            for (AddContentItem item : request.items) {
                serviceItems.add(new String[]{item.contentId, item.contentType});
            }
            List<ContentSortDto> result = sortOrderService.addMultipleContentToCategory(
                    categoryId, serviceItems);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Add content to category failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to add content to category {}", categoryId, e);
            return ResponseEntity.internalServerError().build();
        }
    }

    /**
     * DELETE /api/admin/sort/categories/{categoryId}/content/{contentType}/{contentId}
     *
     * Remove a content item from a category's sort order.
     * Also removes the category from the content item's categoryIds.
     */
    @DeleteMapping("/categories/{categoryId}/content/{contentType}/{contentId}")
    public ResponseEntity<List<ContentSortDto>> removeContentFromCategory(
            @PathVariable String categoryId,
            @PathVariable @Pattern(regexp = "^(channel|playlist|video)$") String contentType,
            @PathVariable @NotBlank String contentId) {
        try {
            List<ContentSortDto> result = sortOrderService.removeContentFromCategoryAndUpdate(
                    categoryId, contentId, contentType);
            return ResponseEntity.ok(result);
        } catch (IllegalArgumentException e) {
            log.warn("Remove content from category failed: {}", e.getMessage());
            return ResponseEntity.badRequest().build();
        } catch (Exception e) {
            log.error("Failed to remove content {} from category {}", contentId, categoryId, e);
            return ResponseEntity.internalServerError().build();
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

    public static class AddContentRequest {
        @NotNull(message = "Items cannot be null")
        @NotEmpty(message = "Items cannot be empty")
        @Valid
        public List<AddContentItem> items;
    }

    public static class AddContentItem {
        @NotNull(message = "Content ID cannot be null")
        @NotBlank(message = "Content ID cannot be blank")
        public String contentId;

        @NotNull(message = "Content type cannot be null")
        @NotBlank(message = "Content type cannot be blank")
        @Pattern(regexp = "^(channel|playlist|video)$", message = "Content type must be one of: channel, playlist, video")
        public String contentType;
    }
}
