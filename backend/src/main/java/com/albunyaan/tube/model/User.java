package com.albunyaan.tube.model;

import com.google.cloud.Timestamp;
import com.google.cloud.firestore.annotation.DocumentId;

/**
 * FIREBASE-MIGRATE-03: User Model (Firestore)
 *
 * Represents admin/moderator users in the system.
 * Firebase Authentication handles the actual auth, this stores additional metadata.
 *
 * Collection: users
 * Document ID: Firebase UID
 */
public class User {

    @DocumentId
    private String uid; // Firebase UID

    private String email;
    private String displayName;

    /**
     * Role: "admin" | "moderator"
     * This is mirrored in Firebase custom claims
     */
    private String role;

    /**
     * Account status: "active" | "inactive"
     */
    private String status;

    /**
     * Metadata
     */
    private Timestamp createdAt;
    private Timestamp updatedAt;
    private Timestamp lastLoginAt;
    private String createdBy; // UID of admin who created this user

    public User() {
        this.status = "active";
        this.role = "moderator";
        this.createdAt = Timestamp.now();
        this.updatedAt = Timestamp.now();
    }

    public User(String uid, String email, String displayName, String role) {
        this();
        this.uid = uid;
        this.email = email;
        this.displayName = displayName;
        this.role = role;
    }

    // Getters and Setters

    public String getUid() {
        return uid;
    }

    public void setUid(String uid) {
        this.uid = uid;
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        this.email = email;
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String getRole() {
        return role;
    }

    public void setRole(String role) {
        this.role = role;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Timestamp getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(Timestamp createdAt) {
        this.createdAt = createdAt;
    }

    public Timestamp getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(Timestamp updatedAt) {
        this.updatedAt = updatedAt;
    }

    public Timestamp getLastLoginAt() {
        return lastLoginAt;
    }

    public void setLastLoginAt(Timestamp lastLoginAt) {
        this.lastLoginAt = lastLoginAt;
    }

    public String getCreatedBy() {
        return createdBy;
    }

    public void setCreatedBy(String createdBy) {
        this.createdBy = createdBy;
    }

    public void touch() {
        this.updatedAt = Timestamp.now();
    }

    public void recordLogin() {
        this.lastLoginAt = Timestamp.now();
        touch();
    }

    public boolean isAdmin() {
        return "admin".equalsIgnoreCase(role);
    }

    public boolean isModerator() {
        return "moderator".equalsIgnoreCase(role);
    }

    public boolean isActive() {
        return "active".equals(status);
    }
}

