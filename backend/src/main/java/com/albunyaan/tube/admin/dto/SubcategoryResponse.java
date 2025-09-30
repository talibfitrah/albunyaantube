package com.albunyaan.tube.admin.dto;

import com.albunyaan.tube.category.Subcategory;
import java.util.Map;
import java.util.UUID;

public record SubcategoryResponse(UUID id, String slug, Map<String, String> name) {
    public static SubcategoryResponse fromModel(Subcategory subcategory) {
        return new SubcategoryResponse(subcategory.id(), subcategory.slug(), subcategory.name());
    }
}
