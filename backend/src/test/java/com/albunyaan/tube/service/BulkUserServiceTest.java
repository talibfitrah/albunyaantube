package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class BulkUserServiceTest {

    private static final FirebaseUserDetails ADMIN_ACTOR =
            new FirebaseUserDetails("admin-uid", "admin@fitrahtube.com", "admin", true);

    @Test
    void happyPath_block_threeUsersSucceed() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid(anyString())).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("u1", "u2", "u3"),
                ADMIN_ACTOR,
                "policy violation");

        assertEquals(List.of("u1", "u2", "u3"), result.getSuccesses());
        assertTrue(result.getFailures().isEmpty());

        verify(authService).blockUser("u1", "admin-uid", "policy violation");
        verify(authService).blockUser("u2", "admin-uid", "policy violation");
        verify(authService).blockUser("u3", "admin-uid", "policy violation");

        verify(auditLog).log(
                eq("USER_BULK_ACTION"),
                eq("user"), eq("(batch)"),
                eq(ADMIN_ACTOR),
                argThat(m -> "block".equals(m.get("action"))
                        && Integer.valueOf(3).equals(m.get("successes"))
                        && Integer.valueOf(0).equals(m.get("failures"))));
    }

    @Test
    void selfAction_isBucketedAsFailure_andDoesNotCallAuth() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUid("other-uid")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-uid", "other-uid"),
                ADMIN_ACTOR,
                null);

        assertEquals(List.of("other-uid"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin-uid", result.getFailures().get(0).uid());
        assertEquals("self_action_forbidden", result.getFailures().get(0).reason());

        verify(authService, never()).blockUser(eq("admin-uid"), any(), any());
        verify(authService).blockUser("other-uid", "admin-uid", null);
    }

    @Test
    void blockAdminTarget_isRejected_withAdminTargetForbidden() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        // Cubic R5 Tier B switched the admin-target guard to
        // findByUidUncached() to avoid the userStatus Caffeine cache. Tests
        // must mock the uncached variant for the guard to fire.
        when(userRepo.findByUidUncached("admin-target")).thenReturn(Optional.of(userWithRole("admin")));
        when(userRepo.findByUidUncached("user-target")).thenReturn(Optional.of(userWithRole("user")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK,
                List.of("admin-target", "user-target"),
                ADMIN_ACTOR,
                null);

        assertEquals(List.of("user-target"), result.getSuccesses());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin_target_forbidden", result.getFailures().get(0).reason());
        verify(authService, never()).blockUser(eq("admin-target"), any(), any());
    }

    @Test
    void recoverAdminTarget_isRejected() throws Exception {
        // Cubic round-2 (P3): admin-target guard now applies uniformly across
        // BLOCK / DELETE / RECOVER / REVOKE_SESSIONS. The previous RECOVER carve-
        // out meant a deleted admin could be silently restored without the
        // per-target admin_target_forbidden audit row. Bulk endpoints never
        // touch admins.
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUidUncached("admin-target")).thenReturn(Optional.of(userWithRole("admin")));

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.RECOVER,
                List.of("admin-target"),
                ADMIN_ACTOR,
                null);

        assertTrue(result.getSuccesses().isEmpty());
        assertEquals(1, result.getFailures().size());
        assertEquals("admin-target", result.getFailures().get(0).uid());
        assertEquals("admin_target_forbidden", result.getFailures().get(0).reason());
        verify(authService, never()).recoverUser(eq("admin-target"), any());
    }

    @Test
    void typedConflict_notBlocked_classifiedTyped() throws Exception {
        // Cubic R6 P2 — typed UserStateConflictException replaces the
        // pre-fix msg.contains classifier. The enum-driven switch makes a
        // future rename of AuthService throw-site strings a compile error
        // rather than a silent downgrade to "invalid_state".
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUidUncached("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new UserStateConflictException(
                UserStateConflictException.ReasonCode.NOT_BLOCKED, "msg"))
                .when(authService).recoverUser("u1", "admin-uid");

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.RECOVER, List.of("u1"), ADMIN_ACTOR, null);

        assertEquals("not_blocked", result.getFailures().get(0).reason());
    }

    @Test
    void typedConflict_blockedCannotDelete_classifiedTyped() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUidUncached("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new UserStateConflictException(
                UserStateConflictException.ReasonCode.BLOCKED_CANNOT_DELETE, "msg"))
                .when(authService).softDeleteUser("u1", "admin-uid", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.DELETE, List.of("u1"), ADMIN_ACTOR, null);

        assertEquals("blocked_cannot_delete", result.getFailures().get(0).reason());
    }

    @Test
    void plainIllegalState_legacyPath_classifiedAsInvalidState() throws Exception {
        // Cubic R6 P2 — defensive fallback: a plain IllegalStateException
        // (no ReasonCode) from a future call site that hasn't yet been
        // promoted to UserStateConflictException routes to "invalid_state"
        // rather than swallowing the failure as "firebase_error".
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUidUncached("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new IllegalStateException("some untyped legacy message"))
                .when(authService).blockUser("u1", "admin-uid", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK, List.of("u1"), ADMIN_ACTOR, null);

        assertEquals("invalid_state", result.getFailures().get(0).reason());
    }

    @Test
    void notFoundException_classifiedAsUserNotFound() throws Exception {
        AuthService authService = mock(AuthService.class);
        UserRepository userRepo = mock(UserRepository.class);
        AuditLogService auditLog = mock(AuditLogService.class);

        when(userRepo.findByUidUncached("u1")).thenReturn(Optional.of(userWithRole("user")));
        doThrow(new com.albunyaan.tube.service.UserNotFoundException("u1"))
                .when(authService).blockUser("u1", "admin-uid", null);

        BulkUserService svc = new BulkUserService(authService, userRepo, auditLog);

        BulkUserActionResult result = svc.execute(
                BulkAction.BLOCK, List.of("u1"), ADMIN_ACTOR, null);

        assertEquals("user_not_found", result.getFailures().get(0).reason());
    }

    // Cubic R6 P2 — the pre-fix `alreadyBlocked` / `alreadyDeleted` /
    // `unrecognizedIllegalStateMessage` cases have been folded into the new
    // typed-conflict tests above (typedConflict_*_classifiedTyped) plus
    // plainIllegalState_legacyPath_classifiedAsInvalidState. The R5 dead-
    // branch removal already collapsed the "already blocked" / "already
    // deleted" predicates because F13 made BLOCK/DELETE idempotent — those
    // strings are never thrown any more.

    private static User userWithRole(String role) {
        User u = new User();
        u.setRole(role);
        return u;
    }
}
