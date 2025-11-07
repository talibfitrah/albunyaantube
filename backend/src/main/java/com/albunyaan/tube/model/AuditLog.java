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
        this.details = details;
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
        this.details.put(key, value);
    }
}

