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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Plan E T6 — SubmissionRateLimitIT
 *
 * Verifies that the SubmissionRateLimiter enforces 50 submissions per 24 h:
 * - Submissions 1-50 return 201 CREATED.
 * - Submission 51 returns 429 with Retry-After header and RATE_LIMIT body.
 *
 * Uses a dedicated uid ("uid-ratelimit-it") that is unique to this test class
 * to avoid polluting the in-memory limiter state shared with other ITs running
 * in the same Spring application context.
 */
class SubmissionRateLimitIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    /**
     * Unique uid for this IT — avoids cross-test rate-limiter state pollution
     * since SubmissionRateLimiter is a singleton Spring bean.
     */
    private static final String MOD_UID   = "uid-ratelimit-it";
    private static final String MOD_EMAIL = "ratelimit@test.com";

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedActiveUser(String uid, String email, String role) throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
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

    private String channelBodyJson(String youtubeId) throws Exception {
        Map<String, Object> body = new HashMap<>();
        body.put("youtubeId", youtubeId);
        body.put("categoryIds", List.of("cat-1"));
        body.put("name", "Channel " + youtubeId);
        body.put("title", "Channel " + youtubeId);
        body.put("url", "https://yt/" + youtubeId);
        return json.writeValueAsString(body);
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void rateLimiter_allows50_thenBlocks51st() throws Exception {

        // 1. Seed moderator
        seedActiveUser(MOD_UID, MOD_EMAIL, "MODERATOR");

        // 2. Stub moderator token (once — stub applies for the duration of the test)
        stubAs(MOD_UID, MOD_EMAIL, "MODERATOR");

        // 3. First 50 submissions must all succeed with 201
        for (int i = 1; i <= 50; i++) {
            mvc.perform(post("/api/admin/registry/channels")
                            .header("Authorization", "Bearer fake")
                            .contentType(MediaType.APPLICATION_JSON)
                            .content(channelBodyJson("UC_rl_" + i)))
                    .andExpect(status().isCreated());
        }

        // 4. 51st submission must be rejected with 429
        String responseBody = mvc.perform(post("/api/admin/registry/channels")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(channelBodyJson("UC_rl_51")))
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

        // Verify Retry-After header value is also positive
        String retryAfterHeader = mvc.perform(post("/api/admin/registry/channels")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(channelBodyJson("UC_rl_52")))
                .andExpect(status().isTooManyRequests())
                .andReturn().getResponse().getHeader("Retry-After");

        assertNotNull(retryAfterHeader, "Retry-After header must be present");
        assertTrue(Long.parseLong(retryAfterHeader) > 0,
                "Retry-After header value must be positive, got: " + retryAfterHeader);
    }
}
