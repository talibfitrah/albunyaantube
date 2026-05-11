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

    // ── F8 — recordRecover clears block metadata as well as delete metadata ──
    // Pre-fix a block→softDelete→recover path left blockedAt/By/Reason populated
    // on an ACTIVE user doc. The User doc must mirror only the CURRENT state.

    @Test void recordRecover_clearsBlockMetadata() {
        User u = new User();
        // Simulate a leftover-block path: block first, then soft-delete (without
        // unblocking), then recover. We bypass the AuthService guard to test the
        // model-level behaviour in isolation.
        u.recordBlock("admin-1", "internal-troll-banned-per-ticket-1234");
        // Skip the unblock — simulate pre-F8 data where someone soft-deleted a
        // blocked user without unblocking first.
        u.recordSoftDelete("admin-2", "policy");
        // Verify the pre-condition: block metadata is still on the doc.
        assertEquals("internal-troll-banned-per-ticket-1234", u.getBlockReason(),
                "Pre-condition: block metadata should still be present after softDelete");
        assertNotNull(u.getBlockedAt(), "Pre-condition: blockedAt should still be set");

        // Act: recover.
        u.recordRecover("admin-3");

        // Assert: every block field is cleared, status is ACTIVE.
        assertEquals("active", u.getStatus());
        assertNull(u.getBlockedAt(), "blockedAt must be cleared on recover");
        assertNull(u.getBlockedBy(), "blockedBy must be cleared on recover");
        assertNull(u.getBlockReason(), "blockReason must be cleared on recover");
        assertNull(u.getDeletedAt());
        assertNull(u.getDeletedBy());
        assertNull(u.getDeleteReason());
    }
}
