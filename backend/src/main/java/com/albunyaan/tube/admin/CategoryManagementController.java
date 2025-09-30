package com.albunyaan.tube.admin;

import com.albunyaan.tube.admin.dto.CategoryPageResponse;
import com.albunyaan.tube.admin.dto.CategoryResponse;
import com.albunyaan.tube.admin.dto.CreateCategoryRequest;
import com.albunyaan.tube.admin.dto.UpdateCategoryRequest;
import com.albunyaan.tube.category.CategoryService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import java.net.URI;
import java.util.UUID;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping(path = "/api/v1/admins/categories", produces = MediaType.APPLICATION_JSON_VALUE)
@Validated
public class CategoryManagementController {

    private final CategoryService categoryService;

    public CategoryManagementController(CategoryService categoryService) {
        this.categoryService = categoryService;
    }

    @GetMapping
    public ResponseEntity<CategoryPageResponse> listCategories(
        @RequestParam(name = "cursor", required = false) String cursor,
        @RequestParam(name = "limit", defaultValue = "20") @Min(1) @Max(100) int limit
    ) {
        var page = categoryService.listCategories(cursor, limit);
        var response = new CategoryPageResponse(
            page.categories().stream().map(CategoryResponse::fromCategory).toList(),
            new CategoryPageResponse.PageInfo(page.cursor(), page.nextCursor(), page.hasNext(), page.limit())
        );
        return ResponseEntity.ok(response);
    }

    @GetMapping(path = "/{id}")
    public ResponseEntity<CategoryResponse> getCategory(@PathVariable("id") UUID id) {
        var category = categoryService.getCategory(id);
        return ResponseEntity.ok(CategoryResponse.fromCategory(category));
    }

    @PostMapping(consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CategoryResponse> createCategory(@Valid @RequestBody CreateCategoryRequest request) {
        var category = categoryService.createCategory(
            request.slug(),
            request.name(),
            request.description(),
            request.subcategories()
        );
        var response = CategoryResponse.fromCategory(category);
        return ResponseEntity
            .created(URI.create("/api/v1/admins/categories/" + response.id()))
            .body(response);
    }

    @PutMapping(path = "/{id}", consumes = MediaType.APPLICATION_JSON_VALUE)
    public ResponseEntity<CategoryResponse> updateCategory(
        @PathVariable("id") UUID id,
        @Valid @RequestBody UpdateCategoryRequest request
    ) {
        var category = categoryService.updateCategory(
            id,
            request.slug(),
            request.name(),
            request.description(),
            request.subcategories()
        );
        return ResponseEntity.ok(CategoryResponse.fromCategory(category));
    }

    @DeleteMapping(path = "/{id}")
    public ResponseEntity<Void> deleteCategory(@PathVariable("id") UUID id) {
        categoryService.deleteCategory(id);
        return ResponseEntity.noContent().build();
    }
}
