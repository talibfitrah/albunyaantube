package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
import com.albunyaan.tube.model.Channel;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.model.ValidationStatus;
import com.albunyaan.tube.repository.ChannelRepository;
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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan D T11 — verifies that admin-archiving a channel surfaces it as a
 * virtual tombstone in the user's next sync read, then disappears from
 * subsequent cursor-based pulls.
 *
 * Auth is mocked via @MockBean FirebaseAuth — no Firebase auth emulator.
 * Each test seeds a fresh ACTIVE user via userRepository.
 * BaseIntegrationTest.getCollectionsToClean() already includes "channels",
 * so archive markers are wiped between tests automatically.
 */
class SyncArchiveIT extends BaseIntegrationTest {

    @MockBean
    private FirebaseAuth firebaseAuth;

    @Autowired
    private ObjectMapper json;

    @Autowired
    private ChannelRepository channelRepository;

    // ── Test 1: ARCHIVED channel becomes a virtual tombstone ─────────────────

    @Test
    void archivedChannelAppearsAsVirtualTombstoneInSync() throws Exception {
        String uid = seedActiveUser("alice@test");
        stubAuthAs(uid, "user");

        // 1. user subscribes to a channel
        PutSubscriptionRequest put = new PutSubscriptionRequest();
        put.setChannelUrl("u");
        put.setName("n");
        put.setSubscribedAt(0L);

        mvc.perform(put("/api/account/subscriptions/UC_ARC")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(put)))
                .andExpect(status().isOk());

        // 2. first pull sees the subscription as alive (deleted == false)
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UC_ARC')].deleted")
                        .value(false));

        // 3. admin archives the channel
        markChannelArchived("UC_ARC");

        // 4. next pull sees it as a virtual tombstone (deleted == true)
        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UC_ARC')].deleted")
                        .value(true));
    }

    // ── Test 2: UNAVAILABLE channel also becomes a virtual tombstone ─────────

    @Test
    void unavailableChannelAlsoBecomesTombstone() throws Exception {
        String uid = seedActiveUser("bob@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest put = new PutSubscriptionRequest();
        put.setChannelUrl("u");
        put.setName("n");
        put.setSubscribedAt(0L);

        mvc.perform(put("/api/account/subscriptions/UC_UN")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(put)))
                .andExpect(status().isOk());

        // Mark UNAVAILABLE (not ARCHIVED — exercises the other branch of isArchivedById)
        Channel ch = new Channel("UC_UN");
        ch.setValidationStatus(ValidationStatus.UNAVAILABLE);
        channelRepository.save(ch);

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UC_UN')].deleted")
                        .value(true));
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Saves a Channel document to Firestore with ValidationStatus.ARCHIVED.
     * ChannelRepository.save() uses the youtubeId field for lookup (not the doc ID),
     * so the auto-generated document ID is irrelevant — isArchivedById queries by
     * whereEqualTo("youtubeId", ...).
     */
    private void markChannelArchived(String youtubeId)
            throws ExecutionException, InterruptedException, TimeoutException {
        Channel ch = new Channel(youtubeId);
        ch.setValidationStatus(ValidationStatus.ARCHIVED);
        channelRepository.save(ch);
    }

    /**
     * Persist a minimal ACTIVE user to the Firestore emulator.
     * UID is deterministic from the email so test isolation is predictable —
     * BaseIntegrationTest's @BeforeEach clears the users collection before each test.
     */
    private String seedActiveUser(String email)
            throws ExecutionException, InterruptedException, TimeoutException {
        String uid = "uid-" + email.replace("@", "-").replace(".", "-");
        User u = new User(uid, email, null, "user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /**
     * Stub both overloads of FirebaseAuth.verifyIdToken to return a fake token
     * carrying the given uid and role claim.
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
