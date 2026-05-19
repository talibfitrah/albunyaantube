package com.albunyaan.tube.service;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.ErrorCode;
import com.google.firebase.FirebaseException;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
// Note: ProfileValidationException is in the same package — no explicit import needed.
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class AccountProfileServiceTest {

    @Mock private UserRepository userRepository;
    @Mock private FirebaseAuth firebaseAuth;
    @Mock private AuditLogService auditLogService;

    private AccountProfileService service;
    private final Clock fixedClock = Clock.fixed(
        LocalDate.of(2026, 5, 11).atStartOfDay(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC
    );

    @BeforeEach
    void setUp() {
        // Cubic R7 P1 — AccountProfileService now depends on AuditLogService
        // for the orphan-audit emission path. Mocked so the noop path returns
        // cleanly (the audit call itself is best-effort, wrapped in catch).
        service = new AccountProfileService(userRepository, firebaseAuth, fixedClock, auditLogService);
    }

    @Test
    void completeProfileAdultSuccess() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.completeProfile("uid-1", "Alice",
            LocalDate.of(2000, 1, 1)); // age 26

        assertEquals(UserStatus.ACTIVE, result.getStatusEnum());
        assertEquals("Alice", result.getDisplayName());
        assertNotNull(result.getDateOfBirth());
        assertNotNull(result.getProfileCompletedAt());
        verify(userRepository).save(any(User.class));
        verify(firebaseAuth, never()).deleteUser(any());
    }

    @Test
    void completeProfileUnder13Rejected() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        // Cubic R5 Tier C P1 #11 — rejectUnderAge now SOFT-deletes rather
        // than hard-deletes; the path is findByUid → recordSoftDelete → save.
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        AgeIneligibleException ex = assertThrows(AgeIneligibleException.class,
            () -> service.completeProfile("uid-1", "Tot", LocalDate.of(2020, 1, 1)));

        // Revoke must happen BEFORE the soft-delete write — see Tier C javadoc:
        // if we wrote first and revoke failed, a stale ID token could be used
        // to re-bootstrap from a different device before the tombstone landed.
        var order = inOrder(firebaseAuth, userRepository);
        order.verify(firebaseAuth).revokeRefreshTokens("uid-1");
        order.verify(userRepository).save(argThat((User u) -> u.isDeleted()
                && "age-ineligible".equals(u.getDeleteReason())));
        // hard-delete path is gone — the user remains as a soft-delete tombstone
        // so retry sign-in returns ACCOUNT_DELETED instead of lazy-creating a
        // fresh PENDING_PROFILE row that bypasses the age gate.
        verify(userRepository, never()).deleteByUid(any());
    }

    @Test
    void completeProfileBoundaryExactly13() throws Exception {
        // 2026-05-11 minus 13 years = 2013-05-11 → age 13, eligible
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any(User.class))).thenAnswer(inv -> inv.getArgument(0));

        User result = service.completeProfile("uid-1", "Teen", LocalDate.of(2013, 5, 11));
        assertEquals(UserStatus.ACTIVE, result.getStatusEnum());
    }

    @Test
    void completeProfileBoundaryDayUnder13() throws Exception {
        // 2013-05-12: birthday in 1 day → still 12, rejected
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThrows(AgeIneligibleException.class,
            () -> service.completeProfile("uid-1", "Almost",
                LocalDate.of(2013, 5, 12)));
    }

    @Test
    void completeProfileRejectsAlreadyCompleted() throws Exception {
        User existing = new User("uid-1", "a@b.com", "Alice", "user");
        existing.setStatusEnum(UserStatus.ACTIVE);
        existing.setProfileCompletedAt(Timestamp.now());
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThrows(ProfileAlreadyCompletedException.class,
            () -> service.completeProfile("uid-1", "Alice", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsMissingUser() throws Exception {
        when(userRepository.findByUid("ghost")).thenReturn(Optional.empty());
        assertThrows(UserNotFoundException.class,
            () -> service.completeProfile("ghost", "x", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsBlankDisplayName() {
        // Validation fires before any repository call — no stub needed.
        assertThrows(ProfileValidationException.class,
            () -> service.completeProfile("uid-1", "   ", LocalDate.of(2000, 1, 1)));
    }

    @Test
    void completeProfileRejectsTooLongDisplayName() {
        // Validation fires before any repository call — no stub needed.
        String over40 = "a".repeat(41);
        assertThrows(ProfileValidationException.class,
            () -> service.completeProfile("uid-1", over40, LocalDate.of(2000, 1, 1)));
    }

    @Test
    void validateDisplayNameRejectsControlChars() {
        assertThrows(ProfileValidationException.class,
            () -> service.validateDisplayName("BadName"));
    }

    @Test
    void validateDisplayNameRejectsUrls() {
        assertThrows(ProfileValidationException.class,
            () -> service.validateDisplayName("Visit https://spam.com"));
    }

    @Test
    void validateDisplayNameAcceptsValidName() {
        // Should not throw — 40 chars exactly, no control chars, no URLs.
        assertDoesNotThrow(() -> service.validateDisplayName("a".repeat(40)));
    }

    @Test
    void completeProfileFailsClosedIfRevokeFails() throws Exception {
        User existing = new User("uid-1", "a@b.com", null, "user");
        existing.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));
        doThrow(new FirebaseAuthException(
                new FirebaseException(ErrorCode.UNKNOWN, "revoke-failed", null)))
            .when(firebaseAuth).revokeRefreshTokens("uid-1");

        assertThrows(AgeIneligibleAbortedException.class,
            () -> service.completeProfile("uid-1", "Tot", LocalDate.of(2020, 1, 1)));

        // Did NOT delete the doc — abort preserves recoverability.
        verify(userRepository, never()).deleteByUid(any());
    }
}
