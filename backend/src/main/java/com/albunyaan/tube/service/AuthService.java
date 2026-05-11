package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.model.Role;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.stereotype.Service;

import jakarta.annotation.PostConstruct;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/**
 * FIREBASE-MIGRATE-02: Authentication Service
 *
 * Manages user creation, role assignment, and Firebase Authentication integration.
 * Syncs user data between Firebase Auth and Firestore.
 */
@Service
public class AuthService {

    private static final Logger logger = LoggerFactory.getLogger(AuthService.class);

    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    // Injected here for Tasks 6/7/8: transactional lifecycle methods need all five.
    private final AuditLogService auditLogService;
    private final AuditLogRepository auditLogRepository;
    private final Firestore firestore;
    private final CacheManager cacheManager;
    private final FirestoreTimeoutProperties timeoutProperties;

    @Value("${app.security.initial-admin.email}")
    private String initialAdminEmail;

    @Value("${app.security.initial-admin.password}")
    private String initialAdminPassword;

    @Value("${app.security.initial-admin.display-name}")
    private String initialAdminDisplayName;

    public AuthService(FirebaseAuth firebaseAuth,
                       UserRepository userRepository,
                       AuditLogService auditLogService,
                       AuditLogRepository auditLogRepository,
                       Firestore firestore,
                       CacheManager cacheManager,
                       FirestoreTimeoutProperties timeoutProperties) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.firestore = firestore;
        this.cacheManager = cacheManager;
        this.timeoutProperties = timeoutProperties;
    }

    /**
     * Unwraps ExecutionException from firestore.runTransaction(...).get() so that
     * domain exceptions (LastAdminException, IllegalArgumentException, IllegalStateException)
     * propagate to @ControllerAdvice for proper HTTP status mapping. Without this, every
     * lifecycle method's spec'd 409/400/409 handler is unreachable.
     */
    private <T> T runLifecycleTx(Transaction.Function<T> fn) throws Exception {
        try {
            return firestore.runTransaction(fn)
                    .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof LastAdminException) throw (LastAdminException) cause;
            if (cause instanceof IllegalArgumentException) throw (IllegalArgumentException) cause;
            if (cause instanceof IllegalStateException) throw (IllegalStateException) cause;
            if (cause instanceof RuntimeException) throw (RuntimeException) cause;
            throw e;
        }
    }

    /**
     * Create initial admin user on application startup
     */
    @PostConstruct
    public void initializeAdmin() {
        try {
            // Check if admin already exists
            UserRecord existingUser = null;
            try {
                existingUser = firebaseAuth.getUserByEmail(initialAdminEmail);
            } catch (FirebaseAuthException e) {
                // User doesn't exist, this is expected for first run
            }

            if (existingUser == null) {
                logger.info("Creating initial admin user: {}", initialAdminEmail);
                // Use canonical lowercase value (D6). Literal "ADMIN" used to land in
                // Firestore as-is, which made the (role,status) admin-count query miss
                // the initial admin → last-admin guard fired wrong.
                createUser(initialAdminEmail, initialAdminPassword, initialAdminDisplayName,
                        Role.ADMIN.getValue(), null);
                logger.info("Initial admin user created successfully");
            } else {
                logger.info("Initial admin user already exists: {}", initialAdminEmail);
            }
        } catch (Exception e) {
            logger.error("Failed to initialize admin user", e);
            // Don't throw - allow application to start
        }
    }

    /**
     * Create a new user in Firebase Auth and Firestore.
     *
     * D6: the role is normalised to canonical lowercase BEFORE both the Firestore
     * write and the Firebase Auth custom-claim write. Anything else makes the
     * (role,status) admin-count query miss legitimate admins.
     */
    public User createUser(String email, String password, String displayName, String role, String createdByUid)
            throws FirebaseAuthException, ExecutionException, InterruptedException, TimeoutException {

        // Canonical lowercase role (D6) — single source of truth for downstream writes.
        String canonicalRole = Role.fromString(role).getValue();

        // Create user in Firebase Authentication
        UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                .setEmail(email)
                .setPassword(password)
                .setDisplayName(displayName)
                .setEmailVerified(false);

        UserRecord userRecord = firebaseAuth.createUser(request);
        String uid = userRecord.getUid();

        // Set custom claims for role-based access (canonical lowercase)
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", canonicalRole);
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Create user document in Firestore — canonical lowercase role
        User user = new User(uid, email, displayName, canonicalRole);
        user.setCreatedBy(createdByUid);
        userRepository.save(user);

        logger.info("Created user: {} (uid: {}) with role: {}", email, uid, canonicalRole);
        return user;
    }

    /**
     * Update user role with actor context: transactional Firestore update + audit log,
     * then Firebase Auth claims update outside the tx (D9), and cache eviction (D4).
     * Enforces the last-admin guard (D2) inline inside the transaction.
     */
    public User updateUserRoleAsActor(String uid, String newRoleStr, String actorUid)
            throws Exception {
        Role newRole = Role.fromString(newRoleStr);
        final String[] previousRole = new String[1];

        User updated = runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new IllegalArgumentException("User not found: " + uid);
            }
            User target = snap.toObject(User.class);
            previousRole[0] = target.getRole();

            // Last-admin guard (D2) — inline, transactional
            if (target.isAdmin() && newRole != Role.ADMIN) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot demote themselves. Ask another admin.");
                }
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot demote the last active admin.");
                }
            }

            target.setRole(newRole.getValue());
            target.setUpdatedAt(com.google.cloud.Timestamp.now());

            AuditLog audit = auditLogService.buildRoleChange(uid, actorUid,
                    previousRole[0], newRole.getValue());

            tx.set(userRef, target);
            tx.set(auditLogRepository.auditLogsCollection().document(), audit);
            return target;
        });

        // Update Firebase Auth custom claims OUTSIDE the tx (D9, D6 lowercase)
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", newRole.getValue());
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Cache eviction (D4)
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);

        logger.info("Role changed: uid={} from={} to={} actor={}",
                uid, previousRole[0], newRole.getValue(), actorUid);

        return updated;
    }

    /**
     * @deprecated Legacy facade preserved for frontend backwards-compatibility. New callers
     * should invoke {@link #blockUser}, {@link #unblockUser}, {@link #softDeleteUser}, or
     * {@link #recoverUser} directly. This facade DELEGATES to those methods so that every
     * lifecycle safeguard (D2 last-admin guard, D4 cache eviction, D5 audit log,
     * D9 Firebase Auth sync, transactional consistency) is enforced. It used to bypass
     * all of them — review-pipeline finding F1.
     *
     * Accepted status values:
     *   "blocked" → delegates to {@link #blockUser} (reason required)
     *   "active"  → if currently BLOCKED, delegates to {@link #unblockUser};
     *               if currently DELETED, delegates to {@link #recoverUser};
     *               otherwise no-op (returns current user unchanged)
     *   "deleted" → delegates to {@link #softDeleteUser} (reason required)
     *
     * @param uid      target user UID
     * @param status   new lifecycle status — one of "blocked" | "active" | "deleted"
     * @param actorUid actor UID for audit + self-action guards
     * @param reason   required for "blocked" and "deleted"; ignored for "active"
     * @throws IllegalArgumentException if {@code status} is null, blank, unknown, or
     *         a transition requires a {@code reason} that wasn't provided
     */
    @Deprecated
    public User updateUserStatus(String uid, String status, String actorUid, String reason)
            throws Exception {
        if (status == null || status.isBlank()) {
            throw new IllegalArgumentException("status is required");
        }
        String normalized = status.trim().toLowerCase(java.util.Locale.ROOT);

        switch (normalized) {
            case "blocked" -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason is required when status=blocked");
                }
                blockUser(uid, actorUid, reason);
            }
            case "deleted" -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason is required when status=deleted");
                }
                softDeleteUser(uid, actorUid, reason);
            }
            case "active" -> {
                User current = userRepository.findByUid(uid)
                        .orElseThrow(() -> new IllegalArgumentException("User not found: " + uid));
                if (current.isBlocked()) {
                    unblockUser(uid, actorUid);
                } else if (current.isDeleted()) {
                    recoverUser(uid, actorUid);
                }
                // else: already active or pending-profile → no-op
            }
            default ->
                throw new IllegalArgumentException(
                    "Invalid status: " + status + ". Must be one of: blocked, active, deleted");
        }

        return userRepository.findByUid(uid)
                .orElseThrow(() -> new IllegalArgumentException("User not found after status update: " + uid));
    }

    /**
     * Soft-delete a user: marks DELETED in Firestore + writes audit log inside a transaction,
     * then disables + revokes tokens in Firebase Auth and evicts the status cache.
     * Enforces the last-admin guard (D2) inside the same transaction.
     */
    public void softDeleteUser(String uid, String actorUid, String reason) throws Exception {
        runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new IllegalArgumentException("User not found: " + uid);
            }
            User target = snap.toObject(User.class);

            // Last-admin guard (D2) — inline transactional check
            if (target.isAdmin()) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot delete themselves.");
                }
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot delete the last active admin.");
                }
            }

            target.recordSoftDelete(actorUid, reason);

            AuditLog audit = auditLogService.buildSoftDelete(uid, actorUid, reason);

            tx.set(userRef, target);
            tx.set(auditLogRepository.auditLogsCollection().document(), audit);
            return null;
        });

        // D9 — outside the tx, idempotent
        firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
        firebaseAuth.revokeRefreshTokens(uid);

        // D4 — cache evict
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);

        logger.info("Soft-deleted user uid={} actor={}", uid, actorUid);
    }

    /**
     * Recover a soft-deleted user: clears DELETED status in Firestore + writes audit log
     * inside a transaction, then re-enables the Firebase Auth account and evicts cache.
     * Requires target to currently be in DELETED status.
     */
    public void recoverUser(String uid, String actorUid) throws Exception {
        runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new IllegalArgumentException("User not found: " + uid);
            }
            User target = snap.toObject(User.class);
            if (!target.isDeleted()) {
                throw new IllegalStateException("User is not in DELETED status: " + uid);
            }

            target.recordRecover(actorUid);

            AuditLog audit = auditLogService.buildRecover(uid, actorUid);

            tx.set(userRef, target);
            tx.set(auditLogRepository.auditLogsCollection().document(), audit);
            return null;
        });

        firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));

        // D4 — cache evict
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);

        logger.info("Recovered user uid={} actor={}", uid, actorUid);
    }

    /**
     * Block a user: marks BLOCKED in Firestore + writes audit log inside a transaction,
     * then disables + revokes tokens in Firebase Auth and evicts the status cache.
     * Enforces the last-admin guard (D2) inside the same transaction.
     */
    public void blockUser(String uid, String actorUid, String reason) throws Exception {
        runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new IllegalArgumentException("User not found: " + uid);
            }
            User target = snap.toObject(User.class);

            // Last-admin guard (D2) — inline transactional check
            if (target.isAdmin()) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot block themselves.");
                }
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot block the last active admin.");
                }
            }

            target.recordBlock(actorUid, reason);

            AuditLog audit = auditLogService.buildBlock(uid, actorUid, reason);

            tx.set(userRef, target);
            tx.set(auditLogRepository.auditLogsCollection().document(), audit);
            return null;
        });

        // D9 — outside the tx, idempotent
        firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
        firebaseAuth.revokeRefreshTokens(uid);

        // D4 — cache evict
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);

        logger.info("Blocked user uid={} actor={} reason={}", uid, actorUid, reason);
    }

    /**
     * Unblock a user: clears BLOCKED status in Firestore + writes audit log inside a
     * transaction, then re-enables the Firebase Auth account and evicts cache.
     * Requires target to currently be in BLOCKED status.
     * No last-admin guard — unblock only increases active-admin count.
     */
    public void unblockUser(String uid, String actorUid) throws Exception {
        runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new IllegalArgumentException("User not found: " + uid);
            }
            User target = snap.toObject(User.class);
            if (!target.isBlocked()) {
                throw new IllegalStateException("User is not in BLOCKED status: " + uid);
            }

            target.recordUnblock(actorUid);

            AuditLog audit = auditLogService.buildUnblock(uid, actorUid);

            tx.set(userRef, target);
            tx.set(auditLogRepository.auditLogsCollection().document(), audit);
            return null;
        });

        // Re-enable Firebase Auth account
        firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));

        // D4 — cache evict
        Cache cache = cacheManager.getCache("userStatus");
        if (cache != null) cache.evict(uid);

        logger.info("Unblocked user uid={} actor={}", uid, actorUid);
    }

    /**
     * Record user login
     */
    public void recordLogin(String uid) throws ExecutionException, InterruptedException, TimeoutException {
        User user = userRepository.findByUid(uid).orElse(null);
        if (user != null) {
            user.recordLogin();
            userRepository.save(user);
        }
    }

    /**
     * Send password reset email
     */
    public void sendPasswordResetEmail(String email) throws FirebaseAuthException {
        String link = firebaseAuth.generatePasswordResetLink(email);
        // In production, send this link via email service
        logger.info("Password reset link generated for: {}", email);
        // TODO: Integrate with email service
    }

    /**
     * Verify if email exists
     */
    public boolean emailExists(String email) {
        try {
            firebaseAuth.getUserByEmail(email);
            return true;
        } catch (FirebaseAuthException e) {
            return false;
        }
    }
}

