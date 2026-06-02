package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.sync.PutFavoriteRequest;
import com.albunyaan.tube.dto.sync.PutPlaylistRequest;
import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.service.ImportGraduationService;
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A10 — TDD: verify approvalStatus / source / importedAt flow through
 * the sync PUT → GET round-trip for all three entity types.
 *
 * Uses the same harness as SyncControllerIT (MockMvc + Firestore emulator,
 * mocked FirebaseAuth). Each test gets a fresh user; BaseIntegrationTest
 * clears the "users" collection (and its subcollections) before each test.
 */
class SyncImportFieldsIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    @Autowired
    ImportGraduationService graduationService;

    // ── Subscription: explicit AWAITING + source + importedAt ───────────────

    @Test
    void subscriptionWithAwaitingStatusRoundTrips() throws Exception {
        String uid = seedActiveUser("sub-awaiting@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest req = new PutSubscriptionRequest();
        req.setChannelUrl("https://yt/UCabc");
        req.setName("ImportedChannel");
        req.setSubscribedAt(1L);
        req.setApprovalStatus("AWAITING");
        req.setSource("YOUTUBE_IMPORT");
        req.setImportedAt(123L);

        // PUT — echo response must carry the fields
        mvc.perform(put("/api/account/subscriptions/UCabc")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.importedAt").value(123));

        // GET /sync — pull response must also carry the fields
        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[0].entityId").value("UCabc"))
                .andExpect(jsonPath("$.subscriptions.items[0].approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.subscriptions.items[0].source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.subscriptions.items[0].importedAt").value(123));
    }

    // ── Subscription: null approvalStatus defaults to "APPROVED" ────────────

    @Test
    void subscriptionWithNullApprovalStatusDefaultsToApproved() throws Exception {
        String uid = seedActiveUser("sub-approved@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest req = new PutSubscriptionRequest();
        req.setChannelUrl("https://yt/UCdef");
        req.setName("OldClient");
        req.setSubscribedAt(1L);
        // approvalStatus intentionally NOT set (null) — simulates old client

        // F3: approvalStatus is server-derived. Seed the channel as APPROVED in the
        // registry so this organic add (no client approvalStatus) is stored APPROVED.
        seedApprovedContent("channels", "UCdef");
        // PUT echo must reflect the derived APPROVED status
        mvc.perform(put("/api/account/subscriptions/UCdef")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

        // GET /sync — pulled row must also return APPROVED
        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[0].approvalStatus").value("APPROVED"));
    }

    // ── Playlist: explicit AWAITING round-trip ───────────────────────────────

    @Test
    void playlistWithAwaitingStatusRoundTrips() throws Exception {
        String uid = seedActiveUser("pl-awaiting@test");
        stubAuthAs(uid, "user");

        PutPlaylistRequest req = new PutPlaylistRequest();
        req.setPlaylistUrl("https://yt/PLxyz");
        req.setName("ImportedPlaylist");
        req.setSavedAt(1L);
        req.setApprovalStatus("AWAITING");
        req.setSource("YOUTUBE_IMPORT");
        req.setImportedAt(456L);

        mvc.perform(put("/api/account/playlists/PLxyz")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.importedAt").value(456));

        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlists.items[0].approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.playlists.items[0].source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.playlists.items[0].importedAt").value(456));
    }

    // ── Playlist: null approvalStatus defaults to "APPROVED" ─────────────────

    @Test
    void playlistWithNullApprovalStatusDefaultsToApproved() throws Exception {
        String uid = seedActiveUser("pl-approved@test");
        stubAuthAs(uid, "user");

        PutPlaylistRequest req = new PutPlaylistRequest();
        req.setPlaylistUrl("https://yt/PLold");
        req.setName("OldPlaylist");
        req.setSavedAt(1L);
        // approvalStatus intentionally NOT set

        seedApprovedContent("playlists", "PLold");
        mvc.perform(put("/api/account/playlists/PLold")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.playlists.items[0].approvalStatus").value("APPROVED"));
    }

    // ── Favorite: explicit AWAITING round-trip ───────────────────────────────

    @Test
    void favoriteWithAwaitingStatusRoundTrips() throws Exception {
        String uid = seedActiveUser("fav-awaiting@test");
        stubAuthAs(uid, "user");

        PutFavoriteRequest req = new PutFavoriteRequest();
        req.setTitle("ImportedVideo");
        req.setChannelName("Channel");
        req.setDurationSeconds(60);
        req.setAddedAt(1L);
        req.setApprovalStatus("AWAITING");
        req.setSource("YOUTUBE_IMPORT");
        req.setImportedAt(789L);

        mvc.perform(put("/api/account/favorites/VIDxyz")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.importedAt").value(789));

        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorites.items[0].approvalStatus").value("AWAITING"))
                .andExpect(jsonPath("$.favorites.items[0].source").value("YOUTUBE_IMPORT"))
                .andExpect(jsonPath("$.favorites.items[0].importedAt").value(789));
    }

    // ── Favorite: null approvalStatus defaults to "APPROVED" ─────────────────

    @Test
    void favoriteWithNullApprovalStatusDefaultsToApproved() throws Exception {
        String uid = seedActiveUser("fav-approved@test");
        stubAuthAs(uid, "user");

        PutFavoriteRequest req = new PutFavoriteRequest();
        req.setTitle("OldVideo");
        req.setChannelName("OldChannel");
        req.setDurationSeconds(0);
        req.setAddedAt(1L);
        // approvalStatus intentionally NOT set

        seedApprovedContent("videos", "VIDold");
        mvc.perform(put("/api/account/favorites/VIDold")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("APPROVED"));

        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.favorites.items[0].approvalStatus").value("APPROVED"));
    }

    // ── F1+F2: full graduation round-trip (PUT AWAITING → approve fan-out → pull APPROVED) ──

    @Test
    void importedRowGraduatesToApprovedAfterFanOut() throws Exception {
        String uid = seedActiveUser("grad@test");
        stubAuthAs(uid, "user");

        // 1. User imports an as-yet-unknown channel → stored AWAITING (UCgrad not in registry).
        PutSubscriptionRequest req = new PutSubscriptionRequest();
        req.setChannelUrl("https://yt/UCgrad");
        req.setName("Imported");
        req.setSubscribedAt(1L);
        req.setApprovalStatus("AWAITING");
        mvc.perform(put("/api/account/subscriptions/UCgrad")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("AWAITING"));

        // 2. Admin approves the content (registry row becomes APPROVED); the fan-out runs.
        seedApprovedContent("channels", "UCgrad");
        graduationService.onApproved(YouTubeContentType.CHANNEL, "UCgrad");

        // 3. The next delta-pull must surface the row as APPROVED. This exercises BOTH
        //    F1 (the fan-out matched because youtubeId is now persisted on the row) and
        //    F2 (updatedAt was bumped to a Timestamp the cursor can read and page past).
        mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[0].entityId").value("UCgrad"))
                .andExpect(jsonPath("$.subscriptions.items[0].approvalStatus").value("APPROVED"));
    }

    @Test
    void clientCannotForceApprovedForUnregisteredContent() throws Exception {
        // F3 (moderation bypass): a malicious client PUTs approvalStatus=APPROVED for a
        // channel NOT approved in the registry. The server must ignore the claim and store
        // AWAITING — a user cannot un-gate its own un-vetted import.
        String uid = seedActiveUser("evil@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest req = new PutSubscriptionRequest();
        req.setChannelUrl("https://yt/UCevil");
        req.setName("NotApproved");
        req.setSubscribedAt(1L);
        req.setApprovalStatus("APPROVED"); // the lie — must be overridden server-side

        mvc.perform(put("/api/account/subscriptions/UCevil")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(req)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.approvalStatus").value("AWAITING"));
    }

    /** Seed an APPROVED registry doc so ContentApprovalGate derives APPROVED for the id. */
    private void seedApprovedContent(String collection, String youtubeId) throws Exception {
        firestore.collection(collection).document()
                .set(Map.of("youtubeId", youtubeId, "status", "APPROVED"))
                .get(5, java.util.concurrent.TimeUnit.SECONDS);
    }

    // ── Helpers (mirrored from SyncControllerIT) ─────────────────────────────

    private String seedActiveUser(String email)
            throws java.util.concurrent.ExecutionException,
                   InterruptedException,
                   java.util.concurrent.TimeoutException {
        String uid = "uid-" + email.replace("@", "-").replace(".", "-");
        User u = new User(uid, email, null, "user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

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
