package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.dto.BulkUserActionResult.FailureEntry;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

@Service
public class BulkUserService {
    private static final Logger log = LoggerFactory.getLogger(BulkUserService.class);

    private final AuthService authService;
    private final UserRepository userRepository;
    private final AuditLogService auditLogService;

    public BulkUserService(AuthService authService,
                            UserRepository userRepository,
                            AuditLogService auditLogService) {
        this.authService = authService;
        this.userRepository = userRepository;
        this.auditLogService = auditLogService;
    }

    public BulkUserActionResult execute(BulkAction action,
                                         List<String> uids,
                                         FirebaseUserDetails actor,
                                         String reason) {
        String actorUid = actor.getUid();
        List<String> successes = new ArrayList<>();
        List<FailureEntry> failures = new ArrayList<>();

        for (String uid : uids) {
            if (uid.equals(actorUid)) {
                failures.add(new FailureEntry(uid, "self_action_forbidden"));
                continue;
            }

            // Admin-target guard applies to every bulk action — including RECOVER.
            // Skipping it for RECOVER (the previous behaviour) meant a deleted admin
            // could be silently restored by another admin without the per-target
            // admin_target_forbidden audit row. Keeping the check uniform also
            // simplifies reasoning: bulk endpoints never touch admins.
            //
            // Cubic R5 P1: use the uncached read so a just-promoted admin from
            // another node is correctly detected. `findByUid` goes through the
            // per-JVM Caffeine cache (`userStatus`); the bulk-target guard MUST
            // see the live role or we leak admins into the bulk path.
            try {
                Optional<User> u = userRepository.findByUidUncached(uid);
                if (u.isPresent() && "admin".equalsIgnoreCase(u.get().getRole())) {
                    failures.add(new FailureEntry(uid, "admin_target_forbidden"));
                    continue;
                }
            } catch (Exception e) {
                log.error("admin.target.check.error uid={}", uid, e);
                failures.add(new FailureEntry(uid, "firebase_error"));
                continue;
            }

            try {
                switch (action) {
                    case BLOCK            -> authService.blockUser(uid, actorUid, reason);
                    case DELETE           -> authService.softDeleteUser(uid, actorUid, reason);
                    case RECOVER          -> authService.recoverUser(uid, actorUid);
                    case REVOKE_SESSIONS  -> authService.revokeSessions(uid, actor, reason);
                }
                successes.add(uid);
            } catch (com.albunyaan.tube.service.UserNotFoundException e) {
                // AuthService now throws UserNotFoundException uniformly for missing
                // users — no string-match fallback against IllegalArgumentException
                // messages, which broke silently when message text drifted.
                failures.add(new FailureEntry(uid, "user_not_found"));
            } catch (UserStateConflictException e) {
                // Cubic R6 P2 — typed reason code. Replaces the
                // {@code msg.toLowerCase().contains(...)} routing that silently
                // downgraded any AuthService message rename to "invalid_state",
                // breaking the i18n keys (users.bulk.reason.blocked_cannot_delete,
                // etc.) without a test signal.
                failures.add(new FailureEntry(uid, classify(e.getReasonCode())));
            } catch (IllegalStateException e) {
                // Defensive fallback — any plain IllegalStateException from new
                // call sites that hasn't yet been promoted to a typed code.
                failures.add(new FailureEntry(uid, "invalid_state"));
            } catch (Exception e) {
                log.error("bulk.action.error uid={} action={}", uid, action, e);
                failures.add(new FailureEntry(uid, "firebase_error"));
            }
        }

        Map<String, Object> details = new HashMap<>();
        details.put("action", action.name().toLowerCase());
        details.put("successes", successes.size());
        details.put("failures", failures.size());
        if (reason != null && !reason.isBlank()) details.put("reason", reason);

        auditLogService.log(
                "USER_BULK_ACTION",
                "user", "(batch)",
                actor,
                details);

        return new BulkUserActionResult(successes, failures);
    }

    /**
     * Cubic R6 P2 — typed dispatch.
     *
     * <p>Maps {@link UserStateConflictException.ReasonCode} to the stable
     * reason strings consumed by the bulk-action i18n keys
     * ({@code users.bulk.reason.*}). The mapping is enum-driven so a rename
     * of an AuthService exception message no longer breaks classification —
     * the compiler enforces exhaustiveness on the switch instead.
     */
    private static String classify(UserStateConflictException.ReasonCode code) {
        return switch (code) {
            case NOT_BLOCKED            -> "not_blocked";
            case NOT_DELETED            -> "not_deleted";
            case BLOCKED_CANNOT_DELETE  -> "blocked_cannot_delete";
            case DELETED_CANNOT_BLOCK   -> "deleted_cannot_block";
        };
    }
}
