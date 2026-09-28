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
        // Phone is optional: the request above sends none, so none is stored.
        assertNull(reloaded.getPhoneNumber());
    }

    /**
     * Cubic R5 P1 #11: under-13 SOFT-deletes (a hard delete let a fresh sign-in lazy-create a new
     * PENDING_PROFILE row and retry with another date). Revoke and disable come first, then the
     * tombstone (AccountProfileService.rejectUnderAge).
     */
    @Test
    void completeProfileUnder13SoftDeletesDocAndRevokesTokens() throws Exception {
        String uid = seedPendingProfileUser("kid@test");
        stubAuthAs(uid, "user");

        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Kid\",\"dateOfBirth\":\"2020-01-01\"}"))
            .andExpect(status().isUnprocessableEntity())
            .andExpect(jsonPath("$.code").value("AGE_INELIGIBLE"));

        // The doc stays as a tombstone (durable assertion, read back from the emulator).
        User tombstone = userRepository.findByUid(uid).orElseThrow(
                () -> new AssertionError("an under-13 rejection must keep the tombstone, not hard-delete"));
        assertTrue(tombstone.isDeleted(), "status must be deleted");
        assertEquals("age-ineligible", tombstone.getDeleteReason());
        assertEquals(uid, tombstone.getDeletedBy(), "the system acts as the user's own uid");
        assertNotNull(tombstone.getDeletedAt());
        assertNull(tombstone.getProfileCompletedAt(), "the rejected profile must not be completed");
        // Tokens revoked and the Auth account disabled, both before the tombstone write.
        var order = Mockito.inOrder(firebaseAuth);
        order.verify(firebaseAuth).revokeRefreshTokens(uid);
        // UpdateRequest's getters are package-private; the disable itself is unit-tested in the service.
        order.verify(firebaseAuth).updateUser(Mockito.any(com.google.firebase.auth.UserRecord.UpdateRequest.class));
    }

    /**
     * Cubic R5 P1 #9: an IDENTICAL retry (the first response lost in flight) is idempotent → 200;
     * a retry with DIFFERENT data is refused → 409, since the profile is locked once set.
     */
    @Test
    void completeProfileSecondAttemptIsIdempotentButDifferentDataReturns409() throws Exception {
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
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.displayName").value("Bob"));

        for (String different : java.util.List.of(
                "{\"displayName\":\"Robert\",\"dateOfBirth\":\"2000-01-01\"}",
                "{\"displayName\":\"Bob\",\"dateOfBirth\":\"2000-01-01\",\"phoneNumber\":\"+31612345678\"}")) {
            mvc.perform(post("/api/account/profile")
                    .header("Authorization", "Bearer fake-token")
                    .contentType(MediaType.APPLICATION_JSON).content(different))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PROFILE_ALREADY_COMPLETED"));
        }
        User reloaded = userRepository.findByUid(uid).orElseThrow();
        assertEquals("Bob", reloaded.getDisplayName());
        assertNull(reloaded.getPhoneNumber());
    }

    /** PUT "phoneNumber":"" removes a saved phone: stored as null, and /me says null. */
    @Test
    void updateProfileSetThenRemovePhone() throws Exception {
        String uid = seedPendingProfileUser("dave@test");
        stubAuthAs(uid, "user");
        mvc.perform(post("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON)
                .content("{\"displayName\":\"Dave\",\"dateOfBirth\":\"2000-01-01\"}"))
            .andExpect(status().isOk());

        mvc.perform(put("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"+31612345678\"}"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.phoneNumber").value("+31612345678"));
        assertEquals("+31612345678", userRepository.findByUid(uid).orElseThrow().getPhoneNumber());

        mvc.perform(put("/api/account/profile")
                .header("Authorization", "Bearer fake-token")
                .contentType(MediaType.APPLICATION_JSON).content("{\"phoneNumber\":\"\"}"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"phoneNumber\":null")));

        mvc.perform(get("/api/account/me").header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(content().string(org.hamcrest.Matchers.containsString("\"phoneNumber\":null")))
            .andExpect(jsonPath("$.displayName").value("Dave"));
        User reloaded = userRepository.findByUid(uid).orElseThrow();
        assertNull(reloaded.getPhoneNumber());
        assertEquals("Dave", reloaded.getDisplayName());
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
        // POST /profile refuses an unverified email (403 EMAIL_NOT_VERIFIED) before the service runs.
        Mockito.when(token.isEmailVerified()).thenReturn(true);
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }
}
