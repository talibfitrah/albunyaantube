package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.Map;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan D T12 — verifies FirebaseAuthFilter's account-status gating
 * applies to the /api/account/sync route:
 *   - BLOCKED        → 403 ACCOUNT_BLOCKED
 *   - DELETED        → 401 ACCOUNT_NOT_FOUND
 *   - PENDING_PROFILE → allowed (200)
 *   - ACTIVE          → allowed (200)
 */
class SyncStatusFilterIT extends BaseIntegrationTest {

    @MockBean
    private FirebaseAuth firebaseAuth;

    @Test
    void blockedUser_getsAccountBlocked403_onSync() throws Exception {
        String uid = "uid-blocked";
        seedUser(uid, uid + "@test.com", "user", UserStatus.ACTIVE);
        markBlocked(uid, "policy");
        stubToken(uid, "user");

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake-token-" + uid))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ACCOUNT_BLOCKED"));
    }

    @Test
    void deletedUser_gets401_onSync() throws Exception {
        String uid = "uid-deleted";
        seedUser(uid, uid + "@test.com", "user", UserStatus.ACTIVE);
        markDeleted(uid);
        stubToken(uid, "user");

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake-token-" + uid))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void pendingProfileUser_isAccepted_onSync() throws Exception {
        String uid = "uid-pending";
        seedUser(uid, uid + "@test.com", "user", UserStatus.PENDING_PROFILE);
        stubToken(uid, "user");

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake-token-" + uid))
                .andExpect(status().isOk());
    }

    @Test
    void activeUser_isAccepted_onSync() throws Exception {
        String uid = "uid-active";
        seedUser(uid, uid + "@test.com", "user", UserStatus.ACTIVE);
        stubToken(uid, "user");

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake-token-" + uid))
                .andExpect(status().isOk());
    }

    // ── helpers ──────────────────────────────────────────────────────────────

    private void stubToken(String uid, String role) throws Exception {
        FirebaseToken fakeToken = mock(FirebaseToken.class);
        when(fakeToken.getUid()).thenReturn(uid);
        when(fakeToken.getEmail()).thenReturn(uid + "@test.com");
        when(fakeToken.getClaims()).thenReturn(Map.of("role", role));
        String tokenValue = "fake-token-" + uid;
        when(firebaseAuth.verifyIdToken(eq(tokenValue))).thenReturn(fakeToken);
        when(firebaseAuth.verifyIdToken(eq(tokenValue), anyBoolean())).thenReturn(fakeToken);
    }

    private void seedUser(String uid, String email, String role, UserStatus status) throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(status);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
    }

    private void markBlocked(String uid, String reason) throws Exception {
        User u = userRepository.findByUid(uid).orElseThrow();
        u.recordBlock("system-test", reason);
        userRepository.save(u);
        evictUserStatus(uid);
    }

    private void markDeleted(String uid) throws Exception {
        User u = userRepository.findByUid(uid).orElseThrow();
        u.recordSoftDelete("system-test", null);
        userRepository.save(u);
        evictUserStatus(uid);
    }

    private void evictUserStatus(String uid) {
        org.springframework.cache.Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);
    }
}
