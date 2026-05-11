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
        var saved = accountProfileService.completeProfile(
                principal.getUid(), req.getDisplayName(), req.getDateOfBirth());
        return ResponseEntity.ok(AccountMeResponse.from(saved));
    }

    @GetMapping("/me")
    public ResponseEntity<?> getMe(
            @AuthenticationPrincipal FirebaseUserDetails principal)
            throws ExecutionException, InterruptedException, TimeoutException {
        String uid = principal.getUid();
        User user = userRepository.findByUid(uid).orElseGet(() -> {
            // Plan C T12 fix: lazy-create on first /api/account/me hit (per
            // FirebaseAuthFilter:110 "Plan C will create it" contract).
            User fresh = new User(uid, principal.getEmail(), null, "user");
            fresh.setStatusEnum(UserStatus.PENDING_PROFILE);
            try {
                return userRepository.save(fresh);
            } catch (ExecutionException | InterruptedException | TimeoutException e) {
                if (e instanceof InterruptedException) Thread.currentThread().interrupt();
                throw new RuntimeException("lazy-create failed for uid=" + uid, e);
            }
        });
        return ResponseEntity.ok(AccountMeResponse.from(user));
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
