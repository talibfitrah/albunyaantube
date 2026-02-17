package com.albunyaan.tube.dto;

import com.fasterxml.jackson.annotation.JsonInclude;

import java.util.List;
import java.util.Map;

/**
 * DTO for a category section in the home feed.
 * Each section contains a category header and a list of content items.
 */
@JsonInclude(JsonInclude.Include.NON_NULL)
public class HomeCategoryDto {
    private String id;
    private String name;
    private String slug;
    private Map<String, String> localizedNames;
    private Integer displayOrder;
    private String icon;
    private List<ContentItemDto> items;
    private int totalContentCount;

    public HomeCategoryDto() {
    }

    public HomeCategoryDto(String id, String name, String slug, Map<String, String> localizedNames,
                           Integer displayOrder, String icon, List<ContentItemDto> items, int totalContentCount) {
        this.id = id;
        this.name = name;
        this.slug = slug;
        this.localizedNames = localizedNames;
        this.displayOrder = displayOrder;
        this.icon = icon;
        this.items = items;
        this.totalContentCount = totalContentCount;
    }

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

    public Map<String, String> getLocalizedNames() {
        return localizedNames;
    }

    public void setLocalizedNames(Map<String, String> localizedNames) {
        this.localizedNames = localizedNames;
    }

    public Integer getDisplayOrder() {
        return displayOrder;
    }

    public void setDisplayOrder(Integer displayOrder) {
        this.displayOrder = displayOrder;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
    }

    public List<ContentItemDto> getItems() {
        return items;
    }

    public void setItems(List<ContentItemDto> items) {
        this.items = items;
    }

    public int getTotalContentCount() {
        return totalContentCount;
    }

    public void setTotalContentCount(int totalContentCount) {
        this.totalContentCount = totalContentCount;
    }
}
