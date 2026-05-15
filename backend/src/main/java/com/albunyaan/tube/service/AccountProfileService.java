package com.albunyaan.tube.service;

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
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeoutException;

@Service
public class AccountProfileService {

    private static final Logger log = LoggerFactory.getLogger(AccountProfileService.class);
    private static final int MIN_AGE = 13;
    private static final int MAX_DISPLAY_NAME_LENGTH = 40;

    private final UserRepository userRepository;
    private final FirebaseAuth firebaseAuth;
    private final Clock clock;

    public AccountProfileService(UserRepository userRepository,
                                  FirebaseAuth firebaseAuth,
                                  Clock clock) {
        this.userRepository = userRepository;
        this.firebaseAuth = firebaseAuth;
        this.clock = clock;
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

        int age = Period.between(dateOfBirth, LocalDate.now(clock)).getYears();
        if (age < MIN_AGE) {
            rejectUnderAge(uid);
            throw new AgeIneligibleException(uid, age);
        }

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
        long incomingEpoch = dateOfBirth.atStartOfDay(ZoneOffset.UTC).toEpochSecond();
        return ts.getSeconds() == incomingEpoch && ts.getNanos() == 0;
    }

    private void validateDisplayName(String name) {
        if (name == null || name.trim().isEmpty()) {
            throw new IllegalArgumentException("displayName must not be blank");
        }
        if (name.trim().length() > MAX_DISPLAY_NAME_LENGTH) {
            throw new IllegalArgumentException(
                    "displayName must be at most " + MAX_DISPLAY_NAME_LENGTH + " characters");
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
