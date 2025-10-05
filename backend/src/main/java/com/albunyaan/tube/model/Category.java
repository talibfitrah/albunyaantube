package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.HashMap;
import java.util.Map;

/**
 * FIREBASE-MIGRATE-03: Category Model (Firestore)
 *
 * Firestore document representing a category or subcategory.
 * Categories form a hierarchical structure using parentCategoryId.
 *
 * Collection: categories
 * Document ID: Auto-generated or custom slug
 *
 * Examples:
 * - Top-level category: {name: "Quran", parentCategoryId: null}
 * - Subcategory: {name: "Tafsir", parentCategoryId: "quran"}
 */
public class Category {

    @DocumentId
    private String id;

    private String name;

    /**
     * URL-friendly slug for category
     */
    private String slug;

    /**
     * Parent category ID for hierarchical structure.
     * null for top-level categories.
     */
    private String parentCategoryId;

    /**
     * Explicit flag persisted in Firestore for quickly filtering
     * top-level categories. This mirrors parentCategoryId == null.
     */
    private Boolean topLevel;

    /**
     * Optional icon/image URL for category display
     */
    private String icon;

    /**
     * Display order for sorting categories
     */
    private Integer displayOrder;

    /**
     * Localized names: {locale: name}
     * Example: {"en": "Quran", "ar": "القرآن", "nl": "Koran"}
     */
    private Map<String, String> localizedNames;

    /**
     * Metadata
     */
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private String createdBy; // Firebase UID
    private String updatedBy; // Firebase UID

    public Category() {
        this.localizedNames = new HashMap<>();
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
        this.topLevel = Boolean.TRUE;
    }

    public Category(String name, String parentCategoryId) {
        this();
        this.name = name;
        this.parentCategoryId = parentCategoryId;
        this.topLevel = parentCategoryId == null;
    }

    // Getters and Setters

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

    public String getParentCategoryId() {
        return parentCategoryId;
    }

    public void setParentCategoryId(String parentCategoryId) {
        this.parentCategoryId = parentCategoryId;
        this.topLevel = parentCategoryId == null;
    }

    public Boolean getTopLevel() {
        return topLevel != null ? topLevel : parentCategoryId == null;
    }

    public void setTopLevel(Boolean topLevel) {
        this.topLevel = topLevel;
    }

    public String getIcon() {
        return icon;
    }

    public void setIcon(String icon) {
        this.icon = icon;
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

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public String getUpdatedBy() {
        return updatedBy;
    }

    public void setUpdatedBy(String updatedBy) {
        this.updatedBy = updatedBy;
    }

    public String getSlug() {
        return slug;
    }

    public void setSlug(String slug) {
        this.slug = slug;
    }

    public String getParentId() {
        return parentCategoryId;
    }

    public void setParentId(String parentId) {
        setParentCategoryId(parentId);
    }

    /**
     * Check if this is a top-level category (no parent)
     */
    public boolean isTopLevel() {
        return Boolean.TRUE.equals(getTopLevel());
    }

    /**
     * Update modification timestamp
     */
    public void touch() {
        this.updatedAt = Timestamp.now();
    }
}
