package com.albunyaan.tube.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.time.Instant;
import java.util.Base64;
import java.util.Map;

/**
 * Plan F (ADMIN-USER-01, F8) — opaque base64url cursor for audit log pagination.
 * Encodes {"ts": ISO-8601, "id": Firestore document id} for Firestore's
 * .startAfter(documentSnapshot) tiebreak.
 */
public final class AuditCursor {
    private static final ObjectMapper M = new ObjectMapper();

    private AuditCursor() {}

    public static String encode(Instant ts, String docId) {
        try {
            byte[] json = M.writeValueAsBytes(Map.of("ts", ts.toString(), "id", docId));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(json);
        } catch (Exception e) {
            throw new IllegalStateException("Cursor encode failed", e);
        }
    }

    public static Decoded decode(String cursor) {
        try {
            byte[] bytes = Base64.getUrlDecoder().decode(cursor);
            Map<String, String> m = M.readValue(bytes, new TypeReference<>() {});
            String ts = m.get("ts");
            String id = m.get("id");
            if (ts == null || id == null) {
                throw new IllegalArgumentException("Cursor missing ts or id field");
            }
            return new Decoded(Instant.parse(ts), id);
        } catch (IllegalArgumentException e) {
            throw e;
        } catch (Exception e) {
            throw new IllegalArgumentException("Invalid cursor", e);
        }
    }

    public record Decoded(Instant ts, String docId) {}
}
