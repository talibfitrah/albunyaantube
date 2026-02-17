package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.Map;

/**
 * DTO for Category in public API.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CategoryDto {
    private String id;
    private String name;
    private String slug;
    private String parentId;
    private Integer displayOrder;
    private Map<String, String> localizedNames;
    private String icon;

    public CategoryDto() {
    }

    public CategoryDto(String id, String name, String slug, String parentId) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
    }

    public CategoryDto(String id, String name, String slug, String parentId,
                       Integer displayOrder, Map<String, String> localizedNames, String icon) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.parentId = parentId;
        this.displayOrder = displayOrder;
        this.localizedNames = localizedNames;
        this.icon = icon;
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

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public Map<String, String> getLocalizedNames() {
        return localizedNames;
    }

    public void setLocalizedNames(Map<String, String> localizedNames) {
        this.localizedNames = localizedNames;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }
}
