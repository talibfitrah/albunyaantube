package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Plan G B5: integration test for the ProfileUpdateRateLimitInterceptor.
 *
 * Verifies that the interceptor enforces 10 updates per hour per uid:
 *  - Requests 1-10 return 200 OK.
 *  - Request 11 returns 429 with Retry-After header and RATE_LIMIT body.
 *
 * Uses a dedicated uid constant to avoid polluting the in-memory limiter
 * state shared with other ITs running in the same Spring application context.
 */
class ProfileUpdateRateLimitIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    /** Unique uid for this IT — avoids cross-test rate-limiter state pollution. */
    private static final String USER_UID   = "uid-profile-ratelimit-it";
    private static final String USER_EMAIL = "profile-ratelimit@test.com";

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedActiveUser(String uid, String email) throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole("user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
    }

    private void stubAs(String uid, String email, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(email);
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void putProfile_11th_in_one_hour_returns429() throws Exception {

        // 1. Seed user and stub auth token (stub persists for the test duration)
        seedActiveUser(USER_UID, USER_EMAIL);
        stubAs(USER_UID, USER_EMAIL, "user");

        // 2. First 10 updates must succeed
        for (int i = 0; i < 10; i++) {
            mvc.perform(put("/api/account/profile")
                            .header("Authorization", "Bearer fake")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"displayName\":\"Name" + i + "\"}"))
                    .andExpect(status().isOk());
        }

        // 3. 11th update must be rejected with 429
        String responseBody = mvc.perform(put("/api/account/profile")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Name11\"}"))
                .andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After"))
                .andExpect(jsonPath("$.code").value("RATE_LIMIT"))
                .andExpect(jsonPath("$.retryAfterSeconds").isNumber())
                .andReturn().getResponse().getContentAsString();

        // Verify retryAfterSeconds is a positive number
        long retryAfterSeconds = json.readTree(responseBody)
                .path("retryAfterSeconds").asLong();
        assertTrue(retryAfterSeconds > 0,
                "retryAfterSeconds must be positive, got: " + retryAfterSeconds);

        // Verify Retry-After header is also present and positive
        String retryAfterHeader = mvc.perform(put("/api/account/profile")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"displayName\":\"Name12\"}"))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertNotNull(retryAfterHeader, "Retry-After header must be present");
        assertTrue(Long.parseLong(retryAfterHeader) > 0,
                "Retry-After header value must be positive, got: " + retryAfterHeader);
    }
}
