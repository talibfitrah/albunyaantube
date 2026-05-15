package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

import java.util.HashMap;
import java.util.Map;

/**
 * FIREBASE-MIGRATE-04: Audit Log Model (Firestore)
 *
 * Records all admin actions for audit trail.
 *
 * Collection: audit_logs
 * Document ID: Auto-generated
 */
public class AuditLog {

    @DocumentId
    private String id;

    /**
     * Action type: "user_created", "channel_approved", "category_updated", etc.
     */
    private String action;

    /**
     * Entity type: "user", "channel", "category", "playlist", "video"
     */
    private String entityType;

    /**
     * Entity ID (UID, channel ID, category ID, etc.)
     */
    private String entityId;

    /**
     * UID of the user who performed the action
     */
    private String actorUid;

    /**
     * Actor's display name (cached for easier display)
     */
    private String actorDisplayName;

    /**
     * Additional details about the action (JSON-like map)
     */
    private Map<String, Object> details;

    /**
     * Timestamp when the action occurred
     */
    private Timestamp timestamp;

    /**
     * IP address of the actor (optional)
     */
    private String ipAddress;

    public AuditLog() {
        this.timestamp = Timestamp.now();
        this.details = new HashMap<>();
    }

    public AuditLog(String action, String entityType, String entityId, String actorUid) {
        this();
        this.action = action;
        this.entityType = entityType;
        this.entityId = entityId;
        this.actorUid = actorUid;
    }

    // Getters and Setters

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getAction() {
        return action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getEntityType() {
        return entityType;
    }

    public void setEntityType(String entityType) {
        this.entityType = entityType;
    }

    public String getEntityId() {
        return entityId;
    }

    public void setEntityId(String entityId) {
        this.entityId = entityId;
    }

    public String getActorUid() {
        return actorUid;
    }

    public void setActorUid(String actorUid) {
        this.actorUid = actorUid;
    }

    public String getActorDisplayName() {
        return actorDisplayName;
    }

    public void setActorDisplayName(String actorDisplayName) {
        this.actorDisplayName = actorDisplayName;
    }

    public Map<String, Object> getDetails() {
        return details;
    }

    public void setDetails(Map<String, Object> details) {
        if (details == null) {
            this.details = new HashMap<>();
            return;
        }
        // Cubic R6 P2 — defensive sanitisation for every value passing through
        // setDetails / addDetail. Counterpart to MailService.sanitiseRecipient
        // ForAudit: CR/LF and other control chars in audit values can poison
        // line-oriented log shippers, CSV exports, and downstream incident
        // tooling that splits on \n. No current caller forwards user-supplied
        // strings into details, but the surface area (rejection notes, block
        // reasons, future request-changes payloads) is wide enough that the
        // first time someone DOES, we want this already wired.
        this.details = new HashMap<>();
        details.forEach((k, v) -> this.details.put(k, sanitiseDetailValue(v)));
    }

    public Timestamp getTimestamp() {
        return timestamp;
    }

    public void setTimestamp(Timestamp timestamp) {
        this.timestamp = timestamp;
    }

    public String getIpAddress() {
        return ipAddress;
    }

    public void setIpAddress(String ipAddress) {
        this.ipAddress = ipAddress;
    }

    public void addDetail(String key, Object value) {
        // Cubic R6 P2 — sanitise per setDetails javadoc.
        this.details.put(key, sanitiseDetailValue(value));
    }

    /**
     * Cubic R6 P2 — strip control chars (CR/LF/NUL/etc.) and cap length on
     * String values that flow into the {@code details} map. Non-String values
     * (numbers, booleans, nested maps/lists) are passed through unchanged;
     * Firestore serialises them losslessly without going through a CR/LF
     * sensitive code path. Package-private for unit-testability.
     *
     * <p>Limit ({@value #DETAIL_VALUE_MAX_LEN}) is generous — covers
     * block-reason notes, request-changes copy, and rejection rationale —
     * but bounds the worst-case audit row size. Truncated strings have
     * {@code "…"} appended so operators see the truncation.
     */
    static Object sanitiseDetailValue(Object v) {
        if (!(v instanceof String s)) return v;
        if (s == null) return null;
        String stripped = s.replaceAll("[\\p{Cntrl}]", "");
        if (stripped.length() > DETAIL_VALUE_MAX_LEN) {
            return stripped.substring(0, DETAIL_VALUE_MAX_LEN) + "…";
        }
        return stripped;
    }

    static final int DETAIL_VALUE_MAX_LEN = 1024;

    /**
     * Factory method for creating AuditLog instances with all fields set.
     * Convenience for transactional audit logging in AuthService.
     */
    public static AuditLog of(String action, String entityType, String entityId,
                              String actorUid, Map<String, Object> details) {
        AuditLog log = new AuditLog();
        log.setAction(action);
        log.setEntityType(entityType);
        log.setEntityId(entityId);
        log.setActorUid(actorUid);
        log.setTimestamp(Timestamp.now());
        if (details != null) {
            details.forEach(log::addDetail);
        }
        return log;
    }
}

