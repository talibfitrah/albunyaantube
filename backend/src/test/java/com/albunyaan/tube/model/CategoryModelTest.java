package com.albunyaan.tube.model;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** Review Focus 2: a PUT body that never names "type" must not clear a LIVE category. */
    @Test
    void aBodyWithoutTypeLeavesTheTypeUnprovided() throws Exception {
        ObjectMapper json = new ObjectMapper();
        Category none = json.readValue("{\"name\":\"Channels Live\"}", Category.class);
        Category cleared = json.readValue("{\"name\":\"Channels Live\",\"type\":null}", Category.class);
        Category live = json.readValue("{\"name\":\"Channels Live\",\"type\":\"LIVE\"}", Category.class);

        assertFalse(none.isTypeProvided());
        assertTrue(cleared.isTypeProvided());
        assertNull(cleared.getType());
        assertTrue(live.isLive());
        assertTrue(Category.isValidType(null));
        assertTrue(Category.isValidType("LIVE"));
        assertFalse(Category.isValidType("PODCAST"));
    }
}
