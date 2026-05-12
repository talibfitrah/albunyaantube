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

            if (action != BulkAction.RECOVER) {
                try {
                    Optional<User> u = userRepository.findByUid(uid);
                    if (u.isPresent() && "admin".equalsIgnoreCase(u.get().getRole())) {
                        failures.add(new FailureEntry(uid, "admin_target_forbidden"));
                        continue;
                    }
                } catch (Exception e) {
                    log.error("admin.target.check.error uid={}", uid, e);
                    failures.add(new FailureEntry(uid, "firebase_error"));
                    continue;
                }
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
                failures.add(new FailureEntry(uid, "user_not_found"));
            } catch (IllegalArgumentException e) {
                // AuthService throws IllegalArgumentException("User not found: ...") rather
                // than the typed UserNotFoundException for several lifecycle paths. Surface
                // these as user_not_found rather than firebase_error.
                String msg = e.getMessage();
                if (msg != null && msg.toLowerCase().contains("not found")) {
                    failures.add(new FailureEntry(uid, "user_not_found"));
                } else {
                    log.error("bulk.action.error uid={} action={}", uid, action, e);
                    failures.add(new FailureEntry(uid, "firebase_error"));
                }
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
        if (m.contains("already blocked")) return "already_blocked";
        if (m.contains("not blocked"))     return "not_blocked";
        if (m.contains("already deleted")) return "already_deleted";
        if (m.contains("not deleted"))     return "not_deleted";
        return "invalid_state";
    }
}
