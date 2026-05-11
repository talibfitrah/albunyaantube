package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class UserModelTest {

    @Test void noArgCtor_defaultsRoleToNull_andStatusToActive() {
        // F3: role MUST default to null. Previously it defaulted to "moderator",
        // which silently granted moderator role to any Firestore doc missing the
        // role field after deserialisation.
        User u = new User();
        assertNull(u.getRole(), "Role default must be null — never an implicit grant");
        assertEquals("active", u.getStatus());
    }

    @Test void isAdminAndIsModerator_returnFalseForNullRole() {
        // Defensive: callers reading role on a freshly-deserialised doc must not
        // accidentally see admin/moderator privileges when the field is null.
        User u = new User();
        assertFalse(u.isAdmin(), "isAdmin() must be false for null role");
        assertFalse(u.isModerator(), "isModerator() must be false for null role");
    }

    @Test void getRoleEnum_returnsUserForNullRole() {
        // Role.fromString(null) returns USER (least-privilege fallback) so any
        // path going through the typed accessor lands on USER, not MODERATOR.
        User u = new User();
        assertEquals(Role.USER, u.getRoleEnum());
    }

    @Test void typedAccessorsRoundTrip() {
        User u = new User();
        u.setRoleEnum(Role.USER);
        assertEquals("user", u.getRole());
        assertEquals(Role.USER, u.getRoleEnum());

        u.setStatusEnum(UserStatus.BLOCKED);
        assertEquals("blocked", u.getStatus());
        assertEquals(UserStatus.BLOCKED, u.getStatusEnum());
    }

    @Test void blockedAndDeletedFlagsReflectStatus() {
        User u = new User();
        u.setStatusEnum(UserStatus.BLOCKED);
        assertTrue(u.isBlocked());
        assertFalse(u.isDeleted());
        assertFalse(u.isActive());

        u.setStatusEnum(UserStatus.DELETED);
        assertTrue(u.isDeleted());
        assertFalse(u.isBlocked());
    }

    @Test void recordBlock_setsAuditFieldsAndStatus() {
        User u = new User();
        u.recordBlock("admin-uid", "spam");
        assertEquals("blocked", u.getStatus());
        assertEquals("admin-uid", u.getBlockedBy());
        assertEquals("spam", u.getBlockReason());
        assertNotNull(u.getBlockedAt());
    }

    @Test void recordSoftDelete_setsAuditFieldsAndStatus() {
        User u = new User();
        u.recordSoftDelete("admin-uid", "user-request");
        assertEquals("deleted", u.getStatus());
        assertEquals("admin-uid", u.getDeletedBy());
        assertEquals("user-request", u.getDeleteReason());
        assertNotNull(u.getDeletedAt());
    }

    @Test void recordRecover_clearsDeletionAndReactivates() {
        User u = new User();
        u.recordSoftDelete("a", "r");
        u.recordRecover("admin-uid");
        assertEquals("active", u.getStatus());
        assertEquals("admin-uid", u.getRecoveredBy());
        assertNotNull(u.getRecoveredAt());
        assertNull(u.getDeletedAt());
        assertNull(u.getDeletedBy());
        assertNull(u.getDeleteReason());
    }

    @Test void recordUnblock_clearsBlockFieldsAndReactivates() {
        User u = new User();
        u.recordBlock("admin-1", "spam");
        u.recordUnblock("admin-2");
        assertEquals("active", u.getStatus());
        assertNull(u.getBlockedAt());
        assertNull(u.getBlockedBy());
        assertNull(u.getBlockReason());
    }
}
