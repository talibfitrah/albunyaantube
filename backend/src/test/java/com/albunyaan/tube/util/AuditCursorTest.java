package com.albunyaan.tube.util;

import org.junit.jupiter.api.Test;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.*;

class AuditCursorTest {

    @Test
    void encodeThenDecode_returnsOriginalValues() {
        Instant ts = Instant.parse("2026-05-12T10:15:30.123Z");
        String docId = "abc-123";

        String cursor = AuditCursor.encode(ts, docId);
        AuditCursor.Decoded out = AuditCursor.decode(cursor);

        assertEquals(ts, out.ts());
        assertEquals(docId, out.docId());
    }

    @Test
    void encode_producesUrlSafeBase64() {
        Instant ts = Instant.parse("2026-05-12T10:15:30.123Z");
        String cursor = AuditCursor.encode(ts, "abc-123");
        assertFalse(cursor.contains("+"));
        assertFalse(cursor.contains("/"));
        assertFalse(cursor.contains("="));
    }

    @Test
    void decode_malformedBase64_throwsIllegalArgument() {
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode("!!!not base64!!!"));
    }

    @Test
    void decode_validBase64ButNotJson_throwsIllegalArgument() {
        String junk = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("not json".getBytes());
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode(junk));
    }

    @Test
    void decode_missingFields_throwsIllegalArgument() {
        String missing = java.util.Base64.getUrlEncoder().withoutPadding()
                .encodeToString("{\"ts\":\"2026-05-12T10:00:00Z\"}".getBytes());
        assertThrows(IllegalArgumentException.class, () -> AuditCursor.decode(missing));
    }
}
