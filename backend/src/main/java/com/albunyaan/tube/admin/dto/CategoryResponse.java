package com.albunyaan.tube.admin.dto;

import com.albunyaan.tube.category.Category;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.UUID;

public record CategoryResponse(
    UUID id,
    String slug,
    Map<String, String> name,
    Map<String, String> description,
    OffsetDateTime createdAt,
    OffsetDateTime updatedAt
) {
    public static CategoryResponse fromCategory(Category category) {
        return new CategoryResponse(
            category.getId(),
            category.getSlug(),
            category.getName(),
            category.getDescription(),
            category.getCreatedAt(),
            category.getUpdatedAt()
        );
    }
}
