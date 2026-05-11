package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class RoleTest {

    @Test void getValue_isLowercase() {
        assertEquals("user", Role.USER.getValue());
        assertEquals("moderator", Role.MODERATOR.getValue());
        assertEquals("admin", Role.ADMIN.getValue());
    }

    @Test void fromString_caseInsensitive() {
        assertEquals(Role.ADMIN, Role.fromString("admin"));
        assertEquals(Role.ADMIN, Role.fromString("ADMIN"));
        assertEquals(Role.ADMIN, Role.fromString("Admin"));
        assertEquals(Role.MODERATOR, Role.fromString("moderator"));
        assertEquals(Role.USER, Role.fromString("user"));
    }

    @Test void fromString_unknownDefaultsToUser() {
        assertEquals(Role.USER, Role.fromString("god"));
        assertEquals(Role.USER, Role.fromString(""));
        assertEquals(Role.USER, Role.fromString(null));
        assertEquals(Role.USER, Role.fromString("   "));
    }

    @Test void includesEqualOrAbove_orderingMatchesPrivilege() {
        assertTrue(Role.ADMIN.includesEqualOrAbove(Role.MODERATOR));
        assertTrue(Role.ADMIN.includesEqualOrAbove(Role.USER));
        assertTrue(Role.MODERATOR.includesEqualOrAbove(Role.USER));
        assertFalse(Role.USER.includesEqualOrAbove(Role.MODERATOR));
        assertFalse(Role.MODERATOR.includesEqualOrAbove(Role.ADMIN));
    }
}
