package com.albunyaan.tube.category;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Simple value object representing a localized subcategory that belongs to a parent category.
 * Stored inside the category table as a JSONB column.
 */
public record Subcategory(UUID id, String slug, Map<String, String> name) {

    @JsonCreator
    public Subcategory(
        @JsonProperty("id") UUID id,
        @JsonProperty("slug") String slug,
        @JsonProperty("name") Map<String, String> name
    ) {
        this.id = Objects.requireNonNull(id, "id");
        this.slug = Objects.requireNonNull(slug, "slug");
        this.name = name == null ? Map.of() : Map.copyOf(name);
    }
}
