package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class CategoryModelTest {

    @Test
    void parentCategorySyncsTopLevelFlag() {
        Category category = new Category();

        category.setParentCategoryId(null);
        assertTrue(category.isTopLevel(), "Top-level categories should set the flag true");
        assertTrue(category.getTopLevel(), "Explicit getter should reflect topLevel flag");

        category.setParentCategoryId("parent-123");
        assertFalse(category.isTopLevel(), "Child categories should clear the topLevel flag");
        assertFalse(category.getTopLevel(), "Flag should persist for Firestore serialization");
    }

    @Test
    void canOverrideTopLevelExplicitly() {
        Category category = new Category("Name", null);
        assertTrue(category.isTopLevel(), "Constructor marks top-level by default");

        category.setTopLevel(Boolean.FALSE);
        assertFalse(category.getTopLevel(), "Setter should allow Firestore-deserialized value");

        category.setParentCategoryId(null);
        assertTrue(category.getTopLevel(), "Re-applying parent null should fix the flag");
    }
}

