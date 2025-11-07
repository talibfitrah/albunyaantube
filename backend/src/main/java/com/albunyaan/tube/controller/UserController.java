package com.albunyaan.tube.controller;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AuditLogService;
import com.albunyaan.tube.service.AuthService;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.concurrent.ExecutionException;

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

    public UserController(UserRepository userRepository, AuthService authService, AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.authService = authService;
        this.auditLogService = auditLogService;
    }

    /**
     * List all users
     */
    @GetMapping
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getAllUsers() throws ExecutionException, InterruptedException {
        List<User> users = userRepository.findAll();
        return ResponseEntity.ok(users);
    }

    /**
     * Get user by UID
     */
    @GetMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> getUserByUid(@PathVariable String uid)
            throws ExecutionException, InterruptedException {
        return userRepository.findByUid(uid)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    /**
     * Get users by role
     */
    @GetMapping("/role/{role}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<List<User>> getUsersByRole(@PathVariable String role)
            throws ExecutionException, InterruptedException {
        List<User> users = userRepository.findByRole(role);
        return ResponseEntity.ok(users);
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
    ) {
        try {
            User user = authService.updateUserRole(uid, request.role);
            try {
                auditLogService.log("user_role_updated", "user", uid, currentUser);
            } catch (Exception auditEx) {
                log.error("Failed to audit user_role_updated for uid={}", uid, auditEx);
            }
            return ResponseEntity.ok(user);
        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Update user status (activate/deactivate)
     */
    @PutMapping("/{uid}/status")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<User> updateUserStatus(
            @PathVariable String uid,
            @RequestBody UpdateStatusRequest request,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) {
        try {
            User user = authService.updateUserStatus(uid, request.status);
            try {
                auditLogService.log("user_status_updated", "user", uid, currentUser);
            } catch (Exception auditEx) {
                log.error("Failed to audit user_status_updated for uid={}", uid, auditEx);
            }
            return ResponseEntity.ok(user);
        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Delete user (admin only)
     */
    @DeleteMapping("/{uid}")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> deleteUser(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) {
        try {
            authService.deleteUser(uid);
            try {
                auditLogService.log("user_deleted", "user", uid, currentUser);
            } catch (Exception auditEx) {
                log.error("Failed to audit user_deleted for uid={}", uid, auditEx);
            }
            return ResponseEntity.noContent().build();
        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        } catch (Exception e) {
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }

    /**
     * Send password reset email
     */
    @PostMapping("/{uid}/reset-password")
    @PreAuthorize("hasRole('ADMIN')")
    public ResponseEntity<Void> sendPasswordReset(
            @PathVariable String uid,
            @AuthenticationPrincipal FirebaseUserDetails currentUser
    ) throws ExecutionException, InterruptedException {
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
        } catch (FirebaseAuthException e) {
            return ResponseEntity.status(HttpStatus.BAD_REQUEST).build();
        }
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
        public String status; // "active" | "inactive"
    }
}

