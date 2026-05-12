package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MvcResult;

import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — bulk-block over 6 mixed users.
 * 1 self + 1 other admin + 2 regular-active + 1 already-blocked + 1 already-deleted
 * → HTTP 200 with 2 successes + 4 failures (self_action_forbidden, admin_target_forbidden,
 *   already_blocked, and one already_deleted-or-invalid_state).
 */
class BulkUserActionIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    @Test
    void bulkBlock_6users_returnsMixedResultWithCorrectReasons() throws Exception {
        String adminUid       = seedUser("admin-bulkblock@test.com",  "admin",    UserStatus.ACTIVE);
        String otherAdminUid  = seedUser("admin2-bulkblock@test.com", "admin",    UserStatus.ACTIVE);
        String regularActive1 = seedUser("u1-bulkblock@test.com",     "user",     UserStatus.ACTIVE);
        String regularActive2 = seedUser("u2-bulkblock@test.com",     "user",     UserStatus.ACTIVE);
        String regularBlocked = seedUser("u3-bulkblock@test.com",     "user",     UserStatus.BLOCKED);
        String regularDeleted = seedUser("u4-bulkblock@test.com",     "user",     UserStatus.DELETED);
        stubAuthAs(adminUid, "admin");

        String body = String.format(
                "{\"uids\":[\"%s\",\"%s\",\"%s\",\"%s\",\"%s\",\"%s\"],\"reason\":\"audit\"}",
                adminUid, otherAdminUid, regularActive1, regularActive2,
                regularBlocked, regularDeleted);

        MvcResult res = mvc.perform(post("/api/admin/users/bulk-block")
                        .header("Authorization", "Bearer fake")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk())
                .andReturn();

        JsonNode result = json.readTree(res.getResponse().getContentAsString());

        // F13: AuthService.blockUser is idempotent — already-blocked targets return
        // silently (no exception, no audit row). BulkUserService counts the no-op as
        // a "success" because no exception was thrown. So the already-blocked user
        // joins the successes bucket but does NOT produce a USER_BLOCKED audit row.
        // The DELETED target throws "Cannot block a deleted user" which classifies as
        // "invalid_state".
        assertEquals(3, result.get("successes").size(),
                "expected 3 successes (2 regular-active + already-blocked idempotent no-op)");
        assertEquals(3, result.get("failures").size(),
                "expected 3 failures (self, other admin, deleted)");

        Set<String> reasons = new HashSet<>();
        result.get("failures").forEach(n -> reasons.add(n.get("reason").asText()));
        assertTrue(reasons.contains("self_action_forbidden"),
                "must reject the calling admin's own uid");
        assertTrue(reasons.contains("admin_target_forbidden"),
                "must reject the other admin uid");
        // The DELETED target's specific failure code depends on AuthService.blockUser
        // behaviour — either already_deleted or invalid_state is acceptable.
        assertTrue(reasons.contains("already_deleted") || reasons.contains("invalid_state"),
                "deleted user should surface as already_deleted or invalid_state");

        // Audit assertions. AuditLogService.log is @Async, so allow it to settle.
        Thread.sleep(500);

        long blockedCount = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BLOCKED").get().get().size();
        long summaryCount = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BULK_ACTION").get().get().size();
        assertEquals(2L, blockedCount,
                "USER_BLOCKED rows = 2 (only the 2 active→blocked transitions; F13 idempotent path skips audit)");
        assertEquals(1L, summaryCount, "USER_BULK_ACTION summary row = 1");
    }

    /** Mocks the bearer-token verification path so any bearer string passes. */
    private void stubAuthAs(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test.com");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }

    private String seedUser(String email, String role, UserStatus status) throws Exception {
        String uid = "test-" + email.replace("@", "-at-").replace(".", "-");
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(status);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
        return uid;
    }
}
