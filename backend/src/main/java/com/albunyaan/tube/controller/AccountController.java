package com.albunyaan.tube.controller;

import com.albunyaan.tube.dto.AccountMeResponse;
import com.albunyaan.tube.dto.CompleteProfileRequest;
import com.albunyaan.tube.dto.UpdateProfileRequest;
import com.albunyaan.tube.model.Role;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.security.FirebaseUserDetails;
import com.albunyaan.tube.service.AccountProfileService;
import com.albunyaan.tube.service.MailService;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.albunyaan.tube.service.AgeIneligibleAbortedException;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.albunyaan.tube.service.ProfileValidationException;
import com.albunyaan.tube.service.UserNotFoundException;
import jakarta.validation.Valid;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
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

    private static final Logger logger = LoggerFactory.getLogger(AccountController.class);
    private static final long VERIFICATION_COOLDOWN_MS = 60_000L;

    private final ConcurrentHashMap<String, Long> verificationCooldowns = new ConcurrentHashMap<>();
    private final AccountProfileService accountProfileService;
    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final MailService mailService;

    public AccountController(AccountProfileService accountProfileService,
                              UserRepository userRepository,
                              FirebaseAuth firebaseAuth,
                              MailService mailService) {
        this.accountProfileService = accountProfileService;
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.mailService = mailService;
    }

    @PostMapping("/profile")
    public ResponseEntity<?> completeProfile(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @Valid @RequestBody CompleteProfileRequest req)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // Reviewer-flagged: client-only email gate is bypassed via curl. The
        // EmailVerificationFragment is a UX-layer enforcement; the backend
        // is the source of truth.
        if (!principal.isEmailVerified()) {
            // Google/Microsoft tokens always have emailVerified=true; if false,
            // the user is on email/password and hasn't clicked the link.
            return ResponseEntity.status(HttpStatus.FORBIDDEN)
                    .body(Map.of("code", "EMAIL_NOT_VERIFIED", "message", "Verify your email first"));
        }
        var saved = accountProfileService.completeProfile(
                principal.getUid(), req.getDisplayName(), req.getDateOfBirth(), req.getPhoneNumber());
        return ResponseEntity.ok(AccountMeResponse.from(saved));
    }

    /** Plan G B3 — partial profile update for an authenticated ACTIVE user. */
    @PutMapping("/profile")
    public ResponseEntity<AccountMeResponse> updateProfile(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @Valid @RequestBody UpdateProfileRequest body)
            throws ExecutionException, InterruptedException, TimeoutException {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        return ResponseEntity.ok(accountProfileService.updateProfile(principal.getUid(), body));
    }

    @PostMapping("/send-verification-email")
    public ResponseEntity<?> sendVerificationEmail(
            @AuthenticationPrincipal FirebaseUserDetails principal) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        if (principal.isEmailVerified()) {
            return ResponseEntity.ok(Map.of("message", "Email already verified"));
        }
        String email = principal.getEmail();
        if (email == null || email.isBlank()) {
            return ResponseEntity.badRequest()
                    .body(Map.of("code", "NO_EMAIL", "message", "Account has no email address"));
        }
        String uid = principal.getUid();
        Long lastSent = verificationCooldowns.get(uid);
        if (lastSent != null && System.currentTimeMillis() - lastSent < VERIFICATION_COOLDOWN_MS) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", "RATE_LIMITED", "message", "Please wait before requesting another email"));
        }
        try {
            String link = firebaseAuth.generateEmailVerificationLink(email);
            mailService.sendEmailVerification(email, link);
            verificationCooldowns.put(uid, System.currentTimeMillis());
            return ResponseEntity.ok(Map.of("message", "Verification email sent"));
        } catch (FirebaseAuthException e) {
            logger.error("send-verification-email failed uid={}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "VERIFICATION_EMAIL_FAILED",
                                 "message", "Could not send verification email"));
        } catch (Exception e) {
            logger.error("send-verification-email failed uid={}", uid, e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body(Map.of("code", "VERIFICATION_EMAIL_FAILED",
                                 "message", "Could not send verification email"));
        }
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
        // Cubic R-final7 P2 — normalise through Role.fromString instead of
        // persisting the raw principal value. The verified ID-token claim
        // SHOULD be a canonical enum value, but defensive normalisation
        // protects against a future custom-claim mint that bypassed
        // setUserRoleClaim's enum gate (e.g., a one-off migration script).
        // Unknown values log + downgrade to USER via Role.fromString.
        final String rawRoleClaim = principal.getRole();
        final String seedRole = (rawRoleClaim != null && !rawRoleClaim.isBlank())
                ? Role.fromString(rawRoleClaim).getValue()
                : Role.USER.getValue();
        // Cubic R-final2 P2 — wire the typed Lazy* envelope. Pre-fix the
        // checked exceptions from getOrCreate were declared throws and the
        // Lazy* classes + their @ExceptionHandler mappings were unreachable
        // dead code. Translating here lets the @ExceptionHandler differentiate
        // a lazy-create timeout (504) from a generic Firestore timeout (500).
        final User user;
        try {
            user = userRepository.getOrCreate(uid, () -> {
                // Cubic R-final4 P3 — warn on non-user lazy-create. When a
                // pre-existing admin/moderator's Firestore doc went missing
                // (operator error, half-applied migration, manual cleanup),
                // their Firebase Auth custom claim survives and this branch
                // mints a fresh doc with role=admin/moderator + status=
                // PENDING_PROFILE. Firestore rules require status==active
                // for isAdmin()/isModerator(), so the user can't yet
                // exercise privileges — safe by design — but an admin in
                // PENDING_PROFILE in the admin dashboard's user list is
                // anomalous and should ping the operator.
                if (!"user".equals(seedRole)) {
                    logger.warn("Lazy-create recovery: uid={} email={} seedRole={} "
                            + "minted as PENDING_PROFILE. Indicates the user's "
                            + "Firestore row was missing but their Firebase Auth "
                            + "custom claim survived. Investigate who/when the row "
                            + "disappeared.",
                            uid, principal.getEmail(), seedRole);
                }
                User fresh = new User(uid, principal.getEmail(), null, seedRole);
                fresh.setStatusEnum(UserStatus.PENDING_PROFILE);
                return fresh;
            });
        } catch (TimeoutException e) {
            throw new LazyCreateTimeoutException(uid, e);
        } catch (ExecutionException e) {
            throw new LazyCreateExecutionException(uid, e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LazyCreateInterruptedException(uid, e);
        }
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

    @ExceptionHandler(ProfileValidationException.class)
    public ResponseEntity<Map<String, String>> handleProfileValidation(ProfileValidationException e) {
        return ResponseEntity.badRequest().body(
                Map.of("code", "VALIDATION",
                       "message", e.getField() + ": " + e.getReason()));
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
