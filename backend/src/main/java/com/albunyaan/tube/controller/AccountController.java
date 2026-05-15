package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.AccountMeResponse;
import com.albunyaan.tube.dto.CompleteProfileRequest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.AgeIneligibleAbortedException;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.albunyaan.tube.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

/**
 * Plan C T3: account bootstrap endpoints.
 *
 * POST /api/account/profile — complete profile for a PENDING_PROFILE user.
 * GET  /api/account/me      — return the authenticated caller's profile.
 *
 * Both endpoints require a valid Firebase ID token (FirebaseAuthFilter runs on
 * /api/account/* — it is NOT in the shouldNotFilter exempt list).
 */
@RestController
@RequestMapping("/api/account")
public class AccountController {

    private final AccountProfileService accountProfileService;
    private final UserRepository userRepository;

    public AccountController(AccountProfileService accountProfileService,
                              UserRepository userRepository) {
        this.accountProfileService = accountProfileService;
        this.userRepository = userRepository;
    }

    @PostMapping("/profile")
    public ResponseEntity<?> completeProfile(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @Valid @RequestBody CompleteProfileRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        var saved = accountProfileService.completeProfile(
                principal.getUid(), req.getDisplayName(), req.getDateOfBirth());
        return ResponseEntity.ok(AccountMeResponse.from(saved));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(
            @AuthenticationPrincipal FirebaseUserDetails principal)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        String uid = principal.getUid();
        // Atomic get-or-create via Firestore transaction (cubic R4 P2): two
        // concurrent first-time /me callers can no longer both observe
        // "absent" and both blindly upsert. The loser's createdAt /
        // lifecycle fields used to be silently clobbered.
        //
        // Cubic R7 P1 — preserve role on lazy-create.
        //
        // Pre-fix the lazy-create hardcoded role="user". For a real first-time
        // user that's correct, but the path also fires when an existing
        // admin's Firestore doc went missing (operator error, half-applied
        // migration, manual cleanup gone wrong). The admin's Firebase custom
        // claim is still "admin" — the token shows it via principal.getRole()
        // — but the new Firestore doc was minted as plain "user" with no audit
        // signal, silently demoting them until an operator noticed and fixed
        // the row by hand. Read the claim from the verified ID token and use
        // that as the seed role; fall back to "user" only when no claim is
        // present (true first-time sign-in).
        final String seedRole = principal.getRole() != null && !principal.getRole().isBlank()
                ? principal.getRole()
                : "user";
        User user = userRepository.getOrCreate(uid, () -> {
            User fresh = new User(uid, principal.getEmail(), null, seedRole);
            fresh.setStatusEnum(UserStatus.PENDING_PROFILE);
            return fresh;
        });
        return ResponseEntity.ok(AccountMeResponse.from(user));
    }

    /**
     * Typed wrappers for {@code lazy-create} failures inside the {@code orElseGet}
     * lambda — checked exceptions cannot escape a {@code Supplier}, so we wrap
     * with typed unchecked exceptions and map them back to the right HTTP
     * status via the handlers below.
     */
    public static class LazyCreateTimeoutException extends RuntimeException {
        public LazyCreateTimeoutException(String uid, Throwable cause) {
            super("lazy-create timeout for uid=" + uid, cause);
        }
    }
    public static class LazyCreateExecutionException extends RuntimeException {
        public LazyCreateExecutionException(String uid, Throwable cause) {
            super("lazy-create execution failure for uid=" + uid, cause);
        }
    }
    public static class LazyCreateInterruptedException extends RuntimeException {
        public LazyCreateInterruptedException(String uid, Throwable cause) {
            super("lazy-create interrupted for uid=" + uid, cause);
        }
    }

    @ExceptionHandler(LazyCreateTimeoutException.class)
    public ResponseEntity<Map<String, String>> handleLazyCreateTimeout(LazyCreateTimeoutException e) {
        return ResponseEntity.status(HttpStatus.GATEWAY_TIMEOUT)
                .body(Map.of("code", "LAZY_CREATE_TIMEOUT",
                             "message", "Account bootstrap timed out. Please try again."));
    }

    @ExceptionHandler({LazyCreateExecutionException.class, LazyCreateInterruptedException.class})
    public ResponseEntity<Map<String, String>> handleLazyCreateFailure(RuntimeException e) {
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "LAZY_CREATE_FAILED",
                             "message", "Account bootstrap failed. Please try again."));
    }

    // ── Exception handlers ─────────────────────────────────────────────────

    @ExceptionHandler(AgeIneligibleException.class)
    public ResponseEntity<Map<String, String>> handleAgeIneligible(AgeIneligibleException e) {
        return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
                .body(Map.of("code", "AGE_INELIGIBLE",
                             "message", "FitrahTube is for users 13 and older."));
    }

    @ExceptionHandler(AgeIneligibleAbortedException.class)
    public ResponseEntity<Map<String, String>> handleAgeIneligibleAborted(AgeIneligibleAbortedException e) {
        // Plan C T12 fix: distinct 500 with machine-readable code so the
        // Android client doesn't conflate this with generic SAVE_FAILED.
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "AGE_INELIGIBLE_ABORTED",
                             "message", "Account rejection could not be completed. Please try again."));
    }

    @ExceptionHandler(ProfileAlreadyCompletedException.class)
    public ResponseEntity<Map<String, String>> handleAlreadyCompleted(ProfileAlreadyCompletedException e) {
        return ResponseEntity.status(HttpStatus.CONFLICT)
                .body(Map.of("code", "PROFILE_ALREADY_COMPLETED",
                             "message", "Profile already completed."));
    }

    @ExceptionHandler(UserNotFoundException.class)
    public ResponseEntity<Map<String, String>> handleNotFound(UserNotFoundException e) {
        return ResponseEntity.status(HttpStatus.NOT_FOUND)
                .body(Map.of("code", "USER_NOT_FOUND",
                             "message", "Account not found."));
    }

    @ExceptionHandler(IllegalArgumentException.class)
    public ResponseEntity<Map<String, String>> handleBadInput(IllegalArgumentException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST",
                             "message", e.getMessage()));
    }

    @ExceptionHandler(HttpMessageNotReadableException.class)
    public ResponseEntity<Map<String, String>> handleUnreadableBody(HttpMessageNotReadableException e) {
        return ResponseEntity.status(HttpStatus.BAD_REQUEST)
                .body(Map.of("code", "BAD_REQUEST",
                             "message", "Malformed or unreadable request body"));
    }
}
