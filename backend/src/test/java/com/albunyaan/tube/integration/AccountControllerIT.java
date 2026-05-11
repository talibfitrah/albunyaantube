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

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Plan C T4: integration test for /api/account/* against Firestore emulator
 * + mocked FirebaseAuth Admin. Verifies the happy-path, age-ineligible
 * (deletion + token revocation), 409 idempotency, and GET /me.
 *
 * mvc and userRepository are inherited from BaseIntegrationTest.
 */
class AccountControllerIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    // ─── Tests ────────────────────────────────────────────────────────────────

    @Test
    void completeProfileAdultSuccess() throws Exception {
        String uid = seedPendingProfileUser("alice@test");
        stubAuthAs(uid, "user");

        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Alice\",\"dateOfBirth\":\"2000-01-01\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.status").value("active"))
            .andExpect(jsonPath("$.displayName").value("Alice"));

        User reloaded = userRepository.findByUid(uid).orElseThrow();
        assertEquals(UserStatus.ACTIVE, reloaded.getStatusEnum());
        assertNotNull(reloaded.getDateOfBirth());
        assertNotNull(reloaded.getProfileCompletedAt());
    }

    @Test
    void completeProfileUnder13DeletesDocAndRevokesTokens() throws Exception {
        String uid = seedPendingProfileUser("kid@test");
        stubAuthAs(uid, "user");

        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Kid\",\"dateOfBirth\":\"2020-01-01\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));

        // Doc was deleted (durable assertion)
        assertTrue(userRepository.findByUid(uid).isEmpty(),
                "user doc must be deleted on AGE_INELIGIBLE");
        // Verify revokeRefreshTokens was called BEFORE the delete (order matters per spec D4)
        Mockito.verify(firebaseAuth).revokeRefreshTokens(uid);
    }

    @Test
    void completeProfileSecondAttemptReturns409() throws Exception {
        String uid = seedPendingProfileUser("bob@test");
        stubAuthAs(uid, "user");
        String body = "{\"displayName\":\"Bob\",\"dateOfBirth\":\"2000-01-01\"}";

        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isOk());

        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON).content(body))
            .andExpect(status().isConflict())
            .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETED"));
    }

    @Test
    void getMeReturnsCallerProfile() throws Exception {
        String uid = seedPendingProfileUser("carol@test");
        stubAuthAs(uid, "user");

        mvc.perform(get("/api/account/me")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.uid").value(uid))
            .andExpect(jsonPath("$.status").value("pending_profile"));
    }

    @Test
    void postProfileMissingAuthHeaderReturns401or403() throws Exception {
        // No stubbed token, no Authorization header. FirebaseAuthFilter rejects.
        mvc.perform(post("/api/account/profile")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"x\",\"dateOfBirth\":\"2000-01-01\"}"))
            .andExpect(status().is(
                org.hamcrest.Matchers.anyOf(
                    org.hamcrest.Matchers.equalTo(401),
                    org.hamcrest.Matchers.equalTo(403))));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Save a minimal PENDING_PROFILE user directly to the Firestore emulator and
     * return its UID. UID is deterministic from email to ensure predictability
     * within a test while BaseIntegrationTest's @BeforeEach clears the collection
     * between tests, so there are no cross-test collisions.
     */
    private String seedPendingProfileUser(String email) throws Exception {
        String uid = "uid-" + email.replace("@", "-").replace(".", "-");
        User u = new User(uid, email, null, "user");
        u.setStatusEnum(UserStatus.PENDING_PROFILE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /**
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token
     * carrying the given uid and role claim. Matches any bearer string so all
     * requests in a test share the same stub without leaking state across tests.
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
