package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — single + bulk revoke-sessions HTTP path.
 * FirebaseAuth is mocked, so we verify the revokeRefreshTokens call was made
 * rather than inspecting real tokensValidAfterTime state.
 */
class RevokeSessionsIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void singleRevokeSessions_callsFirebase_andAudits() throws Exception {
        String adminUid = seedUser("admin-rs@test.com", "admin", UserStatus.ACTIVE);
        String targetUid = seedUser("target-rs@test.com", "user", UserStatus.ACTIVE);
        stubAuthAs(adminUid, "admin");

        mvc.perform(post("/api/admin/users/" + targetUid + "/revoke-sessions")
                        .header("Authorization", "Bearer fake")
                        .contentType("application/json")
                        .content("{\"reason\":\"reported phishing\"}"))
                .andExpect(status().isNoContent());

        verify(firebaseAuth).revokeRefreshTokens(targetUid);

        Thread.sleep(300);

        QuerySnapshot snap = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED")
                .whereEqualTo("entityId", targetUid)
                .get().get();
        assertEquals(1, snap.size());

        @SuppressWarnings("unchecked")
        Map<String, Object> details =
                (Map<String, Object>) snap.getDocuments().get(0).get("details");
        assertNotNull(details);
        assertEquals("reported phishing", details.get("reason"));
    }

    @Test
    void bulkRevokeSessions_audits3perUid_plusSummary() throws Exception {
        String adminUid = seedUser("admin-rs-bulk@test.com", "admin", UserStatus.ACTIVE);
        String u1 = seedUser("u1-rs@test.com", "user", UserStatus.ACTIVE);
        String u2 = seedUser("u2-rs@test.com", "user", UserStatus.ACTIVE);
        String u3 = seedUser("u3-rs@test.com", "user", UserStatus.ACTIVE);
        stubAuthAs(adminUid, "admin");

        String body = String.format(
                "{\"uids\":[\"%s\",\"%s\",\"%s\"],\"reason\":\"sweep\"}", u1, u2, u3);

        mvc.perform(post("/api/admin/users/bulk-revoke-sessions")
                        .header("Authorization", "Bearer fake")
                        .contentType("application/json")
                        .content(body))
                .andExpect(status().isOk());

        verify(firebaseAuth, times(3)).revokeRefreshTokens(anyString());

        Thread.sleep(500);

        long perUid = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED").get().get().size();
        long summary = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_BULK_ACTION").get().get().size();
        assertEquals(3L, perUid, "3 USER_SESSIONS_REVOKED audits");
        assertEquals(1L, summary, "1 USER_BULK_ACTION summary");
    }

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
