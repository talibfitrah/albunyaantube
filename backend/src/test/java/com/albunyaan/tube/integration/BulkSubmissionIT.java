package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.registry.*;
import com.albunyaan.tube.model.VideoType;
import com.albunyaan.tube.service.PreviewFetchResult;
import com.albunyaan.tube.service.YouTubeGateway;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * Integration tests for bulk preview + submit endpoints.
 *
 * Tests the full controller → service → Firestore-writer wiring.
 * YouTubeGateway is mocked via @MockBean so NewPipe is never called over the network.
 * Auth uses the same FirebaseAuth stub pattern as the rest of the IT suite.
 */
class BulkSubmissionIT extends BaseIntegrationTest {

    @Autowired
    private MockMvc mvc;

    @Autowired
    private ObjectMapper json;

    @MockBean
    private FirebaseAuth firebaseAuth;

    /**
     * Mock the gateway so the test doesn't hit YouTube —
     * we're testing controller + service + Firestore wiring only.
     */
    @MockBean
    private YouTubeGateway gateway;

    /**
     * Seed the "cat-1" category so the per-row categoryIds existence
     * check in {@code BulkSubmissionService.submit} (added 2026-05-23 to
     * close H2 unbounded-fan-out finding) finds a real category and
     * rows are not rejected with INVALID_CATEGORY. Runs after the
     * inherited {@link BaseIntegrationTest#setUpFirestore} clears the
     * categories collection.
     */
    @org.junit.jupiter.api.BeforeEach
    void seedCategory() throws Exception {
        firestore.collection("categories").document("cat-1")
                .set(Map.of("name", "Test Category", "slug", "test-category"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    // -------------------------------------------------------------------------
    // Auth helper (copied from BulkUserActionIT pattern)
    // -------------------------------------------------------------------------

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

    // -------------------------------------------------------------------------
    // Test cases
    // -------------------------------------------------------------------------

    /**
     * §7.1 — Moderator can submit a batch; all rows land PENDING regardless of
     * the requested status in the body (role normalization enforced in service).
     */
    @Test
    void moderator_canSubmitBatch_allRowsLandPending() throws Exception {
        stubAuthAs("mod-uid", "moderator");

        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenAnswer(inv -> PreviewFetchResult.ok(
                        new PreviewMetadata(
                                "UCxxxxxxxxxxxxxxxxxxxxxx",
                                "Test Channel",
                                "thumb.jpg",
                                null, null, 100L, null, null, null),
                        VideoType.STANDARD));

        // Preview step: 2 URLs (channel + playlist)
        var previewReq = new BulkPreviewRequest(
                List.of(
                        "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                        "https://www.youtube.com/playlist?list=PLxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxxx"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(previewReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rows.length()").value(2));

        // Submit one channel; request status=APPROVED but moderator → must be downgraded to PENDING
        var submitReq = new BulkSubmitRequest(
                List.of(new SubmitRow(
                        0,
                        "https://www.youtube.com/channel/UCxxxxxxxxxxxxxxxxxxxxxx",
                        YouTubeContentType.CHANNEL,
                        null,
                        new PreviewMetadata(
                                "UCxxxxxxxxxxxxxxxxxxxxxx", "Test Channel", "thumb.jpg",
                                null, null, 100L, null, null, null),
                        List.of("cat-1"))),
                "APPROVED");   // moderator — must be downgraded to PENDING by service

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-mod-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1))
                .andExpect(jsonPath("$.results[0].status").value("ADDED"));
    }

    /**
     * §7.1 — Admin can submit with status=APPROVED, bypassing the PENDING default.
     */
    @Test
    void admin_canSubmitApproved_bypassesPending() throws Exception {
        stubAuthAs("admin-uid", "admin");

        when(gateway.fetchByDetectedType(any(), any(), any()))
                .thenReturn(PreviewFetchResult.ok(
                        new PreviewMetadata(
                                "AAAAAAAAAAA", "Test Video", "thumb.jpg",
                                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx",
                                null, null, 213L, 1000L),
                        VideoType.STANDARD));

        var submitReq = new BulkSubmitRequest(
                List.of(new SubmitRow(
                        0,
                        "https://www.youtube.com/watch?v=AAAAAAAAAAA",
                        YouTubeContentType.VIDEO,
                        VideoType.STANDARD,
                        new PreviewMetadata(
                                "AAAAAAAAAAA", "Test Video", "thumb.jpg",
                                "Channel", "UCxxxxxxxxxxxxxxxxxxxxxx",
                                null, null, 213L, 1000L),
                        List.of("cat-1"))),
                "APPROVED");

        mvc.perform(post("/api/admin/registry/bulk/submit")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(submitReq)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.added").value(1));
    }

    /**
     * §7.1 — Non-admin, non-moderator (plain USER role) must receive 403 Forbidden.
     */
    @Test
    void nonAdminNonModerator_isForbidden() throws Exception {
        stubAuthAs("user-uid", "user");

        var req = new BulkPreviewRequest(
                List.of("https://www.youtube.com/watch?v=AAAAAAAAAAA"));

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-user-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isForbidden());
    }

    /**
     * §7.1 — Preview endpoint rejects a request with 26 URLs (max is 25) with 400 Bad Request.
     */
    @Test
    void preview_rejects26urls_withValidationError() throws Exception {
        stubAuthAs("admin-uid", "admin");

        var twentySix = new ArrayList<String>();
        for (int i = 0; i < 26; i++) {
            twentySix.add("https://www.youtube.com/watch?v=AAAAAAAAAAA");
        }
        var req = new BulkPreviewRequest(twentySix);

        mvc.perform(post("/api/admin/registry/bulk/preview")
                        .header("Authorization", "Bearer fake-admin-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isBadRequest());
    }
}
