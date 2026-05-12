package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.sync.FavoriteSyncDto;
import com.albunyaan.tube.dto.sync.PlaylistSyncDto;
import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
import com.albunyaan.tube.dto.sync.SubscriptionSyncDto;
import com.albunyaan.tube.dto.sync.SyncCursors;
import com.albunyaan.tube.dto.sync.SyncPageDto;
import com.albunyaan.tube.dto.sync.SyncResponseDto;
import com.albunyaan.tube.exception.GlobalExceptionHandler;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.sync.SyncService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.test.web.servlet.MockMvc;

import java.util.List;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SyncController.class)
@AutoConfigureMockMvc(addFilters = false)
@Import(GlobalExceptionHandler.class)
class SyncControllerTest {

    @Autowired
    MockMvc mvc;

    @MockBean
    SyncService service;

    @MockBean
    FirebaseAuth firebaseAuth;

    @MockBean
    UserRepository userRepository;

    ObjectMapper json = new ObjectMapper();

    private static final String TEST_UID = "uid-1";
    private static final String TEST_EMAIL = "user@test.com";

    @BeforeEach
    void setUp() {
        FirebaseUserDetails principal = new FirebaseUserDetails(TEST_UID, TEST_EMAIL, "user");
        UsernamePasswordAuthenticationToken auth =
                new UsernamePasswordAuthenticationToken(principal, null, List.of());
        SecurityContextHolder.getContext().setAuthentication(auth);
    }

    @AfterEach
    void clearSecurityContext() {
        SecurityContextHolder.clearContext();
    }

    // ── Test 1: GET /sync happy path (empty pages) ──────────────────────────

    @Test
    void getSyncReturns200WithEmptyPagesWhenServiceReturnsEmpty() throws Exception {
        when(service.pull(eq(TEST_UID), any(SyncCursors.class)))
                .thenReturn(new SyncResponseDto(
                        new SyncPageDto<>(List.of(), null),
                        new SyncPageDto<>(List.of(), null),
                        new SyncPageDto<>(List.of(), null)));

        mvc.perform(get("/api/account/sync"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items").isArray())
                .andExpect(jsonPath("$.subscriptions.nextCursor").doesNotExist())
                .andExpect(jsonPath("$.playlists.items").isArray())
                .andExpect(jsonPath("$.favorites.items").isArray());
    }

    // ── Test 2: PUT /subscriptions/{id} delegates to service ───────────────

    @Test
    void putSubscriptionDelegates() throws Exception {
        PutSubscriptionRequest req = new PutSubscriptionRequest();
        req.setChannelUrl("u");
        req.setName("n");
        req.setSubscribedAt(100L);

        SubscriptionSyncDto echo = new SubscriptionSyncDto();
        echo.setEntityId("ch1");
        echo.setUpdatedAt(500L);
        echo.setDeleted(false);
        echo.setChannelUrl("u");
        echo.setName("n");
        echo.setSubscribedAt(100L);

        when(service.upsertSubscription(eq(TEST_UID), eq("ch1"), any()))
                .thenReturn(echo);

        mvc.perform(put("/api/account/subscriptions/ch1")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("ch1"))
                .andExpect(jsonPath("$.updatedAt").value(500));
    }

    // ── Test 3: DELETE /playlists/{id} delegates to service ────────────────

    @Test
    void deletePlaylistDelegates() throws Exception {
        PlaylistSyncDto tomb = new PlaylistSyncDto();
        tomb.setEntityId("pl1");
        tomb.setDeleted(true);
        tomb.setUpdatedAt(600L);

        when(service.tombstonePlaylist(eq(TEST_UID), eq("pl1")))
                .thenReturn(tomb);

        mvc.perform(delete("/api/account/playlists/pl1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true));
    }

    // ── Test 4: DELETE /favorites/{id} delegates to service ────────────────

    @Test
    void deleteFavoriteDelegates() throws Exception {
        FavoriteSyncDto tomb = new FavoriteSyncDto();
        tomb.setEntityId("fav1");
        tomb.setDeleted(true);
        tomb.setUpdatedAt(700L);

        when(service.tombstoneFavorite(eq(TEST_UID), eq("fav1")))
                .thenReturn(tomb);

        mvc.perform(delete("/api/account/favorites/fav1"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.deleted").value(true))
                .andExpect(jsonPath("$.entityId").value("fav1"));
    }
}
