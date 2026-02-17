package com.albunyaan.tube.dto;

import java.util.Map;

/**
 * DTO for the admin Content Sorting page — represents a category with its sort position
 * and count of approved content items.
 */
public class CategorySortDto {
    private String id;
    private String name;
    private String icon;
    private Map<String, String> localizedNames;
    private Integer displayOrder;
    private int contentCount;

    public CategorySortDto() {}

    public CategorySortDto(String id, String name, String icon, Map<String, String> localizedNames,
                           Integer displayOrder, int contentCount) {
        this.id = id;
        this.name = name;
        this.icon = icon;
        this.localizedNames = localizedNames;
        this.displayOrder = displayOrder;
        this.contentCount = contentCount;
    }

    public String getId() { return id; }
    public void setId(String id) { this.id = id; }

    public String getName() { return name; }
    public void setName(String name) { this.name = name; }

    public String getIcon() { return icon; }
    public void setIcon(String icon) { this.icon = icon; }

    public Map<String, String> getLocalizedNames() { return localizedNames; }
    public void setLocalizedNames(Map<String, String> localizedNames) { this.localizedNames = localizedNames; }

    public Integer getDisplayOrder() { return displayOrder; }
    public void setDisplayOrder(Integer displayOrder) { this.displayOrder = displayOrder; }

    public int getContentCount() { return contentCount; }
    public void setContentCount(int contentCount) { this.contentCount = contentCount; }
}
