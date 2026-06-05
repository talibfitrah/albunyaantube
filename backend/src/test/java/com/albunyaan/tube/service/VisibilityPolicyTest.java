package com.albunyaan.tube.service;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Finding 3: direct coverage for the single source of truth for visibility access control.
 * Every read path (by-id, cursor feeds, search index, import resolve, sync derive, downloads)
 * routes through these two methods, so the edge cases that an anonymous or organic request
 * actually hits — null uid, null/empty grants, blank/legacy visibility — are pinned here.
 */
class VisibilityPolicyTest {

    // ── isPublic ──────────────────────────────────────────────────────────────

    @Test
    void isPublic_nullBlankOrPublic_isPublic() {
        assertTrue(VisibilityPolicy.isPublic(null), "legacy/organic null visibility is public");
        assertTrue(VisibilityPolicy.isPublic(""));
        assertTrue(VisibilityPolicy.isPublic("   "), "blank is public");
        assertTrue(VisibilityPolicy.isPublic("PUBLIC"));
        assertTrue(VisibilityPolicy.isPublic("public"), "case-insensitive");
    }

    @Test
    void isPublic_personalOrUnknown_isNotPublic_failClosed() {
        assertFalse(VisibilityPolicy.isPublic("PERSONAL"));
        assertFalse(VisibilityPolicy.isPublic("personal"));
        // Fail-closed: any future/unknown visibility value must NOT fall open to the public.
        assertFalse(VisibilityPolicy.isPublic("UNLISTED"));
    }

    // ── isAccessible ──────────────────────────────────────────────────────────

    @Test
    void isAccessible_public_alwaysAccessible_evenAnonymous() {
        assertTrue(VisibilityPolicy.isAccessible("PUBLIC", null, null));
        assertTrue(VisibilityPolicy.isAccessible(null, null, "any-uid"));
        assertTrue(VisibilityPolicy.isAccessible("  ", List.of(), null));
    }

    @Test
    void isAccessible_personal_grantee_accessible() {
        assertTrue(VisibilityPolicy.isAccessible("PERSONAL", List.of("u1", "u2"), "u2"));
        assertTrue(VisibilityPolicy.isAccessible("personal", List.of("u1"), "u1"), "case-insensitive visibility");
    }

    @Test
    void isAccessible_personal_nonGranteeOrAnonymous_denied() {
        assertFalse(VisibilityPolicy.isAccessible("PERSONAL", List.of("u1"), "other"));
        assertFalse(VisibilityPolicy.isAccessible("PERSONAL", List.of("u1"), null), "anonymous denied on personal");
        assertFalse(VisibilityPolicy.isAccessible("PERSONAL", null, "u1"), "null grants denied");
        assertFalse(VisibilityPolicy.isAccessible("PERSONAL", List.of(), "u1"), "empty grants denied");
    }
}
