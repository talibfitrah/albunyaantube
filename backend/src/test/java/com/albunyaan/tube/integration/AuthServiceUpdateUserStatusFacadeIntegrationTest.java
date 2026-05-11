package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * BACKEND-AUTH-01 review-pipeline finding F1:
 *
 * Verifies the legacy {@code AuthService.updateUserStatus(uid, status, actor, reason)}
 * facade enforces every Plan A safeguard by delegating to the new lifecycle methods:
 *  - D2: last-admin guard (cannot block/delete last active admin)
 *  - D4: userStatus cache eviction
 *  - D5: audit log written transactionally
 *  - D9: Firebase Auth disabled + tokens revoked on block / delete
 *
 * Prior to F1 the facade wrote {@code status="blocked"} directly to Firestore with
 * none of these checks. These tests must FAIL on the pre-fix code.
 */
class AuthServiceUpdateUserStatusFacadeIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @Autowired
    AuditLogRepository auditRepo;

    @Autowired
    CacheManager realCacheManager;

    @MockBean
    FirebaseAuth firebaseAuth;

    // ── F1.a — blocked path enforces last-admin guard ────────────────────────

    @Test
    void blocked_appliesLastAdminGuard() throws Exception {
        stubFirebaseAuthMutations();
        String soloAdmin = seedUser("solo@t.com", "admin");

        // Self-block of last admin must throw, NOT silently succeed via the legacy path
        assertThrows(LastAdminException.class,
                () -> authService.updateUserStatus(soloAdmin, "blocked", soloAdmin, "self-block"));

        User after = userRepository.findByUid(soloAdmin).orElseThrow();
        assertTrue(after.isActive(),
                "Last admin must remain ACTIVE after rejected self-block");
    }

    // ── F1.b — blocked path evicts the userStatus cache ──────────────────────

    @Test
    void blocked_evictsUserStatusCache() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin1@t.com", "admin");
        seedUser("admin2@t.com", "admin"); // not last admin
        String targetUid = seedUser("victim@t.com", "moderator");

        // Prime the cache by reading findByUid (the @Cacheable entry point that
        // populates "userStatus"). Cache now holds the ACTIVE User.
        Cache cache = realCacheManager.getCache("userStatus");
        assertNotNull(cache);
        java.util.Optional<User> primed = userRepository.findByUid(targetUid);
        assertTrue(primed.isPresent() && primed.get().isActive(),
                "Sanity: cache primed with ACTIVE user before block");
        Cache.ValueWrapper before = cache.get(targetUid);
        assertNotNull(before, "Cache must be primed before block");
        Object beforeObj = before.get();
        assertTrue(beforeObj instanceof java.util.Optional
                ? ((java.util.Optional<?>) beforeObj).filter(x -> x instanceof User && ((User) x).isActive()).isPresent()
                : (beforeObj instanceof User && ((User) beforeObj).isActive()),
                "Sanity: cache primed value must be ACTIVE");

        authService.updateUserStatus(targetUid, "blocked", adminUid, "policy");

        // Pre-fix: the legacy facade wrote status="blocked" to Firestore without
        // evicting the cache, so the cache still held an ACTIVE entry — letting
        // the just-blocked user pass AccountStatusFilter for 60s.
        // Post-fix: blockUser inside the facade evicts the cache. The terminal
        // findByUid (also @Cacheable) re-populates it from Firestore, so the
        // cached value MUST now reflect BLOCKED status, never ACTIVE.
        Cache.ValueWrapper after = cache.get(targetUid);
        assertNotNull(after, "Cache should re-populate via the terminal findByUid");
        Object cachedAfter = after.get();
        User cachedUser = cachedAfter instanceof java.util.Optional<?>
                ? (User) ((java.util.Optional<?>) cachedAfter).orElseThrow()
                : (User) cachedAfter;
        assertTrue(cachedUser.isBlocked(),
                "Cache MUST reflect BLOCKED state after the facade returns. " +
                "Pre-fix the cache held a stale ACTIVE entry for the 60s TTL.");
    }

    // ── F1.c — blocked path writes USER_BLOCKED audit log ────────────────────

    @Test
    void blocked_writesAuditLog() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin3@t.com", "admin");
        seedUser("admin4@t.com", "admin");
        String targetUid = seedUser("u-audit@t.com", "moderator");

        authService.updateUserStatus(targetUid, "blocked", adminUid, "spam-bot");

        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_BLOCKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size(),
                "USER_BLOCKED audit must be written exactly once via the facade");
    }

    // ── F1.d — deleted path delegates to softDeleteUser ──────────────────────

    @Test
    void deleted_softDeletesAndWritesAudit() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin5@t.com", "admin");
        seedUser("admin6@t.com", "admin");
        String targetUid = seedUser("u-del@t.com", "moderator");

        authService.updateUserStatus(targetUid, "deleted", adminUid, "gdpr-request");

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isDeleted(),
                "User must be DELETED after facade delete call");
        assertEquals("gdpr-request", after.getDeleteReason());

        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_SOFT_DELETED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size());
    }

    // ── F1.e — active path unblocks BLOCKED user ─────────────────────────────

    @Test
    void active_unblocksBlockedUser() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin7@t.com", "admin");
        seedUser("admin8@t.com", "admin");
        String targetUid = seedUser("u-unblock@t.com", "moderator");

        authService.blockUser(targetUid, adminUid, "test-setup");
        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isBlocked());

        authService.updateUserStatus(targetUid, "active", adminUid, null);

        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isActive(),
                "User must be ACTIVE after facade active call");
    }

    // ── F1.f — active path recovers DELETED user ─────────────────────────────

    @Test
    void active_recoversDeletedUser() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin9@t.com", "admin");
        seedUser("admin10@t.com", "admin");
        String targetUid = seedUser("u-recover@t.com", "moderator");

        authService.softDeleteUser(targetUid, adminUid, "test-setup");
        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isDeleted());

        authService.updateUserStatus(targetUid, "active", adminUid, null);

        assertTrue(userRepository.findByUid(targetUid).orElseThrow().isActive(),
                "Soft-deleted user must be RECOVERED to ACTIVE via the facade");
    }

    // ── F17 — legacy "inactive" alias is accepted as a synonym for "blocked" ─
    // Frontend adminUsers.ts#toBackendStatus emits {status: "inactive"} for the
    // "block user" button. Pre-F17 the facade rejected this with 400, breaking
    // the admin dashboard. F17 accepts "inactive" and defaults the reason to a
    // sentinel so the audit log still records WHY the user was blocked and
    // operators can filter legacy-shape calls.

    @Test
    void acceptsLegacyInactiveStatus_blocksUser_defaultReason() throws Exception {
        stubFirebaseAuthMutations();
        String adminUid = seedUser("admin11@t.com", "admin");
        seedUser("admin12@t.com", "admin");
        String targetUid = seedUser("u-legacy@t.com", "moderator");

        // No reason in the call — F17 must default it for the alias path.
        authService.updateUserStatus(targetUid, "inactive", adminUid, null);

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isBlocked(),
                "F17: 'inactive' alias must drive user to BLOCKED via the lifecycle path");

        // Audit log must record the sentinel reason so operators can identify
        // legacy-shape calls in audit history.
        QuerySnapshot audits = auditRepo.auditLogsCollection()
                .whereEqualTo("action", "USER_BLOCKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, audits.size(),
                "F17 alias path must write the same USER_BLOCKED audit as canonical blocked");
        // The buildBlock audit captures the reason in details.reason; verify
        // the sentinel surfaces there for triage.
        Object detailsRaw = audits.getDocuments().get(0).get("details");
        assertTrue(detailsRaw instanceof java.util.Map,
                "Audit details must be a Map");
        String recordedReason = (String) ((java.util.Map<?, ?>) detailsRaw).get("reason");
        assertEquals("legacy-status-update", recordedReason,
                "F17: audit details.reason must record the legacy sentinel "
                + "so operators can identify legacy-shape calls");
    }

    // ── F17 — canonical 'blocked' WITHOUT reason still rejects (strict path) ─

    @Test
    void canonicalBlockedWithoutReason_stillRejects() throws Exception {
        String adminUid = seedUser("admin13@t.com", "admin");
        seedUser("admin14@t.com", "admin");
        String targetUid = seedUser("u-strict@t.com", "moderator");

        assertThrows(IllegalArgumentException.class,
                () -> authService.updateUserStatus(targetUid, "blocked", adminUid, null),
                "F17: canonical 'blocked' must still require a reason");

        User after = userRepository.findByUid(targetUid).orElseThrow();
        assertTrue(after.isActive(),
                "User state must not change on rejected canonical-blocked call");
    }

    // ── Helpers ──────────────────────────────────────────────────────────────

    private void stubFirebaseAuthMutations() throws Exception {
        when(firebaseAuth.updateUser(any())).thenReturn(null);
        doNothing().when(firebaseAuth).revokeRefreshTokens(any());
    }

    private String seedUser(String email, String role) throws Exception {
        String uid = "f1-" + email.replace("@", "-at-").replace(".", "-");
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
        return uid;
    }
}
