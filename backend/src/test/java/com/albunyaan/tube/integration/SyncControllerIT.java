package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.sync.PutSubscriptionRequest;
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

import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan D T10 — SyncController happy-path integration test against Firestore emulator.
 *
 * Verifies the full pull/push cycle: PUT → GET shows the row;
 * DELETE → GET shows the tombstone; cursor advance skips already-seen rows.
 *
 * Auth is mocked via @MockBean FirebaseAuth — the Firebase auth emulator is NOT used.
 * Each test seeds a fresh ACTIVE user via userRepository so the FirebaseAuthFilter
 * can resolve a FirebaseUserDetails principal from the stubbed token.
 *
 * Subcollection cleanup (users/{uid}/subscriptions etc.) is handled automatically:
 * BaseIntegrationTest.setUpFirestore() wipes the top-level "users" collection, and
 * the Firestore emulator cascades the delete into all subcollections.
 */
class SyncControllerIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Autowired
    ObjectMapper json;

    // ── Test 1: PUT then GET returns the upserted subscription ───────────────

    @Test
    void putThenGetReturnsTheUpsertedSubscription() throws Exception {
        String uid = seedActiveUser("alice@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest put = new PutSubscriptionRequest();
        put.setChannelUrl("https://yt/UCabc");
        put.setName("Sample");
        put.setSubscribedAt(1L);

        mvc.perform(put("/api/account/subscriptions/UCabc")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(put)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.entityId").value("UCabc"))
                .andExpect(jsonPath("$.deleted").value(false));

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[0].entityId").value("UCabc"))
                .andExpect(jsonPath("$.subscriptions.items[0].name").value("Sample"));
    }

    // ── Test 2: DELETE emits tombstone on next sync ───────────────────────────

    @Test
    void deleteEmitsTombstoneOnNextSync() throws Exception {
        String uid = seedActiveUser("bob@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest put = new PutSubscriptionRequest();
        put.setChannelUrl("u");
        put.setName("n");
        put.setSubscribedAt(0L);

        mvc.perform(put("/api/account/subscriptions/UCdel")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(put)))
                .andExpect(status().isOk());

        mvc.perform(delete("/api/account/subscriptions/UCdel")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk());

        mvc.perform(get("/api/account/sync").header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items[?(@.entityId=='UCdel')].deleted")
                        .value(true));
    }

    // ── Test 3: Cursor advances past previously-pulled rows ──────────────────

    @Test
    void cursorAdvancesPastPreviouslyPulledRows() throws Exception {
        String uid = seedActiveUser("carol@test");
        stubAuthAs(uid, "user");

        PutSubscriptionRequest put = new PutSubscriptionRequest();
        put.setChannelUrl("u");
        put.setName("first");
        put.setSubscribedAt(0L);

        mvc.perform(put("/api/account/subscriptions/UC1")
                        .header("Authorization", "Bearer fake")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(json.writeValueAsString(put)))
                .andExpect(status().isOk());

        // First pull — note the updatedAt of the row as our cursor
        String body = mvc.perform(get("/api/account/sync")
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        long cursor = json.readTree(body)
                .path("subscriptions").path("items").get(0).path("updatedAt").asLong();

        // Second pull with cursor: the row is at exactly `cursor`, so strict > means 0 results
        mvc.perform(get("/api/account/sync")
                        .param("subs", String.valueOf(cursor))
                        .header("Authorization", "Bearer fake"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subscriptions.items").isEmpty());
    }

    // ── Helpers ───────────────────────────────────────────────────────────────

    /**
     * Persist a minimal ACTIVE user to the Firestore emulator.
     * UID is deterministic from the email so test isolation is predictable —
     * BaseIntegrationTest's @BeforeEach clears the users collection before each test.
     */
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

    /**
     * Stub both overloads of FirebaseAuth.verifyIdToken to return a fake token
     * carrying the given uid and role claim. Any bearer string matches so all
     * requests within a test share the same stub without cross-test leakage.
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
