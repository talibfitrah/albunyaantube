package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.AccountMeResponse;
import com.albunyaan.tube.dto.UpdateProfileRequest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import java.time.Clock;
import java.time.LocalDate;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;
import java.util.regex.Pattern;

@Service
public class AccountProfileService {

    private static final Logger log = LoggerFactory.getLogger(AccountProfileService.class);
    private static final int MIN_AGE = 13;
    private static final int MAX_DISPLAY_NAME_LENGTH = 40;
    private static final Pattern CONTROL_CHARS = Pattern.compile("\\p{Cntrl}");
    /** Plan G cubic R1 P2 — Unicode format-control category (Cf). */
    private static final Pattern FORMAT_CONTROL_CHARS = Pattern.compile("\\p{Cf}");
    private static final Pattern URL_PATTERN   = Pattern.compile("https?://", Pattern.CASE_INSENSITIVE);

    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final Clock clock;
    // Cubic R7 P1 — orphan audit. Injected so rejectUnderAge can emit an
    // observability row when the soft-delete write fails after token revoke.
    private final AuditLogService auditLogService;

    public AccountProfileService(UserRepository userRepository,
                                  FirebaseAuth firebaseAuth,
                                  Clock clock,
                                  AuditLogService auditLogService) {
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.clock = clock;
        this.auditLogService = auditLogService;
    }

    public User completeProfile(String uid, String displayName, LocalDate dateOfBirth)
            throws ExecutionException, InterruptedException, TimeoutException {
        validateDisplayName(displayName);
        validateDateOfBirth(dateOfBirth);

        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new UserNotFoundException(uid));

        if (user.getProfileCompletedAt() != null) {
            // Cubic R5 P1 #9 — idempotent on retry. The previous shape threw
            // 409 unconditionally; if the first call wrote the profile and
            // then the response failed in flight (network blip, mobile
            // suspend), the client's automatic retry hit a permanent 409 and
            // the user was locked out of profile editing without admin help.
            // Now, if the retry's payload matches the persisted profile we
            // return the existing user as a 200; if it differs we still
            // refuse (the profile is locked once set).
            if (profileMatches(user, displayName, dateOfBirth)) {
                return user;
            }
            throw new ProfileAlreadyCompletedException(uid);
        }

        enforceAgeOrReject(uid, dateOfBirth);

        Timestamp dobTs = Timestamp.ofTimeSecondsAndNanos(
                dateOfBirth.atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 0);
        user.setDisplayName(displayName.trim());
        user.setDateOfBirth(dobTs);
        user.setStatusEnum(UserStatus.ACTIVE);
        user.setProfileCompletedAt(Timestamp.now());
        user.touch();
        return userRepository.save(user);
    }

    /**
     * Check that {@code dateOfBirth} implies the user is at least {@link #MIN_AGE} years old.
     * If under-age, delegates to {@link #rejectUnderAge(String)} (revoke + soft-delete) and
     * then throws {@link AgeIneligibleException}. Call this after the idempotency check so
     * a completed profile is never re-evaluated.
     */
    private void enforceAgeOrReject(String uid, LocalDate dateOfBirth) {
        int age = Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        if (age < MIN_AGE) {
            rejectUnderAge(uid);
            throw new AgeIneligibleException(uid, age);
        }
    }

    /**
     * Revoke FIRST, then soft-delete (Cubic R5 P1 #11).
     *
     * <p>Pre-fix this method hard-deleted the Firestore doc. After a fresh
     * sign-in the user minted a new ID token, the FirebaseAuthFilter found
     * no Firestore doc ("first-time user — allow"), and {@code getMe}
     * lazy-created a new PENDING_PROFILE row. The under-13 rejection was
     * therefore bypassable by simply retrying with a different DOB.
     *
     * <p>Soft-delete with {@code deleteReason="age-ineligible"} keeps the
     * tombstone in Firestore. On the retry sign-in the filter sees
     * {@code isDeleted()=true} and emits the 403 ACCOUNT_DELETED envelope
     * the client already handles.
     *
     * <p>If revoke fails we abort without writing — the user retains a valid
     * token but their doc is intact, so the operator's retry can succeed.
     * If we wrote first and revoke failed, a stale ID token could be used
     * to re-bootstrap from a different device before the tombstone was
     * visible.
     */
    private void rejectUnderAge(String uid) {
        try {
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            log.error("AGE_INELIGIBLE: revokeRefreshTokens failed for uid={}, aborting", uid, e);
            throw new AgeIneligibleAbortedException(uid, e);
        }
        // Plan G review-fix: revoke alone leaves the Firebase Auth account
        // enabled; the user can re-authenticate, get fresh tokens, and (if
        // the soft-delete below fails) the FirebaseAuthFilter sees an ACTIVE
        // user doc and lets them call updateProfile with an adult DOB —
        // self-recovering from age-ineligibility. Disable the Firebase Auth
        // record so the bypass closes even when the Firestore write fails.
        try {
            firebaseAuth.updateUser(new com.google.firebase.auth.UserRecord.UpdateRequest(uid)
                    .setDisabled(true));
        } catch (FirebaseAuthException e) {
            log.error("AGE_INELIGIBLE: disable Firebase Auth account failed for uid={} "
                    + "(refresh tokens already revoked, account stays enabled — re-auth-bypass possible)",
                    uid, e);
            try {
                auditLogService.logSystem(
                        "USER_AGE_INELIGIBLE_DISABLE_FAILED",
                        "user", uid,
                        "disable-failed: " + e.getClass().getSimpleName());
            } catch (RuntimeException auditEx) {
                log.error("AGE_INELIGIBLE: orphan audit emission also failed uid={}", uid, auditEx);
            }
            throw new AgeIneligibleAbortedException(uid, e);
        }
        try {
            User user = userRepository.findByUid(uid)
                    .orElseThrow(() -> new UserNotFoundException(uid));
            // recordSoftDelete sets status=DELETED, deletedAt=now, deleteReason.
            // The actor is the system (no admin invoked this), so we use the
            // user's own uid as actorUid to avoid a null-actor audit row.
            user.recordSoftDelete(uid, "age-ineligible");
            userRepository.save(user);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // Tokens already revoked but soft-delete failed. The user cannot
            // mint new ID tokens until they re-authenticate; on next sign-in
            // they'll be in a half-state (token revoked, doc still ACTIVE).
            // The operator can clean up via the admin user-deletion path.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("AGE_INELIGIBLE: soft-delete failed for uid={} (refresh tokens already revoked)", uid, e);
            // Cubic R7 P1 — emit orphan audit row so operators can find this
            // half-state user without grepping logs. The user.recordSoftDelete
            // write failed but tokens are revoked; surface the abort with the
            // failure class so the on-call dashboard can route it to the
            // cleanup queue.
            try {
                auditLogService.logSystem(
                        "USER_AGE_INELIGIBLE_ABORTED",
                        "user", uid,
                        "save-failed: " + e.getClass().getSimpleName());
            } catch (RuntimeException auditEx) {
                log.error("AGE_INELIGIBLE: orphan audit emission also failed uid={}", uid, auditEx);
            }
            throw new AgeIneligibleAbortedException(uid, e);
        }
        log.warn("AGE_INELIGIBLE: soft-deleted uid={} (deleteReason=age-ineligible, refresh tokens revoked)", uid);
    }

    /**
     * Cubic R5 P1 #9 — idempotent-retry check. Two calls with the same
     * {@code displayName} (trim-and-normalised) and {@code dateOfBirth}
     * shape are treated as the same intent.
     */
    private static boolean profileMatches(User user, String displayName, LocalDate dateOfBirth) {
        if (user.getDisplayName() == null || !user.getDisplayName().equals(displayName.trim())) return false;
        Timestamp ts = user.getDateOfBirth();
        if (ts == null) return false;
        // Cubic R-final5 P2 — compare by date components, not raw epoch + nanos.
        //
        // Pre-fix the equality required {@code ts.getSeconds() == incomingEpoch
        // && ts.getNanos() == 0}. A legacy client that wrote dateOfBirth via
        // {@code Timestamp.now()} (or any path that produced a non-zero nanos
        // value) failed this check on retry — even though the stored DOB
        // represents the same calendar date the client is re-submitting.
        // The legitimate idempotent retry then bypassed the early-return and
        // tripped {@code ProfileAlreadyCompletedException} instead of returning
        // the existing user. Comparing by LocalDate (UTC) restores idempotency
        // regardless of the timestamp's sub-day precision.
        LocalDate storedDate = java.time.Instant
                .ofEpochSecond(ts.getSeconds(), ts.getNanos())
                .atZone(ZoneOffset.UTC)
                .toLocalDate();
        return storedDate.equals(dateOfBirth);
    }

    /**
     * Plan G B2 — partial profile update. Supports changing displayName and/or
     * dateOfBirth on an existing, completed profile.
     *
     * <p>Idempotency: if the resolved (trimmed) values are identical to what is
     * already persisted, the method returns the existing response without writing
     * or emitting an audit row.
     *
     * <p>Age gate: if a new dateOfBirth implies the user is under {@link #MIN_AGE},
     * delegates to {@link #rejectUnderAge(String)} (revoke + soft-delete) and
     * throws {@link AgeIneligibleException} — same path as {@code completeProfile}.
     *
     * @throws UserNotFoundException if uid has no Firestore document
     * @throws ProfileValidationException if displayName fails validation
     * @throws AgeIneligibleException if dateOfBirth implies under-13
     */
    public AccountMeResponse updateProfile(String uid, UpdateProfileRequest body)
            throws ExecutionException, InterruptedException, TimeoutException {
        User user = userRepository.findByUid(uid)
                .orElseThrow(() -> new UserNotFoundException(uid));

        if (isNoOpUpdate(user, body)) {
            return AccountMeResponse.from(user);
        }

        if (body.displayName() != null) {
            validateDisplayName(body.displayName());
        }
        if (body.dateOfBirth() != null) {
            // Plan G review-fix: reject future DOB before age-gate. A future
            // date produces a negative Period and would fall through to
            // rejectUnderAge → token revoke + soft-delete on a valid user
            // who fat-fingered the picker.
            validateDateOfBirth(body.dateOfBirth());
            enforceAgeOrReject(uid, body.dateOfBirth());
        }

        // Plan G review-fix (codex P1 lost-update): write through the
        // field-level merge so two concurrent edits on disjoint fields
        // don't clobber each other. {@code userRepository.save(user)} uses
        // {@code .set(user)} which is a whole-document overwrite — under
        // the read-modify-write window in this method, T1's name change and
        // T2's DOB change race to last-writer-wins.
        Map<String, Object> updates = new LinkedHashMap<>();
        User updated = user.copy();
        if (body.displayName() != null) {
            String trimmed = body.displayName().trim();
            updates.put("displayName", trimmed);
            updated.setDisplayName(trimmed);
        }
        if (body.dateOfBirth() != null) {
            Timestamp dobTs = Timestamp.ofTimeSecondsAndNanos(
                    body.dateOfBirth().atStartOfDay(ZoneOffset.UTC).toEpochSecond(), 0);
            updates.put("dateOfBirth", dobTs);
            updated.setDateOfBirth(dobTs);
        }
        userRepository.updateFields(uid, updates);
        // Plan G re-review-fix (reviewer Important #2): mirror the persisted
        // updatedAt touch on the local projection. Firestore's serverTimestamp
        // sentinel is expanded at the write site; this keeps the in-memory
        // response object aligned with what we just wrote (within JVM-clock
        // skew). Without this, future refactors that expose updatedAt on
        // AccountMeResponse would silently return stale values.
        updated.touch();

        auditLogService.logProfileEdit(uid, changedFields(user, updated));
        return AccountMeResponse.from(updated);
    }

    /**
     * Returns true iff both fields resolve to the same values already on the
     * user — in which case no write or audit is needed.
     *
     * <p>Trim is applied to displayName before comparing so whitespace-only
     * changes (e.g. trailing space stripped) still count as a real change.
     */
    private boolean isNoOpUpdate(User u, UpdateProfileRequest body) {
        boolean nameSame = body.displayName() == null
                || body.displayName().trim().equals(u.getDisplayName());
        boolean dobSame = body.dateOfBirth() == null
                || body.dateOfBirth().equals(timestampToLocalDate(u.getDateOfBirth()));
        return nameSame && dobSame;
    }

    private LocalDate timestampToLocalDate(Timestamp t) {
        if (t == null) return null;
        return java.time.Instant.ofEpochSecond(t.getSeconds(), t.getNanos())
                .atZone(ZoneOffset.UTC).toLocalDate();
    }

    /**
     * Returns a diff map of fields that changed between {@code before} and
     * {@code after}. Both fields are recorded as the sentinel {@code "changed"}
     * (no raw value) to avoid writing PII to the audit log; display names are
     * identifying data and the audit log is retained indefinitely, so storing
     * before/after pairs creates a permanent name-history record per user.
     * (Plan G review-fix: pre-fix the displayName diff stored {from,to}
     * plaintext; cso/codex flagged this and the nested-Map shape also
     * bypassed AuditLog.sanitiseDetailValue's control-char stripper.)
     */
    private Map<String, Object> changedFields(User before, User after) {
        Map<String, Object> diff = new LinkedHashMap<>();
        if (!Objects.equals(before.getDisplayName(), after.getDisplayName())) {
            diff.put("displayName", "changed");
        }
        if (!Objects.equals(before.getDateOfBirth(), after.getDateOfBirth())) {
            diff.put("dateOfBirth", "changed");
        }
        return diff;
    }

    /**
     * Validate a display name for both {@code completeProfile} and the forthcoming
     * {@code updateProfile}. Trims before checking so callers can work with raw input.
     *
     * <p>Throws {@link ProfileValidationException} (mapped to 400) on any violation.
     */
    void validateDisplayName(String name) {
        String trimmed = (name == null) ? "" : name.trim();
        if (trimmed.isEmpty() || trimmed.length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new ProfileValidationException("displayName",
                    "must be 1–" + MAX_DISPLAY_NAME_LENGTH + " characters");
        }
        if (CONTROL_CHARS.matcher(trimmed).find()) {
            throw new ProfileValidationException("displayName", "control characters not allowed");
        }
        // Plan G cubic R1 P2: reject zero-width / BOM / bidi-override
        // characters that bypass the C0/C1 control-char filter but render
        // identically to other users' display names or hijack RTL/LTR
        // rendering. `\p{Cf}` (Unicode format-control class) covers
        // U+200B–U+200D (ZWSP/ZWJ), U+FEFF (BOM), U+200E/F (LRM/RLM),
        // U+202A–U+202E and U+2066–U+2069 (bidi-override).
        if (FORMAT_CONTROL_CHARS.matcher(trimmed).find()) {
            throw new ProfileValidationException("displayName",
                    "zero-width or bidi-override characters not allowed");
        }
        if (URL_PATTERN.matcher(trimmed).find()) {
            throw new ProfileValidationException("displayName", "URLs not allowed in display name");
        }
    }

    private void validateDateOfBirth(LocalDate dob) {
        if (dob == null) {
            throw new IllegalArgumentException("dateOfBirth must not be null");
        }
        if (dob.isAfter(LocalDate.now(clock))) {
            throw new IllegalArgumentException("dateOfBirth must not be in the future");
        }
    }
}
