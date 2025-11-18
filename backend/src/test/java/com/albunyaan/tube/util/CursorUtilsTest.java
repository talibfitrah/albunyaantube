package com.albunyaan.tube.util;

import com.google.cloud.Timestamp;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Unit tests for CursorUtils encoding and decoding.
 */
class CursorUtilsTest {

    @Test
    void encode_withDocumentIdOnly_returnsOpaqueString() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc123");

        String encoded = CursorUtils.encode(data);

        assertNotNull(encoded);
        assertFalse(encoded.isEmpty());
        // Should be URL-safe base64 (no +, /, or =)
        assertFalse(encoded.contains("+"));
        assertFalse(encoded.contains("/"));
        assertFalse(encoded.contains("="));
    }

    @Test
    void decode_validCursor_returnsCursorData() {
        CursorUtils.CursorData original = new CursorUtils.CursorData("doc123");
        String encoded = CursorUtils.encode(original);

        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);

        assertNotNull(decoded);
        assertEquals("doc123", decoded.getId());
    }

    @Test
    void roundTrip_withOrderingFields_preservesAllData() {
        CursorUtils.CursorData original = new CursorUtils.CursorData("doc456")
                .withField("createdAt", 1700000000000L)
                .withField("subscribers", 50000L)
                .withField("name", "Test Channel");

        String encoded = CursorUtils.encode(original);
        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);

        assertEquals("doc456", decoded.getId());
        assertEquals(1700000000000L, decoded.getFieldAsLong("createdAt"));
        assertEquals(50000L, decoded.getFieldAsLong("subscribers"));
        assertEquals("Test Channel", decoded.getFieldAsString("name"));
    }

    @Test
    void roundTrip_withTimestamp_convertsToMillis() {
        Timestamp ts = Timestamp.ofTimeSecondsAndNanos(1700000000, 500_000_000);
        CursorUtils.CursorData original = new CursorUtils.CursorData("doc789")
                .withField("createdAt", ts);

        String encoded = CursorUtils.encode(original);
        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);

        Timestamp restored = decoded.getFieldAsTimestamp("createdAt");
        assertNotNull(restored);
        // Verify timestamp is within 1 second (millisecond precision)
        assertEquals(ts.getSeconds(), restored.getSeconds());
    }

    @Test
    void encode_nullCursorData_returnsNull() {
        assertNull(CursorUtils.encode(null));
    }

    @Test
    void encode_nullId_returnsNull() {
        CursorUtils.CursorData data = new CursorUtils.CursorData();
        assertNull(CursorUtils.encode(data));
    }

    @Test
    void decode_nullCursor_returnsNull() {
        assertNull(CursorUtils.decode(null));
    }

    @Test
    void decode_emptyCursor_returnsNull() {
        assertNull(CursorUtils.decode(""));
    }

    @Test
    void decode_invalidBase64_throwsException() {
        assertThrows(IllegalArgumentException.class, () -> {
            CursorUtils.decode("not-valid-base64!!!");
        });
    }

    @Test
    void decode_invalidJson_throwsException() {
        // Valid base64 but not valid JSON
        String invalidJson = java.util.Base64.getUrlEncoder()
                .encodeToString("not json".getBytes());

        assertThrows(IllegalArgumentException.class, () -> {
            CursorUtils.decode(invalidJson);
        });
    }

    @Test
    void encodeFromDocumentId_simpleId_works() {
        String encoded = CursorUtils.encodeFromDocumentId("simple-doc-id");

        assertNotNull(encoded);
        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);
        assertEquals("simple-doc-id", decoded.getId());
    }

    @Test
    void encodeFromDocumentId_nullId_returnsNull() {
        assertNull(CursorUtils.encodeFromDocumentId(null));
    }

    @Test
    void encodeFromDocumentId_emptyId_returnsNull() {
        assertNull(CursorUtils.encodeFromDocumentId(""));
    }

    @Test
    void getStartAfterValues_withFields_returnsOrderedArray() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc")
                .withField("createdAt", 1700000000000L)
                .withField("subscribers", 1000L);

        Object[] values = CursorUtils.getStartAfterValues(data, "createdAt", "subscribers");

        assertNotNull(values);
        assertEquals(2, values.length);
        // createdAt should be converted to Timestamp
        assertTrue(values[0] instanceof Timestamp);
        assertEquals(1000L, values[1]);
    }

    @Test
    void getStartAfterValues_nullCursorData_returnsNull() {
        assertNull(CursorUtils.getStartAfterValues(null, "field"));
    }

    @Test
    void isValid_validCursor_returnsTrue() {
        String cursor = CursorUtils.encodeFromDocumentId("valid-id");
        assertTrue(CursorUtils.isValid(cursor));
    }

    @Test
    void isValid_nullCursor_returnsFalse() {
        assertFalse(CursorUtils.isValid(null));
    }

    @Test
    void isValid_emptyCursor_returnsFalse() {
        assertFalse(CursorUtils.isValid(""));
    }

    @Test
    void isValid_invalidCursor_returnsFalse() {
        assertFalse(CursorUtils.isValid("invalid-cursor-format"));
    }

    @Test
    void isValid_cursorWithNullId_returnsFalse() {
        // Create a cursor with null ID manually
        String invalidCursor = java.util.Base64.getUrlEncoder()
                .encodeToString("{\"id\":null,\"fields\":{}}".getBytes());
        assertFalse(CursorUtils.isValid(invalidCursor));
    }

    @Test
    void multipleEncodings_sameData_producesSameResult() {
        CursorUtils.CursorData data1 = new CursorUtils.CursorData("doc")
                .withField("field1", "value1");
        CursorUtils.CursorData data2 = new CursorUtils.CursorData("doc")
                .withField("field1", "value1");

        String encoded1 = CursorUtils.encode(data1);
        String encoded2 = CursorUtils.encode(data2);

        assertEquals(encoded1, encoded2);
    }

    @Test
    void cursorData_withField_chainsCorrectly() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc")
                .withField("a", 1)
                .withField("b", 2)
                .withField("c", 3);

        assertEquals(1, data.getFieldAsLong("a"));
        assertEquals(2, data.getFieldAsLong("b"));
        assertEquals(3, data.getFieldAsLong("c"));
    }

    @Test
    void getFieldAsLong_stringValue_parsesCorrectly() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc")
                .withField("number", "12345");

        assertEquals(12345L, data.getFieldAsLong("number"));
    }

    @Test
    void getFieldAsLong_nullField_returnsNull() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc");
        assertNull(data.getFieldAsLong("nonexistent"));
    }

    @Test
    void getFieldAsString_variousTypes_convertsCorrectly() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc")
                .withField("number", 123)
                .withField("bool", true)
                .withField("string", "text");

        assertEquals("123", data.getFieldAsString("number"));
        assertEquals("true", data.getFieldAsString("bool"));
        assertEquals("text", data.getFieldAsString("string"));
    }

    @Test
    void encode_specialCharactersInId_handlesCorrectly() {
        // Firestore IDs can contain various characters
        String specialId = "doc_with-special.chars~123";
        CursorUtils.CursorData data = new CursorUtils.CursorData(specialId);

        String encoded = CursorUtils.encode(data);
        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);

        assertEquals(specialId, decoded.getId());
    }

    @Test
    void encode_unicodeInFields_handlesCorrectly() {
        CursorUtils.CursorData data = new CursorUtils.CursorData("doc")
                .withField("name", "القناة العربية"); // Arabic text

        String encoded = CursorUtils.encode(data);
        CursorUtils.CursorData decoded = CursorUtils.decode(encoded);

        assertEquals("القناة العربية", decoded.getFieldAsString("name"));
    }
}
