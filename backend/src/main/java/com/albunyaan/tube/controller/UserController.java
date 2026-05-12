package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.BulkUserActionRequest;
import com.albunyaan.tube.dto.BulkUserActionResult;
import com.albunyaan.tube.dto.RevokeSessionsRequest;
import com.albunyaan.tube.model.Role;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.AuthService;
import com.albunyaan.tube.service.BulkAction;
import com.albunyaan.tube.service.BulkUserService;
import com.google.firebase.auth.FirebaseAuthException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * FIREBASE-MIGRATE-04: User Management Controller
 *
 * Endpoints for managing admin and moderator users.
 * Only admins can create/update/delete users.
 */
@RestController
@RequestMapping("/api/admin/users")
public class UserController {

    private static final Logger log = LoggerFactory.getLogger(UserController.class);

    private final UserRepository userRepository;
    private final AuthService authService;
    private final AuditLogService auditLogService;
    private final BulkUserService bulkUserService;

    public UserController(UserRepository userRepository, AuthService authService, AuditLogService auditLogService, BulkUserService bulkUserService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditLogService = auditLogService;
        this.bulkUserService = bulkUserService;
    }

    /**
     * List all users.
     *
     * @param includeDeleted when true, soft-deleted users are included in the response.
     *                       Defaults to false so deleted users are hidden unless explicitly requested.
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getAllUsers(
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        try {
            List<User> users = userRepository.findAll(includeDeleted);
            return ResponseEntity.ok(users);
        } catch (TimeoutException e) {
            log.error("Timeout while fetching all users", e);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error fetching all users", e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get user by UID.
     *
     * F11: hides soft-deleted users by default to match GET /api/admin/users
     * semantics. Pre-fix this endpoint returned deleted users unconditionally,
     * so an admin UI calling /api/admin/users (count=N) and then
     * /api/admin/users/{uid} for each row saw inconsistent results.
     *
     * @param includeDeleted when true, returns the user even if soft-deleted.
     */
    @GetMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> getUserByUid(
            @PathVariable String uid,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        try {
            return userRepository.findByUid(uid)
                    // F11: deleted users return 404 unless explicitly opted in.
                    .filter(u -> includeDeleted || !u.isDeleted())
                    .map(ResponseEntity::ok)
                    .orElse(ResponseEntity.notFound().build());
        } catch (TimeoutException e) {
            log.error("Timeout while fetching user by UID: {}", uid, e);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error fetching user by UID: {}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Get users by role.
     *
     * F9: the role path param is normalised to canonical lowercase via
     * {@link Role#fromString(String)} before the Firestore query. Pre-fix the
     * raw param flowed straight to {@code whereEqualTo("role", role)}, so
     * /role/ADMIN matched zero documents post-D6 (all roles stored lowercase).
     * Unknown role strings fall back to {@link Role#USER} (Role.fromString
     * already implements least-privilege); to match the existing list-filter
     * semantics we return the result for the resolved canonical role.
     *
     * @param includeDeleted when true, soft-deleted users are included in the response.
     *                       Defaults to false so deleted users are hidden unless explicitly requested.
     */
    @GetMapping("/role/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getUsersByRole(
            @PathVariable String role,
            @RequestParam(required = false, defaultValue = "false") boolean includeDeleted) {
        // F9: canonical lowercase via Role enum (e.g. /role/ADMIN → "admin").
        String canonicalRole = Role.fromString(role).getValue();
        try {
            List<User> users = userRepository.findByRole(canonicalRole);
            // F11: hide soft-deleted users by default — matches GET /api/admin/users semantics.
            if (!includeDeleted) {
                users = users.stream().filter(u -> !u.isDeleted()).toList();
            }
            return ResponseEntity.ok(users);
        } catch (TimeoutException e) {
            log.error("Timeout while fetching users by role: {}", role, e);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error fetching users by role: {}", role, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Create new user (admin only)
     */
    @PostMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> createUser(
            @RequestBody CreateUserRequest request,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) {
        try {
            User user = authService.createUser(
                    request.email,
                    request.password,
                    request.displayName,
                    request.role,
                    currentUser.getUid()
            );
            try {
                auditLogService.log("user_created", "user", user.getUid(), currentUser);
            } catch (Exception auditEx) {
                log.error("Failed to audit user_created for uid={}", user.getUid(), auditEx);
            }
            return ResponseEntity.status(HttpStatus.CREATED).body(user);
        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update user role (admin only)
     */
    @PutMapping("/{uid}/role")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> updateUserRole(
            @PathVariable String uid,
            @RequestBody UpdateRoleRequest request,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) throws Exception {
        if (currentUser == null) {
            return ResponseEntity.status(401).build();
        }
        return ResponseEntity.ok(authService.updateUserRoleAsActor(uid, request.role, currentUser.getUid()));
    }

    /**
     * Update user status (legacy facade — delegates to block / unblock / softDelete / recover).
     *
     * Kept for back-compat with admin dashboard. Plan A safeguards (last-admin guard,
     * cache eviction, audit log, FB Auth sync) are enforced by the underlying lifecycle
     * methods now — see {@link AuthService#updateUserStatus(String, String, String, String)}.
     */
    @PutMapping("/{uid}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> updateUserStatus(
            @PathVariable String uid,
            @RequestBody UpdateStatusRequest request,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) throws Exception {
        if (currentUser == null) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        }
        // No try-catch here: domain exceptions (IllegalArgumentException → 400,
        // LastAdminException → 409) are mapped by GlobalExceptionHandler so legacy
        // callers get the right HTTP status instead of a swallowed 500.
        User user = authService.updateUserStatus(uid, request.status, currentUser.getUid(), request.reason);
        return ResponseEntity.ok(user);
    }

    /**
     * Soft-delete user (admin only)
     */
    @DeleteMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails actor,
            @RequestParam(required = false, defaultValue = "admin-action") String reason
    ) throws Exception {
        if (actor == null) return ResponseEntity.status(401).build();
        authService.softDeleteUser(uid, actor.getUid(), reason);
        return ResponseEntity.noContent().build();
    }

    /**
     * Recover a soft-deleted user (admin only)
     */
    @PostMapping("/{uid}/recover")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> recoverUser(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails actor
    ) throws Exception {
        if (actor == null) return ResponseEntity.status(401).build();
        authService.recoverUser(uid, actor.getUid());
        return ResponseEntity.noContent().build();
    }

    /**
     * Block a user (admin only)
     */
    @PostMapping("/{uid}/block")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> block(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails actor,
            @RequestBody(required = false) Map<String, String> body
    ) throws Exception {
        if (actor == null) return ResponseEntity.status(401).build();
        String reason = body != null ? body.getOrDefault("reason", "policy-violation") : "policy-violation";
        authService.blockUser(uid, actor.getUid(), reason);
        return ResponseEntity.noContent().build();
    }

    /**
     * Unblock a user (admin only)
     */
    @PostMapping("/{uid}/unblock")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> unblock(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails actor
    ) throws Exception {
        if (actor == null) return ResponseEntity.status(401).build();
        authService.unblockUser(uid, actor.getUid());
        return ResponseEntity.noContent().build();
    }

    /**
     * Send password reset email
     */
    @PostMapping("/{uid}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> sendPasswordReset(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) {
        try {
            User user = userRepository.findByUid(uid).orElse(null);
            if (user == null) {
                return ResponseEntity.notFound().build();
            }
            authService.sendPasswordResetEmail(user.getEmail());
            try {
                auditLogService.log("user_password_reset", "user", uid, currentUser);
            } catch (Exception auditEx) {
                log.error("Failed to audit user_password_reset for uid={}", uid, auditEx);
            }
            return ResponseEntity.ok().build();
        } catch (TimeoutException e) {
            log.error("Timeout while sending password reset for uid: {}", uid, e);
            return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT).build();
        } catch (ExecutionException | InterruptedException e) {
            log.error("Error sending password reset for uid: {}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        } catch (FirebaseAuthException e) {
            log.error("Firebase auth error for password reset, uid: {}", uid, e);
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/{uid}/revoke-sessions")
    public ResponseEntity<Void> revokeSessions(
            @PathVariable String uid,
            @Valid @RequestBody(required = false) RevokeSessionsRequest body,
            @AuthenticationPrincipal FirebaseUserDetails actor) {
        if (actor == null) return ResponseEntity.status(401).build();
        try {
            String reason = body != null ? body.getReason() : null;
            authService.revokeSessions(uid, actor, reason);
            return ResponseEntity.noContent().build();
        } catch (FirebaseAuthException e) {
            log.error("revoke-sessions failed uid={}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-block")
    public ResponseEntity<BulkUserActionResult> bulkBlock(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        if (actor == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.BLOCK, req.getUids(), actor, req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-delete")
    public ResponseEntity<BulkUserActionResult> bulkDelete(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        if (actor == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.DELETE, req.getUids(), actor, req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-recover")
    public ResponseEntity<BulkUserActionResult> bulkRecover(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        if (actor == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.RECOVER, req.getUids(), actor, req.getReason()));
    }

    @PreAuthorize("hasRole('ADMIN')")
    @PostMapping("/bulk-revoke-sessions")
    public ResponseEntity<BulkUserActionResult> bulkRevokeSessions(
            @Valid @RequestBody BulkUserActionRequest req,
            @AuthenticationPrincipal com.albunyaan.tube.security.FirebaseUserDetails actor) {
        if (actor == null) return ResponseEntity.status(401).build();
        return ResponseEntity.ok(bulkUserService.execute(
                BulkAction.REVOKE_SESSIONS, req.getUids(), actor, req.getReason()));
    }

    // DTOs

    public static class CreateUserRequest {
        public String email;
        public String password;
        public String displayName;
        public String role; // "admin" | "moderator"
    }

    public static class UpdateRoleRequest {
        public String role; // "admin" | "moderator"
    }

    public static class UpdateStatusRequest {
        public String status; // "active" | "blocked" | "deleted"
        public String reason; // required for "blocked" and "deleted"; ignored for "active"
    }
}

