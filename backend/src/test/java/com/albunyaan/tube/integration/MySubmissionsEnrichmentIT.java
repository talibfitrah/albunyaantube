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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan E T6 — MySubmissionsEnrichmentIT
 *
 * Verifies that GET /api/admin/approvals/my-submissions returns items enriched
 * with submittedByDisplayName and submittedByEmail from the user's profile.
 */
class MySubmissionsEnrichmentIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    private static final String MOD_UID          = "uid-enrich-mod";
    private static final String MOD_EMAIL        = "mod@test.com";
    private static final String MOD_DISPLAY_NAME = "Test Mod";

    // ── Helpers ───────────────────────────────────────────────────────────────

    private void seedActiveUser(String uid, String email, String displayName, String role)
            throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setDisplayName(displayName);
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

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void mySubmissions_enrichedWithDisplayNameAndEmail() throws Exception {

        // 1. Seed moderator with known displayName and email
        seedActiveUser(MOD_UID, MOD_EMAIL, MOD_DISPLAY_NAME, "MODERATOR");

        // 2. Stub moderator token and submit a channel
        stubAs(MOD_UID, MOD_EMAIL, "MODERATOR");

        Map<String, Object> channelBody = new HashMap<>();
        channelBody.put("youtubeId", "UC_enrich_1");
        channelBody.put("categoryIds", List.of("cat-1"));
        channelBody.put("name", "Enrichment Test Channel");
        channelBody.put("title", "Enrichment Test Channel");
        channelBody.put("url", "https://yt/UC_enrich_1");

        mvc.perform(post("/api/admin/registry/channels")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(channelBody)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"));

        // 3. GET /api/admin/approvals/my-submissions → 200
        // 4. Assert items[0] has correct enrichment fields
        mvc.perform(get("/api/admin/approvals/my-submissions")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].submittedByDisplayName").value(MOD_DISPLAY_NAME))
                .andExpect(jsonPath("$.data[0].submittedByEmail").value(MOD_EMAIL));
    }
}
