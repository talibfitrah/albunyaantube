package com.albunyaan.tube.util;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.DocumentSnapshot;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Utility class for encoding and decoding Firestore cursor tokens.
 *
 * Cursors are opaque base64-encoded JSON strings containing:
 * - Document ID (for document lookup and tiebreaker)
 * - Ordering field values (for Firestore startAfter())
 *
 * This enables deterministic, resumable pagination across Firestore queries.
 */
public class CursorUtils {

    private static final ObjectMapper objectMapper = new ObjectMapper();

    /**
     * Cursor data structure for encoding/decoding.
     */
    public static class CursorData {
        private String id;
        private Map<String, Object> fields;

        public CursorData() {
            this.fields = new LinkedHashMap<>();
        }

        public CursorData(String id) {
            this.id = id;
            this.fields = new LinkedHashMap<>();
        }

        public String getId() {
            return id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public Map<String, Object> getFields() {
            return fields;
        }

        public void setFields(Map<String, Object> fields) {
            this.fields = fields;
        }

        public CursorData withField(String name, Object value) {
            // Convert Firestore Timestamp to milliseconds for JSON serialization
            if (value instanceof Timestamp) {
                this.fields.put(name, ((Timestamp) value).toDate().getTime());
            } else {
                this.fields.put(name, value);
            }
            return this;
        }

        public Object getField(String name) {
            return fields.get(name);
        }

        public Long getFieldAsLong(String name) {
            Object value = fields.get(name);
            if (value == null) return null;
            if (value instanceof Number) {
                return ((Number) value).longValue();
            }
            return Long.parseLong(value.toString());
        }

        public String getFieldAsString(String name) {
            Object value = fields.get(name);
            return value != null ? value.toString() : null;
        }

        public Timestamp getFieldAsTimestamp(String name) {
            Long millis = getFieldAsLong(name);
            if (millis == null) return null;
            return Timestamp.ofTimeSecondsAndNanos(millis / 1000, (int) ((millis % 1000) * 1_000_000));
        }
    }

    /**
     * Encode cursor data to an opaque URL-safe base64 string.
     *
     * @param cursorData The cursor data containing document ID and ordering fields
     * @return URL-safe base64 encoded cursor string
     */
    public static String encode(CursorData cursorData) {
        if (cursorData == null || cursorData.getId() == null) {
            return null;
        }
        try {
            String json = objectMapper.writeValueAsString(cursorData);
            return Base64.getUrlEncoder().withoutPadding()
                    .encodeToString(json.getBytes(StandardCharsets.UTF_8));
        } catch (JsonProcessingException e) {
            throw new IllegalArgumentException("Failed to encode cursor", e);
        }
    }

    /**
     * Decode an opaque cursor string back to cursor data.
     *
     * @param cursor The base64 encoded cursor string
     * @return Decoded cursor data, or null if cursor is null/empty
     * @throws IllegalArgumentException if cursor format is invalid
     */
    public static CursorData decode(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return null;
        }
        try {
            byte[] decoded = Base64.getUrlDecoder().decode(cursor);
            String json = new String(decoded, StandardCharsets.UTF_8);
            return objectMapper.readValue(json, CursorData.class);
        } catch (IllegalArgumentException | IOException e) {
            throw new IllegalArgumentException("Invalid cursor format: " + cursor, e);
        }
    }

    /**
     * Create cursor data from a Firestore DocumentSnapshot.
     *
     * @param snapshot The document snapshot
     * @param orderingFields The field names used for ordering (in order)
     * @return CursorData populated with document ID and ordering field values
     */
    public static CursorData fromSnapshot(DocumentSnapshot snapshot, String... orderingFields) {
        if (snapshot == null || !snapshot.exists()) {
            return null;
        }

        CursorData cursorData = new CursorData(snapshot.getId());

        for (String field : orderingFields) {
            Object value = snapshot.get(field);
            if (value != null) {
                cursorData.withField(field, value);
            }
        }

        return cursorData;
    }

    /**
     * Encode a cursor from a Firestore DocumentSnapshot.
     *
     * @param snapshot The document snapshot
     * @param orderingFields The field names used for ordering
     * @return Encoded cursor string
     */
    public static String encodeFromSnapshot(DocumentSnapshot snapshot, String... orderingFields) {
        return encode(fromSnapshot(snapshot, orderingFields));
    }

    /**
     * Create a simple cursor containing only the document ID.
     * Use this for simple pagination where document ID is the only ordering field.
     *
     * @param documentId The document ID
     * @return CursorData with just the ID
     */
    public static CursorData fromDocumentId(String documentId) {
        if (documentId == null || documentId.isEmpty()) {
            return null;
        }
        return new CursorData(documentId);
    }

    /**
     * Encode a simple cursor from just a document ID.
     *
     * @param documentId The document ID
     * @return Encoded cursor string
     */
    public static String encodeFromDocumentId(String documentId) {
        return encode(fromDocumentId(documentId));
    }

    /**
     * Get the ordering field values as an array for use with Firestore startAfter().
     * Values are returned in the same order as the field names provided during encoding.
     *
     * @param cursorData The decoded cursor data
     * @param orderingFields The field names in order
     * @return Array of field values for startAfter(), or null if cursor is null
     */
    public static Object[] getStartAfterValues(CursorData cursorData, String... orderingFields) {
        if (cursorData == null) {
            return null;
        }

        Object[] values = new Object[orderingFields.length];
        for (int i = 0; i < orderingFields.length; i++) {
            String field = orderingFields[i];
            Object value = cursorData.getField(field);

            // Convert timestamp milliseconds back to Firestore Timestamp
            if (value instanceof Number &&
                (field.contains("At") || field.contains("Time") || field.contains("Date"))) {
                values[i] = cursorData.getFieldAsTimestamp(field);
            } else {
                values[i] = value;
            }
        }

        return values;
    }

    /**
     * Check if a cursor string is valid (non-null, non-empty, and decodable).
     *
     * @param cursor The cursor string to validate
     * @return true if cursor is valid and can be decoded
     */
    public static boolean isValid(String cursor) {
        if (cursor == null || cursor.isEmpty()) {
            return false;
        }
        try {
            CursorData data = decode(cursor);
            return data != null && data.getId() != null && !data.getId().isEmpty();
        } catch (IllegalArgumentException e) {
            return false;
        }
    }
}
