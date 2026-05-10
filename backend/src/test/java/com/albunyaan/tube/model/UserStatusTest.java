package com.albunyaan.tube.model;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserStatusTest {

    @Test void getValue_isLowercaseSnake() {
        assertEquals("active", UserStatus.ACTIVE.getValue());
        assertEquals("blocked", UserStatus.BLOCKED.getValue());
        assertEquals("deleted", UserStatus.DELETED.getValue());
        assertEquals("pending_profile", UserStatus.PENDING_PROFILE.getValue());
    }

    @Test void fromString_caseInsensitiveAndTolerantOfHyphens() {
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("active"));
        assertEquals(UserStatus.BLOCKED, UserStatus.fromString("BLOCKED"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("pending_profile"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("pending-profile"));
        assertEquals(UserStatus.PENDING_PROFILE, UserStatus.fromString("PendingProfile"));
    }

    @Test void fromString_unknownDefaultsToActive() {
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString(null));
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString(""));
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("inactive")); // legacy value
        assertEquals(UserStatus.ACTIVE, UserStatus.fromString("anything-weird"));
    }

    @Test void canTokensFromBlockedOrDeletedAccounts_isFalse() {
        assertTrue(UserStatus.ACTIVE.allowsAuth());
        assertTrue(UserStatus.PENDING_PROFILE.allowsAuth());
        assertFalse(UserStatus.BLOCKED.allowsAuth());
        assertFalse(UserStatus.DELETED.allowsAuth());
    }
}
