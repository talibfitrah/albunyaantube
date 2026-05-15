package com.albunyaan.tube.service;

import com.albunyaan.tube.config.FirestoreTimeoutProperties;
import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.model.AuditLog;
import com.albunyaan.tube.model.Role;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.AuditLogRepository;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.firestore.DocumentReference;
import com.google.cloud.firestore.DocumentSnapshot;
import com.google.cloud.firestore.FieldValue;
import com.google.cloud.firestore.SetOptions;
import com.google.cloud.firestore.Firestore;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.cloud.firestore.Transaction;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.UserRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.cache.Cache;
import org.springframework.cache.CacheManager;
import org.springframework.context.annotation.Lazy;
import org.springframework.scheduling.annotation.Async;
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
    private final MailService mailService;

    /**
     * Cubic R-final5 P2 — self-reference for {@code @Async} self-invocation.
     * Spring's AOP proxy is only consulted when the call goes through the
     * bean reference, not {@code this}. Without this lazy self-injection,
     * {@code revokeTokensWithAuditAsync} would run on the caller's thread
     * (defeating the point of the async dispatch).
     */
    @Autowired
    @Lazy
    private AuthService self;

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
                       FirestoreTimeoutProperties timeoutProperties,
                       MailService mailService) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
        this.auditLogRepository = auditLogRepository;
        this.firestore = firestore;
        this.cacheManager = cacheManager;
        this.timeoutProperties = timeoutProperties;
        this.mailService = mailService;
    }

    /**
     * F7: set the {@code role} custom claim on a Firebase Auth user WITHOUT
     * clobbering any other claims that may have been written by other systems
     * (subscription tier, feature flags, etc.).
     *
     * Pre-fix every call site used {@code Map.of("role", role)} which REPLACES
     * the entire claims object. This is a forward-compat hazard — any new
     * custom claim added in the future would be silently wiped by every
     * role-change / migration backfill.
     *
     * The role is normalised to canonical lowercase here so callers don't have
     * to remember D6 separately.
     */
    public void setUserRoleClaim(String uid, String role) throws FirebaseAuthException {
        UserRecord existing = firebaseAuth.getUser(uid);
        Map<String, Object> merged = existing.getCustomClaims() == null
                ? new HashMap<>()
                : new HashMap<>(existing.getCustomClaims());
        merged.put("role", role == null ? null : role.toLowerCase(java.util.Locale.ROOT));
        firebaseAuth.setCustomUserClaims(uid, merged);
    }

    /**
     * F4: evict the userStatus cache entry for {@code uid}. Always-runs semantics —
     * called from try/finally blocks in every lifecycle mutation so a Firebase Auth
     * failure cannot leave a stale ACTIVE entry in cache for the 60s TTL window.
     * Failures inside cache eviction itself are swallowed: an exception here would
     * mask the actual cause of the failure and the next cache read repopulates from
     * Firestore (which already reflects the new state).
     */
    private void evictUserStatus(String uid) {
        try {
            Cache cache = cacheManager.getCache("userStatus");
            if (cache != null) cache.evict(uid);
        } catch (RuntimeException e) {
            logger.warn("userStatus cache eviction failed for uid={}: {}", uid, e.toString());
        }
    }

    /**
     * Unwraps ExecutionException from firestore.runTransaction(...).get() so that
     * domain exceptions (LastAdminException, IllegalArgumentException, IllegalStateException)
     * propagate to @ControllerAdvice for proper HTTP status mapping. Without this, every
     * lifecycle method's spec'd 409/400/409 handler is unreachable.
     */
    /**
     * Cubic R5 P0 #2 — serialising sentinel for the last-admin guard.
     *
     * <p>The admin-count query (`role="admin" AND status="active"`) runs
     * inside the transaction, but Firestore txs only lock documents READ via
     * `tx.get(DocumentReference)`. A `QuerySnapshot` does not take per-doc
     * locks on its result set — two concurrent demote/block/delete txs
     * targeting <em>different</em> admin uids each see "2 admins", both pass
     * the {@code <= 1} guard, both commit → zero admins.
     *
     * <p>Fix: every lifecycle op that touches admin status reads AND writes a
     * single shared sentinel doc inside its tx. Firestore detects the
     * read-then-write conflict at commit time and aborts the losing tx; on
     * retry it observes the updated admin count and the guard fires
     * correctly.
     *
     * <p>The sentinel only kicks in for admin-touching operations (gated by
     * the caller's `if (target.isAdmin())` branch), so regular non-admin
     * blocks/deletes/role-changes don't pay the serialisation cost.
     */
    /**
     * Reads the admin sentinel lock document. Pairs with
     * {@link #lockAdminSentinelWrite(Transaction, String)} which MUST be
     * called AFTER all other reads in the tx and BEFORE any writes.
     *
     * <p>Cubic R-final3 P1 collapsed the original read+write into a
     * read-only stub because google-cloud-firestore 3.x enforces "all reads
     * before all writes" — the original {@code tx.get(lockRef);
     * tx.set(lockRef, …);} sequence was immediately followed by callers'
     * {@code tx.get(adminCount)} and threw IllegalStateException against a
     * real Firestore.
     *
     * <p>Cubic R-final4 P1 restores the write half (now split into a
     * second method): dropping the sentinel write silently broke
     * cross-target concurrent admin-on-admin tx isolation. Two
     * demote/block/delete txs targeting DIFFERENT admin uids share no
     * read/write doc pairs (different userRef writes, different auditDoc
     * writes), so both commit cleanly past the
     * {@code admins.size() <= 1} guard → zero admins. Splitting the read
     * out lets callers stage all reads first, run the count check, then
     * call the write half to restore conflict detection on the sentinel
     * itself.
     */
    private void lockAdminSentinelRead(Transaction tx) throws Exception {
        DocumentReference lockRef = firestore.document("system/admin_lock");
        tx.get(lockRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
    }

    /**
     * Writes the admin sentinel. MUST be called after
     * {@link #lockAdminSentinelRead(Transaction)} and after every other read
     * in the tx — Firestore aborts the commit if a concurrent tx has since
     * modified this doc, which is exactly the cross-target serialisation we
     * want for admin-on-admin lifecycle operations.
     */
    private void lockAdminSentinelWrite(Transaction tx, String op) {
        DocumentReference lockRef = firestore.document("system/admin_lock");
        tx.set(lockRef, Map.of("op", op, "ts", com.google.cloud.Timestamp.now()), SetOptions.merge());
    }

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

        // F7: merge-set the role claim (preserves any prior custom claims).
        // createUser is the genesis path, so existing claims will typically be
        // empty, but using the helper keeps the API surface uniform with the
        // role-update path.
        setUserRoleClaim(uid, canonicalRole);

        // Create user document in Firestore — canonical lowercase role.
        //
        // Cubic R7 P1 — compensate FB Auth if Firestore save fails.
        //
        // Pre-fix a Firestore save failure left a fresh FB Auth account
        // dangling with no Firestore row. Next /me sign-in lazy-created the
        // row as role="user" (R7 P1 #4 also tightens that path) and the
        // operator-intended role was lost — admins created via the wizard
        // could be silently downgraded to "user" by a single Firestore
        // hiccup. The compensation here deletes the FB Auth user so the
        // operator can retry cleanly. Any compensation failure is logged
        // and propagates the ORIGINAL exception so observability points at
        // the root cause, not the cleanup.
        try {
            User user = new User(uid, email, displayName, canonicalRole);
            user.setCreatedBy(createdByUid);
            userRepository.save(user);
            logger.info("Created user: {} (uid: {}) with role: {}", email, uid, canonicalRole);
            return user;
        } catch (ExecutionException | InterruptedException | TimeoutException | RuntimeException saveEx) {
            if (saveEx instanceof InterruptedException) Thread.currentThread().interrupt();
            logger.error("createUser: Firestore save failed for uid={}; compensating FB Auth delete", uid, saveEx);
            try {
                firebaseAuth.deleteUser(uid);
            } catch (FirebaseAuthException compensateEx) {
                // Orphan FB Auth row. Operator must clean up manually. Audit
                // surfaces the situation; logging at error level lets
                // dashboards page the on-call rotation.
                logger.error("createUser: COMPENSATION FAILED — orphan FB Auth uid={}", uid, compensateEx);
                try {
                    auditLogService.logSystem(
                            "USER_CREATE_COMPENSATION_FAILED",
                            "user", uid,
                            "auth-delete-failed: " + compensateEx.getClass().getSimpleName());
                } catch (RuntimeException ignored) {}
            }
            // Re-throw the ORIGINAL save exception so the controller maps it to
            // the right HTTP status (500 / 503), not the compensation failure.
            //
            // Cubic R-final4 P2 — explicit type-dispatching chain. The caught
            // types are `ExecutionException | InterruptedException |
            // TimeoutException | RuntimeException`. Pre-fix the final branch
            // was `throw (RuntimeException) saveEx;` which is safe today but
            // becomes a ClassCastException-on-failure-path footgun the
            // moment a future maintainer adds a new checked exception to the
            // throws clause. The explicit chain below makes the contract
            // visible and surfaces the mismatch at compile time.
            if (saveEx instanceof ExecutionException ee) throw ee;
            if (saveEx instanceof InterruptedException ie) throw ie;
            if (saveEx instanceof TimeoutException te) throw te;
            if (saveEx instanceof RuntimeException re) throw re;
            // Unreachable: the catch clause caps the type universe.
            throw new IllegalStateException(
                    "Unexpected createUser save-failure type: " + saveEx.getClass().getName(), saveEx);
        }
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
                throw new UserNotFoundException(uid);
            }
            User target = snap.toObject(User.class);
            previousRole[0] = target.getRole();

            // F12: refuse to change role on a DELETED user. Recover first, then
            // change role. This keeps role-change audit events tied to live
            // accounts only and prevents privilege drift on dormant rows.
            if (target.isDeleted()) {
                throw new IllegalStateException(
                    "Cannot change role of a deleted user. Recover first: " + uid);
            }

            // Last-admin guard (D2) — inline, transactional
            if (target.isAdmin() && newRole != Role.ADMIN) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot demote themselves. Ask another admin.");
                }
                lockAdminSentinelRead(tx);
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot demote the last active admin.");
                }
                lockAdminSentinelWrite(tx, "demote");
            }

            target.setRole(newRole.getValue());
            target.setUpdatedAt(com.google.cloud.Timestamp.now());

            AuditLog audit = auditLogService.buildRoleChange(uid, actorUid,
                    previousRole[0], newRole.getValue());

            // Cubic R5 P1 #7: merge instead of full-replace so out-of-band
            // fields written by Plan D sync or future schema migrations are
            // not silently wiped on every lifecycle commit.
            tx.set(userRef, target, SetOptions.merge());
            auditLogRepository.saveInTransaction(tx, audit);
            return target;
        });

        // F4: cache eviction in try/finally so a FB Auth failure can't leave a
        // stale entry behind. Pre-fix the evict ran AFTER the FB Auth call and
        // would be skipped on any exception.
        try {
            // F7 + D6: merge-set the role claim so OTHER custom claims survive.
            // Map.of("role", newRole) used to replace the entire claim object.
            setUserRoleClaim(uid, newRole.getValue());
        } finally {
            evictUserStatus(uid);
        }

        logger.info("Role changed: uid={} from={} to={} actor={}",
                uid, previousRole[0], newRole.getValue(), actorUid);

        // Plan F (ADMIN-USER-01, F6) — auto-revoke refresh tokens so the new role
        // takes effect immediately rather than after the existing JWT expires.
        // Errors are absorbed: the role change has already committed. Audit entry
        // distinguishes the auto-fire from an admin-triggered revoke.
        FirebaseUserDetails actor =
                new FirebaseUserDetails(actorUid, null, "admin");
        // Cubic R-final5 P2 — fire revoke retry asynchronously.
        //
        // Pre-fix the bounded 3-attempt retry (200ms/400ms backoff) ran
        // inline on the admin HTTP request thread, stacking up to 600ms of
        // Tomcat thread occupancy on every role change. The retry +
        // success/failure audit emission is now delegated to the
        // `authExecutor` bean (see AsyncConfig). The role-change response
        // returns as soon as the role + audit are committed; the revoke
        // completes (or audits a failure) on the background executor.
        // Same fail-open contract as before — failure surfaces via the
        // USER_SESSIONS_REVOKED_AUTO_FAILED audit row, not the HTTP path.
        // self is null in unit tests that construct AuthService without a Spring
        // context. Fall back to a direct (synchronous) call so test contracts
        // around audit-row emission still hold; production paths always have
        // the proxy wired via @Autowired @Lazy.
        if (self != null) {
            self.revokeTokensWithAuditAsync(uid, actor, previousRole[0], newRole.getValue(), "role_change");
        } else {
            revokeTokensWithAuditAsync(uid, actor, previousRole[0], newRole.getValue(), "role_change");
        }

        return updated;
    }

    /**
     * Cubic R-final5 P2 — async retry+audit for refresh-token revocation.
     * Called from {@link #updateUserRoleAsActor} after the role-change
     * commit. Runs on the {@code authExecutor} thread pool so admin HTTP
     * threads return immediately. Self-invocation will not trigger the
     * proxy — always invoke through the Spring-managed bean reference.
     */
    @Async("authExecutor")
    public void revokeTokensWithAuditAsync(String uid,
                                            FirebaseUserDetails actor,
                                            String oldRole,
                                            String newRole,
                                            String trigger) {
        Exception lastError = null;
        boolean revoked = false;
        for (int attempt = 1; attempt <= 3 && !revoked; attempt++) {
            try {
                firebaseAuth.revokeRefreshTokens(uid);
                revoked = true;
            } catch (Exception e) {
                lastError = e;
                if (attempt < 3) {
                    try {
                        Thread.sleep(200L * attempt);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
            }
        }
        if (revoked) {
            auditLogService.log(
                    "USER_SESSIONS_REVOKED_AUTO",
                    "user", uid,
                    actor,
                    java.util.Map.of(
                            "oldRole", oldRole == null ? "" : oldRole,
                            "newRole", newRole,
                            "trigger", trigger));
        } else {
            // F6 + risk §11.6: log + audit-failure, never throw.
            logger.error("auto-revoke after role change failed (after retries) uid={}", uid, lastError);
            auditLogService.log(
                    "USER_SESSIONS_REVOKED_AUTO_FAILED",
                    "user", uid,
                    actor,
                    java.util.Map.of(
                            "error", lastError == null ? "unknown" : lastError.getClass().getSimpleName(),
                            "attempts", "3"));
        }
    }

    /**
     * @deprecated Legacy facade preserved for frontend backwards-compatibility. New callers
     * should invoke {@link #blockUser}, {@link #unblockUser}, {@link #softDeleteUser}, or
     * {@link #recoverUser} directly. This facade DELEGATES to those methods so that every
     * lifecycle safeguard (D2 last-admin guard, D4 cache eviction, D5 audit log,
     * D9 Firebase Auth sync, transactional consistency) is enforced. It used to bypass
     * all of them — review-pipeline finding F1.
     *
     * <p>Legacy admin clients still send {@code "inactive"} as a synonym for
     * {@code "blocked"} (see frontend {@code adminUsers.ts#toBackendStatus}).
     * F17 accepts that alias and defaults the reason to a sentinel so the
     * "block user" button keeps working until the admin dashboard migrates to
     * the canonical wire shape. Will be removed once the frontend migrates.
     *
     * Accepted status values:
     *   "blocked"  → delegates to {@link #blockUser} (reason required)
     *   "inactive" → F17 alias for "blocked"; reason defaults to
     *                {@value #LEGACY_STATUS_REASON} if missing so the audit log
     *                still records WHY the user was blocked (and identifies the
     *                call as a legacy-shape one for operator triage).
     *   "active"   → if currently BLOCKED, delegates to {@link #unblockUser};
     *                if currently DELETED, delegates to {@link #recoverUser};
     *                otherwise no-op (returns current user unchanged)
     *   "deleted"  → delegates to {@link #softDeleteUser} (reason required)
     *
     * @param uid      target user UID
     * @param status   new lifecycle status — one of
     *                 "blocked" | "inactive" (alias) | "active" | "deleted"
     * @param actorUid actor UID for audit + self-action guards
     * @param reason   required for canonical "blocked" and "deleted";
     *                 defaulted to {@value #LEGACY_STATUS_REASON} for the
     *                 legacy "inactive" alias path; ignored for "active"
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
            case "inactive", "blocked" -> {
                // F17: only the legacy "inactive" alias may default the reason —
                // canonical "blocked" callers must always provide one so new
                // wire-shapes don't accidentally inherit the looser contract.
                String effectiveReason;
                if (reason == null || reason.isBlank()) {
                    if ("inactive".equals(normalized)) {
                        effectiveReason = LEGACY_STATUS_REASON;
                    } else {
                        throw new IllegalArgumentException("reason is required when status=blocked");
                    }
                } else {
                    effectiveReason = reason;
                }
                blockUser(uid, actorUid, effectiveReason);
            }
            case "deleted" -> {
                if (reason == null || reason.isBlank()) {
                    throw new IllegalArgumentException("reason is required when status=deleted");
                }
                softDeleteUser(uid, actorUid, reason);
            }
            case "active" -> {
                User current = userRepository.findByUid(uid)
                        .orElseThrow(() -> new UserNotFoundException(uid));
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
                .orElseThrow(() -> new UserNotFoundException(uid));
    }

    /**
     * F17: default reason recorded on the audit log when a legacy admin client
     * hits the {@code "inactive"} alias path without sending a {@code reason}
     * field. Surfaces in audit history so operators can filter for legacy-shape
     * calls and identify callers that still need migration.
     */
    static final String LEGACY_STATUS_REASON = "legacy-status-update";

    /**
     * Soft-delete a user: marks DELETED in Firestore + writes audit log inside a transaction,
     * then disables + revokes tokens in Firebase Auth and evicts the status cache.
     * Enforces the last-admin guard (D2) inside the same transaction.
     *
     * <p>F20: the tx returns a boolean indicating whether a state transition
     * actually occurred. The post-tx Firebase Auth side-effects
     * ({@code setDisabled(true)}, {@code revokeRefreshTokens}) only fire when
     * the tx wrote new state. The cache eviction still runs unconditionally
     * (cheap, defensive). Pre-F20 the side-effects ran even on the F13
     * idempotent no-op path, which could re-disable an out-of-band-enabled
     * account or re-revoke tokens on an already-deleted user.
     */
    public void softDeleteUser(String uid, String actorUid, String reason) throws Exception {
        Boolean transitioned = runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new UserNotFoundException(uid);
            }
            User target = snap.toObject(User.class);

            // F8: forbid soft-delete of an already-blocked user. Pre-fix path was
            //   block (status=blocked) → softDelete (status=deleted) → unblock (status=active)
            // — the unblock step turned a blocked user back to ACTIVE without a
            // USER_RECOVERED audit row, evading the audit trail. By refusing the
            // delete entirely, admins must explicitly unblock or follow the
            // recover path, both of which write the right audit events.
            if (target.isBlocked()) {
                throw new UserStateConflictException(
                    UserStateConflictException.ReasonCode.BLOCKED_CANNOT_DELETE,
                    "Unblock before soft-deleting: " + uid);
            }

            // F13: idempotent — if target is already DELETED, no-op. Retry-safe:
            // a tx-commits-but-FB-Auth-fails retry won't write a SECOND
            // USER_SOFT_DELETED audit nor overwrite the original deletedAt /
            // deleteReason. Runs AFTER F8 isBlocked guard so a (highly unusual)
            // BLOCKED + retry path still throws rather than silently skips.
            //
            // F20: return false on the no-op path so the post-tx FB Auth
            // side-effects are skipped. Pre-F20 a retry would re-call
            // setDisabled(true) + revokeRefreshTokens on an already-deleted
            // user — harmless for setDisabled but revoking tokens updates
            // validSince and would invalidate any fresh out-of-band token a
            // SysAdmin minted for support investigation.
            if (target.isDeleted()) {
                return Boolean.FALSE;
            }

            // Cubic R7 P1 — F13/F20 noop reason was silently dropped.
            // Captured here so the post-tx side can emit an audit row.
            // (Tx side is the wrong place: auditLogService.log uses its own
            // Firestore writes and would fire on retries.)
            // No-op via the if-branch above already returned; this is the
            // proceed path where transition will occur.

            // Last-admin guard (D2) — inline transactional check
            if (target.isAdmin()) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot delete themselves.");
                }
                lockAdminSentinelRead(tx);
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot delete the last active admin.");
                }
                lockAdminSentinelWrite(tx, "delete");
            }

            target.recordSoftDelete(actorUid, reason);

            AuditLog audit = auditLogService.buildSoftDelete(uid, actorUid, reason);

            // Cubic R5 P1 #7: merge instead of full-replace so out-of-band
            // fields written by Plan D sync or future schema migrations are
            // not silently wiped on every lifecycle commit.
            tx.set(userRef, target, SetOptions.merge());
            auditLogRepository.saveInTransaction(tx, audit);
            return Boolean.TRUE;
        });

        // F4: cache eviction in try/finally — guarantee D4 fires even when FB Auth
        // calls throw, otherwise a stale ACTIVE entry can let a deleted user
        // pass the AccountStatusFilter for the next 60s. Eviction still runs
        // unconditionally even when the tx no-op'd (cheap, defensive).
        try {
            // F20: only fire FB Auth side-effects if the tx actually
            // transitioned the user. Pre-F20 an idempotent retry could
            // re-revoke tokens and invalidate fresh out-of-band tokens.
            if (Boolean.TRUE.equals(transitioned)) {
                // D9 — outside the tx, idempotent
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
                firebaseAuth.revokeRefreshTokens(uid);
            } else if (reason != null && !reason.isBlank()) {
                // Cubic R7 P1 — preserve the noop reason in an audit row.
                //
                // Pre-fix when softDelete was called on an already-deleted target,
                // F13/F20 returned no-op and the supplied {@code reason} was
                // silently dropped. Re-blocking for a more severe policy
                // violation never reached the audit trail. The _NOOP variant
                // captures the new reason without altering the user's state.
                auditLogService.logSystem(
                        "USER_SOFT_DELETE_NOOP",
                        "user", uid,
                        "noop-reason: " + reason);
            }
        } finally {
            evictUserStatus(uid);
        }

        logger.info("Soft-deleted user uid={} actor={} transitioned={}",
                uid, actorUid, transitioned);
    }

    /**
     * Recover a soft-deleted user: clears DELETED status in Firestore + writes audit log
     * inside a transaction, then re-enables the Firebase Auth account and evicts cache.
     * Requires target to currently be in DELETED status.
     *
     * <p>F20: same shape as softDeleteUser/blockUser/unblockUser — tx returns
     * a boolean transition flag and the post-tx {@code setDisabled(false)}
     * only fires when the tx actually transitioned. recoverUser currently
     * THROWS on non-DELETED targets (no idempotent path), so the flag is
     * effectively always true if we reach the post-tx code — but we keep the
     * pattern uniform so future idempotency additions don't drift.
     */
    public void recoverUser(String uid, String actorUid) throws Exception {
        Boolean transitioned = runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new UserNotFoundException(uid);
            }
            User target = snap.toObject(User.class);
            // F13 / F20 (R7 P1 deferred, applied in F-LIFECYCLE-NOOP-01):
            // mirrors unblockUser's three-case shape.
            //   1) Already ACTIVE → idempotent no-op (retry-safe).
            //   2) BLOCKED → reject (must go through unblock, not recover).
            //   3) PENDING_PROFILE → reject (was never deleted).
            // Pre-fix every non-DELETED case threw UserStateConflictException
            // (NOT_DELETED) → 409, inconsistent with blockUser/softDeleteUser
            // / unblockUser which return false → 200 on the idempotent path.
            // ACTIVE no-op preserves the "look, we're done" UX while keeping
            // the BLOCKED/PENDING guard strict (audit-trail honesty).
            if (target.isActive()) {
                return Boolean.FALSE;
            }
            if (!target.isDeleted()) {
                throw new UserStateConflictException(
                    UserStateConflictException.ReasonCode.NOT_DELETED,
                    "User is not in DELETED status: " + uid);
            }

            target.recordRecover(actorUid);

            AuditLog audit = auditLogService.buildRecover(uid, actorUid);

            // Cubic R5 P1 #7: merge instead of full-replace so out-of-band
            // fields written by Plan D sync or future schema migrations are
            // not silently wiped on every lifecycle commit.
            tx.set(userRef, target, SetOptions.merge());
            auditLogRepository.saveInTransaction(tx, audit);
            return Boolean.TRUE;
        });

        // F4: cache eviction in try/finally (see softDeleteUser for rationale).
        try {
            // F20: only re-enable if the tx actually transitioned. With the
            // current strict guard (must be DELETED) this is always TRUE, but
            // keeping the gate keeps the pattern consistent.
            if (Boolean.TRUE.equals(transitioned)) {
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));
            }
        } finally {
            evictUserStatus(uid);
        }

        logger.info("Recovered user uid={} actor={} transitioned={}",
                uid, actorUid, transitioned);
    }

    /**
     * Plan F (ADMIN-USER-01, F6) — stand-alone refresh-token revocation.
     * Extracted from the inline calls in {@link #blockUser} / {@link #softDeleteUser}
     * so admins can force-logout a user without changing their account state.
     */
    public void revokeSessions(String uid,
                               FirebaseUserDetails actor,
                               String reason)
            throws FirebaseAuthException {
        Map<String, Object> details = new HashMap<>();
        if (reason != null && !reason.isBlank()) details.put("reason", reason);

        // Cubic R5 P1 #10 — audit BEFORE the revoke, not after.
        //
        // A security-sensitive action must produce an audit row regardless of
        // outcome. Previously the audit row was written only after a
        // successful revoke; when `revokeRefreshTokens` threw (FirebaseAuth
        // outage, network blip, mistyped uid) no row was written and the
        // attempt was invisible in the audit trail. Writing the
        // `USER_SESSIONS_REVOKE_ATTEMPTED` row first guarantees the attempt
        // is recorded; the `USER_SESSIONS_REVOKED` row on success then
        // closes the pair.
        auditLogService.log(
                "USER_SESSIONS_REVOKE_ATTEMPTED",
                "user", uid,
                actor,
                details);

        firebaseAuth.revokeRefreshTokens(uid);

        auditLogService.log(
                "USER_SESSIONS_REVOKED",
                "user", uid,
                actor,
                details);
    }

    /**
     * Block a user: marks BLOCKED in Firestore + writes audit log inside a transaction,
     * then disables + revokes tokens in Firebase Auth and evicts the status cache.
     * Enforces the last-admin guard (D2) inside the same transaction.
     *
     * <p>F20: post-tx FB Auth side-effects gated on the tx transition flag.
     * Pre-F20 a retry against an already-BLOCKED target re-called
     * revokeRefreshTokens, which updates {@code validSince} and would
     * invalidate any fresh out-of-band token issued by a SysAdmin for
     * support investigation. Now the idempotent path skips both
     * setDisabled and revokeRefreshTokens.
     */
    public void blockUser(String uid, String actorUid, String reason) throws Exception {
        Boolean transitioned = runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new UserNotFoundException(uid);
            }
            User target = snap.toObject(User.class);

            // F12: refuse to block an already-DELETED user. Pre-fix path was
            //   softDelete → block (audit: USER_BLOCKED) → unblock (audit:
            //   USER_UNBLOCKED) → ends up status="active" with NO USER_RECOVERED
            // audit. That evaded the audit trail. Force admins down the recover
            // path so the audit log reflects what actually happened.
            if (target.isDeleted()) {
                throw new UserStateConflictException(
                    UserStateConflictException.ReasonCode.DELETED_CANNOT_BLOCK,
                    "Cannot block a deleted user. Recover first: " + uid);
            }

            // F13: idempotent — if target is already BLOCKED, no-op. Pre-fix a
            // retry after a partial failure (tx commits, FB Auth fails, admin
            // retries) re-ran recordBlock, overwriting blockedAt/blockReason
            // and writing a SECOND USER_BLOCKED audit row. Now retries are
            // safe; the original block timestamp and audit are preserved.
            // Cross-state guard (F12) above runs FIRST so a deleted target
            // still throws cleanly instead of being silently skipped.
            //
            // F20: return false here so the post-tx setDisabled +
            // revokeRefreshTokens are skipped. revokeRefreshTokens has a
            // visible side-effect (updates validSince) — re-calling it on an
            // already-blocked user can invalidate fresh out-of-band tokens.
            if (target.isBlocked()) {
                return Boolean.FALSE;
            }

            // Last-admin guard (D2) — inline transactional check
            if (target.isAdmin()) {
                if (uid.equals(actorUid)) {
                    throw new LastAdminException("Admins cannot block themselves.");
                }
                lockAdminSentinelRead(tx);
                QuerySnapshot admins = tx.get(firestore.collection("users")
                        .whereEqualTo("role", "admin")
                        .whereEqualTo("status", "active"))
                        .get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
                if (admins.size() <= 1) {
                    throw new LastAdminException("Cannot block the last active admin.");
                }
                lockAdminSentinelWrite(tx, "block");
            }

            target.recordBlock(actorUid, reason);

            AuditLog audit = auditLogService.buildBlock(uid, actorUid, reason);

            // Cubic R5 P1 #7: merge instead of full-replace so out-of-band
            // fields written by Plan D sync or future schema migrations are
            // not silently wiped on every lifecycle commit.
            tx.set(userRef, target, SetOptions.merge());
            auditLogRepository.saveInTransaction(tx, audit);
            return Boolean.TRUE;
        });

        // F4: cache eviction in try/finally — guarantee D4 fires even when FB Auth
        // calls throw, otherwise a stale ACTIVE entry lets a blocked user pass the
        // AccountStatusFilter for the next 60s.
        try {
            // F20: only fire FB Auth side-effects on actual transitions —
            // see method-level javadoc for rationale.
            if (Boolean.TRUE.equals(transitioned)) {
                // D9 — outside the tx, idempotent
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(true));
                firebaseAuth.revokeRefreshTokens(uid);
            } else if (reason != null && !reason.isBlank()) {
                // Cubic R7 P1 — see softDeleteUser noop-audit rationale. A
                // re-block for a more severe policy violation against an
                // already-BLOCKED user previously dropped the reason on the
                // floor with no audit signal; now the _NOOP variant captures
                // it without touching state.
                auditLogService.logSystem(
                        "USER_BLOCK_NOOP",
                        "user", uid,
                        "noop-reason: " + reason);
            }
        } finally {
            evictUserStatus(uid);
        }

        logger.info("Blocked user uid={} actor={} reason={} transitioned={}",
                uid, actorUid, reason, transitioned);
    }

    /**
     * Unblock a user: clears BLOCKED status in Firestore + writes audit log inside a
     * transaction, then re-enables the Firebase Auth account and evicts cache.
     * Requires target to currently be in BLOCKED status.
     * No last-admin guard — unblock only increases active-admin count.
     *
     * <p>F20: post-tx {@code setDisabled(false)} gated on the tx transition
     * flag. The motivating scenario:
     * <ol>
     *   <li>SysAdmin disables a user in FB Auth Console out-of-band (e.g.,
     *       for support investigation). Firestore status stays ACTIVE.</li>
     *   <li>A moderator calls unblockUser on this user by mistake.</li>
     *   <li>F13 sees ACTIVE → tx no-op return.</li>
     *   <li>Pre-F20 the post-tx {@code setDisabled(false)} ran
     *       unconditionally and re-enabled the out-of-band-disabled account,
     *       silently undoing the SysAdmin action.</li>
     * </ol>
     * Pre-F13 this would have thrown {@code IllegalStateException}
     * ("User is not in BLOCKED status"), surfacing the divergence. With F13
     * making the path idempotent, F20 must restore the safety property.
     */
    public void unblockUser(String uid, String actorUid) throws Exception {
        Boolean transitioned = runLifecycleTx(tx -> {
            DocumentReference userRef = firestore.collection("users").document(uid);
            DocumentSnapshot snap = tx.get(userRef).get(timeoutProperties.getWrite(), TimeUnit.SECONDS);
            if (!snap.exists()) {
                throw new UserNotFoundException(uid);
            }
            User target = snap.toObject(User.class);
            // F13 — three cases for non-BLOCKED targets:
            //   1) Already ACTIVE → idempotent no-op (retry-safe).
            //   2) DELETED → reject (must go through recover, not unblock).
            //   3) PENDING_PROFILE → reject (was never blocked).
            // Pre-F13 every non-BLOCKED case threw IllegalStateException, so a
            // retry after a partial failure would have looked like a bug to the
            // operator. ACTIVE no-op preserves the "look, we're done" UX while
            // keeping the DELETED guard strict (audit-trail honesty).
            //
            // F20: return false so the post-tx setDisabled(false) is skipped
            // — see method-level javadoc for the out-of-band-disabled
            // scenario this protects against.
            if (target.isActive()) {
                return Boolean.FALSE;
            }
            if (!target.isBlocked()) {
                throw new UserStateConflictException(
                    UserStateConflictException.ReasonCode.NOT_BLOCKED,
                    "User is not in BLOCKED status: " + uid);
            }

            target.recordUnblock(actorUid);

            AuditLog audit = auditLogService.buildUnblock(uid, actorUid);

            // Cubic R5 P1 #7: merge instead of full-replace so out-of-band
            // fields written by Plan D sync or future schema migrations are
            // not silently wiped on every lifecycle commit.
            tx.set(userRef, target, SetOptions.merge());
            auditLogRepository.saveInTransaction(tx, audit);
            return Boolean.TRUE;
        });

        // F4: cache eviction in try/finally (see blockUser for rationale).
        try {
            // F20: only re-enable FB Auth if the tx actually transitioned.
            // See method-level javadoc — protects against silently undoing
            // an out-of-band SysAdmin-issued disable.
            if (Boolean.TRUE.equals(transitioned)) {
                firebaseAuth.updateUser(new UserRecord.UpdateRequest(uid).setDisabled(false));
            }
        } finally {
            evictUserStatus(uid);
        }

        logger.info("Unblocked user uid={} actor={} transitioned={}",
                uid, actorUid, transitioned);
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
        logger.info("Password reset link generated for: {}", email);
        mailService.sendPasswordResetEmail(email, link);
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

