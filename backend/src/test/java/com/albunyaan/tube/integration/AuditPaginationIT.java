package com.albunyaan.tube.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.test.web.servlet.MvcResult;

import java.time.Instant;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Plan F (ADMIN-USER-01) — cursor pagination walks 250 rows in 5 pages of 50.
 * No duplicates, no omissions, last page returns null cursor.
 */
class AuditPaginationIT extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void walk250Rows_5pages_noDupesNoOmissions() throws Exception {
        String adminUid = "admin-pagination-uid";
        stubAuthAs(adminUid, "admin");

        // Seed 250 rows with strictly-decreasing timestamps (newest first).
        Instant base = Instant.parse("2026-05-12T00:00:00Z");
        for (int i = 0; i < 250; i++) {
            Map<String, Object> doc = Map.of(
                    "action",     "TEST_PAGINATION",
                    "entityType", "user",
                    "entityId",   "u-" + i,
                    "actorUid",   adminUid,
                    "timestamp",  Timestamp.ofTimeSecondsAndNanos(
                            base.minusSeconds(i).getEpochSecond(), 0)
            );
            firestore.collection("audit_logs").add(doc).get();
        }

        Set<String> seenIds = new HashSet<>();
        String cursor = null;
        int pages = 0;
        ObjectMapper jsonM = new ObjectMapper();

        do {
            String url = "/api/admin/audit/action/TEST_PAGINATION?limit=50"
                    + (cursor != null ? "&cursor=" + cursor : "");
            MvcResult res = mvc.perform(get(url)
                            .header("Authorization", "Bearer fake"))
                    .andExpect(status().isOk())
                    .andReturn();

            JsonNode body = jsonM.readTree(res.getResponse().getContentAsString());
            JsonNode items = body.get("items");
            assertNotNull(items);
            items.forEach(n -> assertTrue(seenIds.add(n.get("entityId").asText()),
                    "duplicate entityId: " + n.get("entityId").asText()));
            cursor = body.hasNonNull("nextCursor") ? body.get("nextCursor").asText() : null;
            pages++;
        } while (cursor != null && pages < 10);

        assertEquals(5, pages, "expected 5 pages of 50");
        assertEquals(250, seenIds.size(), "expected 250 unique ids across all pages");
        assertNull(cursor, "last page should return null nextCursor");
    }

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
}
