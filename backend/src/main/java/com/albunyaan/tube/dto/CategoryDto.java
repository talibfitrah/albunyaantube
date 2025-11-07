package com.albunyaan.tube.dto;

/**
 * DTO for Category in public API.
 */
public class CategoryDto {
    private String id;
    private String name;
    private String slug;
    private String parentId;

    public CategoryDto() {
    }

    public CategoryDto(String id, String name, String slug, String parentId) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
    }

    // Getters and setters
    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getParentId() {
        return parentId;
    }

    public void setParentId(String parentId) {
        this.parentId = parentId;
    }
}

