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
     * Revoke FIRST, then delete (see plan T2 self-critique #3). If revoke fails
     * we abort without deleting — the user retains a valid token but their doc
     * is intact, so a retry can succeed. If we deleted first and revoke failed,
     * a stale ID token could be used to re-bootstrap from a different device.
     */
    private void rejectUnderAge(String uid) {
        try {
            firebaseAuth.revokeRefreshTokens(uid);
        } catch (FirebaseAuthException e) {
            log.error("AGE_INELIGIBLE: revokeRefreshTokens failed for uid={}, aborting", uid, e);
            throw new AgeIneligibleAbortedException(uid, e);
        }
        try {
            userRepository.deleteByUid(uid);
        } catch (ExecutionException | InterruptedException | TimeoutException e) {
            // If revoke succeeded but delete failed, the user retains no refresh token
            // (so cannot mint new ID tokens after current one expires ~1h) but their
            // doc is still present. Backend cleanup job or admin can purge the doc.
            // We still throw aborted to surface the failure.
            if (e instanceof InterruptedException) Thread.currentThread().interrupt();
            log.error("AGE_INELIGIBLE: deleteByUid failed for uid={} (refresh tokens already revoked)", uid, e);
            throw new AgeIneligibleAbortedException(uid, e);
        }
        log.warn("AGE_INELIGIBLE: hard-rejected uid={} (doc deleted, refresh tokens revoked)", uid);
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
