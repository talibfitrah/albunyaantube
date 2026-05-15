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
            } catch (IllegalStateException e) {
                failures.add(new FailureEntry(uid, classify(e.getMessage())));
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

    private static String classify(String msg) {
        if (msg == null) return "invalid_state";
        String m = msg.toLowerCase();
        // Patterns must match the actual exception messages AuthService throws.
        //
        // Cubic R5 P1 — dead branches removed:
        //   - "already blocked" / "already deleted":  F13 made BLOCK/DELETE
        //     idempotent (no-op on already-X), so these strings are never
        //     thrown any more — the predicates never fired.
        //   - "cannot change role of a deleted":      ROLE_CHANGE is not a
        //     BulkAction (see {@link BulkAction}); bulk endpoints never reach
        //     {@code AuthService.updateRole} where this string is thrown.
        //
        // Live branches:
        //   - "User is not in BLOCKED status: ..."       → not_blocked
        //     (recoverUser, when target isn't blocked)
        //   - "User is not in DELETED status: ..."       → not_deleted
        //     (recoverUser, when target isn't deleted)
        //   - "Unblock before soft-deleting: ..."        → blocked_cannot_delete
        //   - "Cannot block a deleted user. Recover ..." → deleted_cannot_block
        if (m.contains("not in blocked status"))           return "not_blocked";
        if (m.contains("not in deleted status"))           return "not_deleted";
        if (m.contains("unblock before soft-deleting"))    return "blocked_cannot_delete";
        if (m.contains("cannot block a deleted user"))     return "deleted_cannot_block";
        return "invalid_state";
    }
}
