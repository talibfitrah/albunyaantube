package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

/**
 * Firestore document representing the sort position of a content item within a category.
 *
 * Collection: category_content_order
 * Document ID: {categoryId}_{contentType}_{contentId} (deterministic, prevents duplicates)
 *
 * This enables per-category content ordering: the same playlist can be at position 1
 * in "Ramadaan" and position 5 in "Anasheed".
 */
public class CategoryContentOrder {

    @DocumentId
    private String id;

    private String categoryId;
    private String contentId;
    private String contentType; // "channel", "playlist", "video"
    private Integer position;   // 0-indexed sort position within the category
    private Timestamp updatedAt;

    public CategoryContentOrder() {
        this.updatedAt = Timestamp.now();
    }

    public CategoryContentOrder(String categoryId, String contentId, String contentType, int position) {
        this();
        java.util.Objects.requireNonNull(categoryId, "categoryId must not be null");
        java.util.Objects.requireNonNull(contentId, "contentId must not be null");
        java.util.Objects.requireNonNull(contentType, "contentType must not be null");
        this.id = generateId(categoryId, contentType, contentId);
        this.categoryId = categoryId;
        this.contentId = contentId;
        this.contentType = contentType;
        this.position = position;
    }

    /**
     * Generate a deterministic document ID from the composite key.
     */
    public static String generateId(String categoryId, String contentType, String contentId) {
        return categoryId + "_" + contentType + "_" + contentId;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getCategoryId() {
        return categoryId;
    }

    public void setCategoryId(String categoryId) {
        this.categoryId = categoryId;
    }

    public String getContentId() {
        return contentId;
    }

    public void setContentId(String contentId) {
        this.contentId = contentId;
    }

    public String getContentType() {
        return contentType;
    }

    public void setContentType(String contentType) {
        this.contentType = contentType;
    }

    public Integer getPosition() {
        return position;
    }

    public void setPosition(Integer position) {
        this.position = position;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void touch() {
        this.updatedAt = Timestamp.now();
    }
}
