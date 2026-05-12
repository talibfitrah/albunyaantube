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
import org.springframework.http.MediaType;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan E T6 — RequestChangesIT
 *
 * Exercises the full REQUEST_CHANGES state-machine over HTTP:
 *   PENDING → REQUEST_CHANGES → PENDING (re-submit) → APPROVED
 */
class RequestChangesIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    private static final String MOD_UID   = "uid-rc-mod";
    private static final String ADMIN_UID = "uid-rc-admin";

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

    private Map<String, Object> channelBody(String youtubeId) {
        Map<String, Object> body = new HashMap<>();
        body.put("youtubeId", youtubeId);
        body.put("categoryIds", List.of("cat-1"));
        body.put("name", "Test Channel");
        body.put("title", "Test Channel");
        body.put("url", "https://yt/" + youtubeId);
        return body;
    }

    // ── Test ──────────────────────────────────────────────────────────────────

    @Test
    void requestChanges_fullStateMachine() throws Exception {

        // 1. Seed moderator + admin
        seedActiveUser(MOD_UID,   "mod@test.com",   "Test Mod",   "MODERATOR");
        seedActiveUser(ADMIN_UID, "admin@test.com", "Test Admin", "ADMIN");

        // 2. Moderator submits a channel → 201 PENDING
        stubAs(MOD_UID, "mod@test.com", "MODERATOR");
        String submitBody = json.writeValueAsString(channelBody("UC_test_1"));

        String createResponse = mvc.perform(
                        post("/api/admin/registry/channels")
                                .header("Authorization", "Bearer fake")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(submitBody))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andReturn().getResponse().getContentAsString();

        JsonNode createdChannel = json.readTree(createResponse);
        String channelId = createdChannel.path("id").asText();
        assertFalse(channelId.isBlank(), "Channel id should be assigned");

        // 3. Admin requests changes → 200 REQUEST_CHANGES
        stubAs(ADMIN_UID, "admin@test.com", "ADMIN");
        Map<String, Object> rcBody = Map.of(
                "note", "wrong category",
                "contentType", "channel"
        );

        mvc.perform(post("/api/admin/approvals/{id}/request-changes", channelId)
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(rcBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUEST_CHANGES"));

        // 4. Re-fetch channel; assert status=REQUEST_CHANGES and reviewNotes set
        String channelDoc = mvc.perform(
                        get("/api/admin/registry/channels/{id}", channelId)
                                .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REQUEST_CHANGES"))
                .andExpect(jsonPath("$.approvalMetadata.reviewNotes").value("wrong category"))
                .andReturn().getResponse().getContentAsString();

        assertNotNull(channelDoc);

        // 5. Moderator re-submits (same youtubeId) → 200, back to PENDING, metadata cleared
        stubAs(MOD_UID, "mod@test.com", "MODERATOR");

        mvc.perform(post("/api/admin/registry/channels")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(submitBody))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.approvalMetadata").doesNotExist());

        // 6. Admin approves → 200 APPROVED
        stubAs(ADMIN_UID, "admin@test.com", "ADMIN");
        Map<String, Object> approveBody = Map.of("reviewNotes", "looks good now");

        mvc.perform(post("/api/admin/approvals/{id}/approve", channelId)
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(approveBody)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("APPROVED"));
    }
}
