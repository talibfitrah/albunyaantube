package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.AuditLog;
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
                createUser(initialAdminEmail, initialAdminPassword, initialAdminDisplayName, "ADMIN", null);
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
     * Create a new user in Firebase Auth and Firestore
     */
    public User createUser(String email, String password, String displayName, String role, String createdByUid)
            throws FirebaseAuthException, ExecutionException, InterruptedException, TimeoutException {

        // Create user in Firebase Authentication
        UserRecord.CreateRequest request = new UserRecord.CreateRequest()
                .setEmail(email)
                .setPassword(password)
                .setDisplayName(displayName)
                .setEmailVerified(false);

        UserRecord userRecord = firebaseAuth.createUser(request);
        String uid = userRecord.getUid();

        // Set custom claims for role-based access
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role != null ? role.toLowerCase(java.util.Locale.ROOT) : null);
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Create user document in Firestore
        User user = new User(uid, email, displayName, role);
        user.setCreatedBy(createdByUid);
        userRepository.save(user);

        logger.info("Created user: {} (uid: {}) with role: {}", email, uid, role);
        return user;
    }

    /**
     * Update user role (both Firebase claims and Firestore)
     */
    public User updateUserRole(String uid, String newRole)
            throws FirebaseAuthException, ExecutionException, InterruptedException, TimeoutException {

        // Update custom claims in Firebase
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", newRole != null ? newRole.toLowerCase(java.util.Locale.ROOT) : null);
        firebaseAuth.setCustomUserClaims(uid, claims);

        // Update Firestore document
        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + uid));
        user.setRole(newRole);
        user.touch();
        userRepository.save(user);

        logger.info("Updated role for user {} to: {}", uid, newRole);
        return user;
    }

    /**
     * Activate/deactivate user
     */
    public User updateUserStatus(String uid, String status)
            throws FirebaseAuthException, ExecutionException, InterruptedException, TimeoutException {

        boolean disabled = "inactive".equals(status);

        // Update Firebase Auth
        UserRecord.UpdateRequest request = new UserRecord.UpdateRequest(uid)
                .setDisabled(disabled);
        firebaseAuth.updateUser(request);

        // Update Firestore
        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new IllegalArgumentException("User not found: " + uid));
        user.setStatus(status);
        user.touch();
        userRepository.save(user);

        logger.info("Updated status for user {} to: {}", uid, status);
        return user;
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

