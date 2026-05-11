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

    // Block lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp blockedAt;
    private String blockedBy;
    private String blockReason;

    // Soft-delete lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp deletedAt;
    private String deletedBy;
    private String deleteReason;

    // Recovery lifecycle (BACKEND-ACCT-FOUND)
    private Timestamp recoveredAt;
    private String recoveredBy;

    // Profile completion (Plan C will populate; Plan A only stores)
    private Timestamp profileCompletedAt;

    // Plan C T1: profile bootstrap captures DOB; stored as Firestore Timestamp at 00:00 UTC.
    private Timestamp dateOfBirth;

    public User() {
        // F3: role MUST default to null, not "moderator".
        // Firestore's @DocumentId path calls the no-arg ctor and then copies fields
        // over. A document missing the "role" field used to land as role="moderator"
        // — a silent privilege grant. With null here, missing-role docs are caught
        // by the migration's null-or-blank guard and explicitly clamped to "user".
        // Code paths that read role must handle null gracefully:
        //   - isAdmin() / isModerator() return false (string-equals on null)
        //   - getRoleEnum() returns Role.USER via Role.fromString(null)
        this.status = "active";
        this.role = null;
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

    public Role getRoleEnum() {
        return Role.fromString(role);
    }

    public void setRoleEnum(Role role) {
        this.role = role.getValue();
    }

    public UserStatus getStatusEnum() {
        return UserStatus.fromString(status);
    }

    public void setStatusEnum(UserStatus status) {
        this.status = status.getValue();
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
        return UserStatus.ACTIVE == getStatusEnum();
    }

    public boolean isBlocked() {
        return UserStatus.BLOCKED == getStatusEnum();
    }

    public boolean isDeleted() {
        return UserStatus.DELETED == getStatusEnum();
    }

    public boolean isPendingProfile() {
        return UserStatus.PENDING_PROFILE == getStatusEnum();
    }

    public void recordBlock(String byUid, String reason) {
        this.status = UserStatus.BLOCKED.getValue();
        this.blockedAt = Timestamp.now();
        this.blockedBy = byUid;
        this.blockReason = reason;
        touch();
    }

    // byUid is captured by AuditLogService.buildUnblock (Task 9, D5) — the
    // audit trail lives in the auditLogs collection, not on the User doc.
    // The parameter stays here for API symmetry with recordBlock.
    public void recordUnblock(String byUid) {
        this.status = UserStatus.ACTIVE.getValue();
        this.blockedAt = null;
        this.blockedBy = null;
        this.blockReason = null;
        touch();
    }

    public void recordSoftDelete(String byUid, String reason) {
        this.status = UserStatus.DELETED.getValue();
        this.deletedAt = Timestamp.now();
        this.deletedBy = byUid;
        this.deleteReason = reason;
        touch();
    }

    /**
     * F8: recover ALSO clears any leftover block metadata.
     *
     * Without this clear, a user who was blocked → soft-deleted → recovered
     * would end up status="active" but still carry the prior blockedAt /
     * blockedBy / blockReason values from the block lifecycle. That left the
     * document internally inconsistent: isBlocked() returned false (status is
     * active) while block audit fields were still populated.
     *
     * The audit trail in the auditLogs collection is the durable source of
     * truth for "this user was ever blocked"; the User doc only mirrors the
     * CURRENT state, so wiping block metadata here is the right shape.
     */
    public void recordRecover(String byUid) {
        this.status = UserStatus.ACTIVE.getValue();
        this.deletedAt = null;
        this.deletedBy = null;
        this.deleteReason = null;
        // F8: defence in depth — clear any block metadata that survived a
        // block→softDelete→recover path. AuthService.softDeleteUser also
        // refuses to soft-delete a blocked user, so this path should be
        // rare, but clearing here keeps the User doc consistent if any
        // pre-F8 data slipped through.
        this.blockedAt = null;
        this.blockedBy = null;
        this.blockReason = null;
        this.recoveredAt = Timestamp.now();
        this.recoveredBy = byUid;
        touch();
    }

    public Timestamp getBlockedAt() { return blockedAt; }
    public void setBlockedAt(Timestamp t) { this.blockedAt = t; }
    public String getBlockedBy() { return blockedBy; }
    public void setBlockedBy(String s) { this.blockedBy = s; }
    public String getBlockReason() { return blockReason; }
    public void setBlockReason(String s) { this.blockReason = s; }

    public Timestamp getDeletedAt() { return deletedAt; }
    public void setDeletedAt(Timestamp t) { this.deletedAt = t; }
    public String getDeletedBy() { return deletedBy; }
    public void setDeletedBy(String s) { this.deletedBy = s; }
    public String getDeleteReason() { return deleteReason; }
    public void setDeleteReason(String s) { this.deleteReason = s; }

    public Timestamp getRecoveredAt() { return recoveredAt; }
    public void setRecoveredAt(Timestamp t) { this.recoveredAt = t; }
    public String getRecoveredBy() { return recoveredBy; }
    public void setRecoveredBy(String s) { this.recoveredBy = s; }

    public Timestamp getProfileCompletedAt() { return profileCompletedAt; }
    public void setProfileCompletedAt(Timestamp t) { this.profileCompletedAt = t; }

    public Timestamp getDateOfBirth() { return dateOfBirth; }
    public void setDateOfBirth(Timestamp t) { this.dateOfBirth = t; }

    /**
     * F10: return a defensive copy of this User.
     *
     * UserRepository.findByUid is @Cacheable("userStatus") — without a copy,
     * the cache hands back the same mutable instance to every caller. Any
     * caller that mutates the returned User (notably AuthService.recordLogin
     * which calls user.recordLogin() then save) corrupts the cached object,
     * so the next concurrent lookup sees torn / stale state.
     *
     * Timestamps from com.google.cloud are immutable value objects, so a
     * shallow copy is safe — references can be shared. Only the User shell
     * itself must be cloned.
     */
    public User copy() {
        User c = new User();
        c.uid = this.uid;
        c.email = this.email;
        c.displayName = this.displayName;
        c.role = this.role;
        c.status = this.status;
        c.createdAt = this.createdAt;
        c.updatedAt = this.updatedAt;
        c.lastLoginAt = this.lastLoginAt;
        c.createdBy = this.createdBy;
        c.blockedAt = this.blockedAt;
        c.blockedBy = this.blockedBy;
        c.blockReason = this.blockReason;
        c.deletedAt = this.deletedAt;
        c.deletedBy = this.deletedBy;
        c.deleteReason = this.deleteReason;
        c.recoveredAt = this.recoveredAt;
        c.recoveredBy = this.recoveredBy;
        c.profileCompletedAt = this.profileCompletedAt;
        c.dateOfBirth = this.dateOfBirth;
        return c;
    }
}

