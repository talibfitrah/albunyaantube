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
import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import com.google.common.net.InetAddresses;
import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.albunyaan.tube.service.AgeIneligibleAbortedException;
import com.albunyaan.tube.service.AgeIneligibleException;
import com.albunyaan.tube.service.ProfileAlreadyCompletedException;
import com.albunyaan.tube.service.ProfileValidationException;
import com.albunyaan.tube.service.UserNotFoundException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;

import java.net.Inet6Address;
import java.net.InetAddress;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HexFormat;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

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
    private static final long RECENT_LOGIN_MAX_AGE_S = 300;
    private static final int RESET_MAX_PER_IP_PER_HOUR = 20;
    private static final long RESET_EMAIL_COOLDOWN_MS = 60_000L;
    private static final int RESET_MAX_PER_EMAIL_PER_DAY = 10;
    private static final long DAY_MS = 86_400_000L;

    private final ConcurrentHashMap<String, Long> verificationCooldowns = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Long> changeEmailCooldowns = new ConcurrentHashMap<>();
    // Anonymous keys (any email, any IP), so bounded + expiring unlike the per-uid maps above.
    // Same Caffeine counter as reportRateLimitCache (CacheConfig).
    // ponytail: in-memory per instance; move to Redis if the backend ever runs more than one.
    private final Cache<String, AtomicInteger> resetIpAttempts = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.HOURS)
            .maximumSize(100_000)
            .build();
    private final Cache<String, Deque<Long>> resetMailSends = Caffeine.newBuilder()
            .expireAfterAccess(1, TimeUnit.DAYS)
            .maximumSize(100_000)
            .build();
    private final AccountProfileService accountProfileService;
    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final MailService mailService;
    private final com.albunyaan.tube.service.AuthService authService;
    private final Executor passwordResetExecutor;

    public AccountController(AccountProfileService accountProfileService,
                              UserRepository userRepository,
                              FirebaseAuth firebaseAuth,
                              MailService mailService,
                              com.albunyaan.tube.service.AuthService authService,
                              @Qualifier("passwordResetExecutor") Executor passwordResetExecutor) {
        this.accountProfileService = accountProfileService;
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.mailService = mailService;
        this.authService = authService;
        this.passwordResetExecutor = passwordResetExecutor;
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
        // CF-A-57 (Cubic P3): with mail off nothing can carry the link, so do not spend a
        // Firebase Admin call minting one. Still record the cooldown -- the apps treat any 503
        // as "fall back to Firebase's mailer and wait 60 s", so the throttle stays one rule.
        if (!mailService.isEnabled()) {
            verificationCooldowns.put(uid, System.currentTimeMillis());
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "MAIL_UNAVAILABLE",
                                 "message", "Verification email could not be sent"));
        }
        try {
            String link = firebaseAuth.generateEmailVerificationLink(email);
            // Cooldown BEFORE the outcome is known: both apps wait 60 s before the next tap and
            // fall back to Firebase's own mailer on the 503 below, so a retry window here would
            // only let one uid loop generateEmailVerificationLink (Firebase Admin quota) while
            // mail is down.
            verificationCooldowns.put(uid, System.currentTimeMillis());
            // A mailer that is disabled (mail.enabled=false) or refused by Graph reports false.
            // That must be a non-2xx: both apps fall back to Firebase's own verification mail
            // only on a failure, and a 200 here left the owner's first real account stuck on
            // the verification screen with no email at all (2026-09-21).
            if (!mailService.sendEmailVerification(email, link)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("code", "MAIL_UNAVAILABLE",
                                     "message", "Verification email could not be sent"));
            }
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

    /**
     * Signed-out forgot-password (permitted anonymously in SecurityConfig). Nothing here depends on
     * whether the address has an account: the body is identical, the per-address limits DROP rather
     * than refuse, and the link is minted and mailed on passwordResetExecutor, never on this thread.
     * 503 only when mail is disabled -- a server setting -- so the apps fall back to Firebase's mailer.
     */
    @PostMapping("/send-password-reset-email")
    public ResponseEntity<?> sendPasswordResetEmail(@Valid @RequestBody PasswordResetRequest body,
                                                    HttpServletRequest request) {
        // The one limit that may answer 429: it is about the caller, never about the address.
        String ipKey = clientIpKey(request.getHeader("X-Real-IP"), request.getRemoteAddr());
        if (resetIpAttempts.get(ipKey, k -> new AtomicInteger()).incrementAndGet() > RESET_MAX_PER_IP_PER_HOUR) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", "RATE_LIMITED", "message", "Please wait before requesting another email"));
        }
        if (!mailService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "MAIL_UNAVAILABLE", "message", "Password reset email could not be sent"));
        }
        String email = body.email();
        if (resetMailAllowed(email, System.currentTimeMillis())) {
            passwordResetExecutor.execute(() -> authService.sendPasswordResetEmailQuietly(email));
        }
        return ResponseEntity.ok(Map.of("message", "If an account exists for that email, a reset link is on its way"));
    }

    /** Per address: one mail a minute, ten a day (sliding). Over either, the send is dropped and the
     *  caller still gets the same 200 -- a refusal would lock the owner out and answer a prober. */
    boolean resetMailAllowed(String email, long nowMs) {
        boolean[] allowed = {false};
        resetMailSends.asMap().compute(email.toLowerCase(Locale.ROOT), (k, sent) -> {
            Deque<Long> sends = sent != null ? sent : new ArrayDeque<>();
            while (!sends.isEmpty() && nowMs - sends.peekFirst() >= DAY_MS) sends.pollFirst();
            if (sends.size() < RESET_MAX_PER_EMAIL_PER_DAY
                    && (sends.isEmpty() || nowMs - sends.peekLast() >= RESET_EMAIL_COOLDOWN_MS)) {
                sends.addLast(nowMs);
                allowed[0] = true;
            }
            return sends;
        });
        return allowed[0];
    }

    /**
     * Rate-limit key for the caller. X-Real-IP is set by nginx's realip module from Cloudflare's
     * published ranges only; CF-Connecting-IP is NOT read, since the origin is public and anyone can
     * send it. A value over 45 chars (the longest IP text) or not an IP literal falls back to the
     * socket peer. IPv6 is keyed by its /64, which one subscriber usually holds whole.
     */
    static String clientIpKey(String realIp, String remoteAddr) {
        InetAddress ip = ipLiteral(realIp);
        if (ip == null) ip = ipLiteral(remoteAddr);
        if (ip == null) return "ip:" + remoteAddr;
        if (ip instanceof Inet6Address) return "ip6:" + HexFormat.of().formatHex(ip.getAddress(), 0, 8);
        return "ip:" + ip.getHostAddress();
    }

    /** Guava's parser: a literal or nothing, never a DNS lookup on a header value. */
    private static InetAddress ipLiteral(String s) {
        return s != null && s.length() <= 45 && InetAddresses.isInetAddress(s) ? InetAddresses.forString(s) : null;
    }

    /**
     * Change email: mails a verifyAndChangeEmail link for the CALLER's account to the NEW address;
     * the email changes only when it is opened. 503 rules as send-verification-email above.
     */
    @PostMapping("/send-change-email-verification")
    public ResponseEntity<?> sendChangeEmailVerification(
            @AuthenticationPrincipal FirebaseUserDetails principal,
            @Valid @RequestBody ChangeEmailRequest body) {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        // Firebase's verifyBeforeUpdateEmail enforced requires-recent-login; so must this, or any live
        // token (stolen, or a device left signed in) could move the email and take the account. The
        // apps re-authenticate and force-refresh the token first, so auth_time is seconds old.
        Long authTime = principal.getAuthTime();
        if (authTime == null || System.currentTimeMillis() / 1000 - authTime > RECENT_LOGIN_MAX_AGE_S) {
            return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                    .body(Map.of("code", "REQUIRES_RECENT_LOGIN", "message", "Sign in again to change your email"));
        }
        String uid = principal.getUid();
        Long lastSent = changeEmailCooldowns.get(uid);
        if (lastSent != null && System.currentTimeMillis() - lastSent < VERIFICATION_COOLDOWN_MS) {
            return ResponseEntity.status(HttpStatus.TOO_MANY_REQUESTS)
                    .body(Map.of("code", "RATE_LIMITED", "message", "Please wait before requesting another email"));
        }
        // Every attempt past this point costs the cooldown, whatever it answers: a 409 says "that
        // address has an account" and must not be free to repeat.
        changeEmailCooldowns.put(uid, System.currentTimeMillis());
        if (!mailService.isEnabled()) {
            return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                    .body(Map.of("code", "MAIL_UNAVAILABLE", "message", "Confirmation email could not be sent"));
        }
        try {
            // The uid's record, not the token's email claim: a claim up to an hour stale could name
            // an address that now belongs to another account, and the link would change THAT one.
            String currentEmail = firebaseAuth.getUser(uid).getEmail();
            if (currentEmail == null || currentEmail.isBlank()) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "NO_EMAIL", "message", "Account has no email address"));
            }
            String link = authService.generateVerifyAndChangeEmailLink(currentEmail, body.newEmail());
            if (!mailService.sendEmailChangeVerification(body.newEmail(), link)) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE)
                        .body(Map.of("code", "MAIL_UNAVAILABLE", "message", "Confirmation email could not be sent"));
            }
            return ResponseEntity.ok(Map.of("message", "Confirmation email sent"));
        } catch (FirebaseAuthException e) {
            if (e.getAuthErrorCode() == AuthErrorCode.EMAIL_ALREADY_EXISTS) {
                return ResponseEntity.status(HttpStatus.CONFLICT)
                        .body(Map.of("code", "EMAIL_IN_USE", "message", "That email is already in use"));
            }
            if (e.getErrorCode() == ErrorCode.INVALID_ARGUMENT) {
                return ResponseEntity.badRequest()
                        .body(Map.of("code", "INVALID_EMAIL", "message", "That email address is not valid"));
            }
            logger.error("send-change-email-verification failed uid={}", uid, e);
        } catch (Exception e) {
            logger.error("send-change-email-verification failed uid={}", uid, e);
        }
        return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                .body(Map.of("code", "CHANGE_EMAIL_FAILED", "message", "Could not send confirmation email"));
    }

    public record PasswordResetRequest(@NotBlank @Email @Size(max = 254) String email) {}

    public record ChangeEmailRequest(@NotBlank @Email @Size(max = 254) String newEmail) {}

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
     * Self-serve, permanent account deletion — the in-app half of Google Play
     * policy 13327111. The public web half (for users who already uninstalled)
     * is {@code GET /delete-account}, served by {@link LegalPagesController}.
     *
     * <p>Deletes only the caller's own account: the uid comes from the verified
     * ID token, never from the request, so there is no target to tamper with.
     * Answers 204 on both the first call and an idempotent retry;
     * {@link com.albunyaan.tube.exception.LastAdminException} maps to 409 via
     * {@code GlobalExceptionHandler}.
     */
    @DeleteMapping("/me")
    public ResponseEntity<Void> deleteMe(
            @AuthenticationPrincipal FirebaseUserDetails principal) throws Exception {
        if (principal == null) return ResponseEntity.status(HttpStatus.UNAUTHORIZED).build();
        authService.deleteAccountPermanently(principal.getUid());
        return ResponseEntity.noContent().build();
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
