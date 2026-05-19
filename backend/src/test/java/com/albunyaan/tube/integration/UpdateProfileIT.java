package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan G B3: integration test for PUT /api/account/profile against the
 * Firestore emulator + mocked FirebaseAuth Admin.
 *
 * Covers:
 *   - Happy-path: displayName update returns 200 with updated profile
 *   - Under-age dateOfBirth: returns 422 AGE_INELIGIBLE and soft-deletes the user
 *   - Unauthenticated request: returns 401
 *
 * mvc and userRepository are inherited from BaseIntegrationTest.
 */
class UpdateProfileIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void putProfile_authenticatedActiveUser_updatesDisplayName() throws Exception {
        String uid = seedActiveUser("update-happy@test");
        stubAuthAs(uid, "user");

        mvc.perform(put("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"New Name\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("New Name"))
            .andExpect(jsonPath("$.uid").value(uid));
    }

    @Test
    void putProfile_underAgeDob_returns422AndSoftDeletesUser() throws Exception {
        String uid = seedActiveUser("update-underage@test");
        stubAuthAs(uid, "user");

        long twelveYearsAgoEpochSec = LocalDate.now(java.time.Clock.systemUTC())
                .minusYears(12)
                .atStartOfDay(ZoneOffset.UTC)
                .toEpochSecond();

        mvc.perform(put("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"dateOfBirth\":{\"seconds\":" + twelveYearsAgoEpochSec + ",\"nanos\":0}}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));

        // Verify the user was soft-deleted (status=DELETED, deleteReason=age-ineligible).
        // Use findByUidUncached to bypass the Caffeine cache and hit Firestore directly.
        User reloaded = userRepository.findByUidUncached(uid).orElseThrow(
                () -> new AssertionError("User doc must still exist after soft-delete"));
        assertEquals(UserStatus.DELETED, reloaded.getStatusEnum(),
                "User status must be DELETED after age-ineligible rejection");
        assertEquals("age-ineligible", reloaded.getDeleteReason(),
                "deleteReason must be 'age-ineligible'");

        // Verify token revocation was called
        Mockito.verify(firebaseAuth).revokeRefreshTokens(uid);
    }

    @Test
    void putProfile_unauthenticated_returns401() throws Exception {
        mvc.perform(put("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"X\"}"))
            .andExpect(status().is(
                org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.equalTo(401),
                    org.hamcrest.Matchers.equalTo(403))));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Seed an ACTIVE user directly into the Firestore emulator and return its UID.
     * UID is deterministic from email; BaseIntegrationTest clears the collection
     * between tests so there are no cross-test collisions.
     */
    private String seedActiveUser(String email) throws Exception {
        String uid = "uid-" + email.replace("@", "-").replace(".", "-");
        User u = new User(uid, email, "Original Name", "user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /**
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token
     * carrying the given uid and role claim. Mirrors the pattern from AccountControllerIT.
     */
    private void stubAuthAs(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }
}
