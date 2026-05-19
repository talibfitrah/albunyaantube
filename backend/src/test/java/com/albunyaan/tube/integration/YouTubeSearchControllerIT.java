package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeSearchResponse;
import com.albunyaan.tube.service.YouTubeSearchService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan G B7: integration test for GET /api/admin/youtube/search.
 *
 * <p>YouTubeSearchService is mocked — unit tests in B6 cover service logic.
 * This IT verifies HTTP wiring, auth gating, and request-param validation.
 */
class YouTubeSearchControllerIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    YouTubeSearchService youtubeSearchService;

    // ─── Tests ──────────────────────────────────────────────────────────────

    @Test
    void getSearch_asModerator_returns200() throws Exception {
        when(youtubeSearchService.search(any(), any(), any()))
                .thenReturn(new YouTubeSearchResponse(List.of(), null));

        stubAuthAs("uid-search-it-mod", "moderator");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "kittens")
                        .param("type", "CHANNEL")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items").isArray());
    }

    @Test
    void getSearch_asAdmin_returns200() throws Exception {
        when(youtubeSearchService.search(any(), any(), any()))
                .thenReturn(new YouTubeSearchResponse(List.of(), null));

        stubAuthAs("uid-search-it-admin", "admin");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "kittens")
                        .param("type", "CHANNEL")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk());
    }

    @Test
    void getSearch_asUser_returns403() throws Exception {
        stubAuthAs("uid-search-it-user", "user");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "kittens")
                        .param("type", "CHANNEL")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isForbidden());
    }

    @Test
    void getSearch_emptyQuery_returns400() throws Exception {
        stubAuthAs("uid-search-it-blank", "moderator");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "")
                        .param("type", "CHANNEL")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void getSearch_withPageToken_passes() throws Exception {
        when(youtubeSearchService.search(any(), any(), any()))
                .thenReturn(new YouTubeSearchResponse(List.of(), "next-token-abc"));

        stubAuthAs("uid-search-it-paged", "moderator");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "cats")
                        .param("type", "VIDEO")
                        .param("pageToken", "some-token")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.nextPageToken").value("next-token-abc"));
    }

    @Test
    void getSearch_missingType_returns400() throws Exception {
        stubAuthAs("uid-search-it-notype", "moderator");
        mvc.perform(get("/api/admin/youtube/search")
                        .param("q", "kittens")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isBadRequest());
    }

    // ─── Helpers ────────────────────────────────────────────────────────────

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
