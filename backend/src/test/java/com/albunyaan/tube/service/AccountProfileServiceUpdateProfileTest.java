package com.albunyaan.tube.service;

import com.albunyaan.tube.dto.AccountMeResponse;
import com.albunyaan.tube.dto.UpdateProfileRequest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

/**
 * Plan G B2 — unit tests for {@link AccountProfileService#updateProfile}.
 *
 * <p>Uses the same fixture wiring as {@link AccountProfileServiceTest}:
 * fixed clock at 2026-05-19 UTC, mocked UserRepository/FirebaseAuth/AuditLogService.
 *
 * <p>NOTE: UserRepository.findByUid/save throw checked exceptions; Mockito
 * stubs them cleanly with the declared Exception throws clause below.
 */
@ExtendWith(MockitoExtension.class)
class AccountProfileServiceUpdateProfileTest {

    @Mock private UserRepository userRepository;
    @Mock private FirebaseAuth   firebaseAuth;
    @Mock private AuditLogService auditLogService;

    private AccountProfileService svc;

    private final Clock clock = Clock.fixed(
        LocalDate.of(2026, 5, 19).atStartOfDay(ZoneOffset.UTC).toInstant(),
        ZoneOffset.UTC
    );

    @BeforeEach
    void setUp() {
        svc = new AccountProfileService(userRepository, firebaseAuth, clock, auditLogService);
    }

    // ------------------------------------------------------------------
    // Helper: build a User that has a completed profile
    // ------------------------------------------------------------------
    private User baseUser(String uid, String displayName, Timestamp dateOfBirth) {
        User u = new User(uid, uid + "@example.com", displayName, "user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setProfileCompletedAt(Timestamp.now());
        if (dateOfBirth != null) u.setDateOfBirth(dateOfBirth);
        return u;
    }

    // ------------------------------------------------------------------
    // Happy path: update display name
    // ------------------------------------------------------------------
    @Test
    void updateDisplayName_persistsTrimmedNameAndAuditLogs() throws Exception {
        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AccountMeResponse resp = svc.updateProfile("u1",
            new UpdateProfileRequest("  New Name  ", null));

        ArgumentCaptor<User> saved = ArgumentCaptor.forClass(User.class);
        verify(userRepository).save(saved.capture());
        assertThat(saved.getValue().getDisplayName()).isEqualTo("New Name");
        assertThat(resp.getDisplayName()).isEqualTo("New Name");
        verify(auditLogService).logProfileEdit(eq("u1"), any());
    }

    // ------------------------------------------------------------------
    // Age gate on dateOfBirth update: under-13 → AgeIneligibleException
    // + tokens revoked
    // ------------------------------------------------------------------
    @Test
    void updateDateOfBirth_underAge_throwsAgeIneligible() throws Exception {
        // 12 years ago from the fixed clock date (2026-05-19) — LocalDate, not Timestamp
        LocalDate twelveYearsAgo = LocalDate.of(2026, 5, 19).minusYears(12);

        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));
        // rejectUnderAge calls findByUid again and then save for soft-delete
        when(userRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        assertThatThrownBy(() -> svc.updateProfile("u1",
            new UpdateProfileRequest(null, twelveYearsAgo)))
            .isInstanceOf(AgeIneligibleException.class);

        verify(firebaseAuth).revokeRefreshTokens("u1");
    }

    // ------------------------------------------------------------------
    // Idempotency: no-op body → no save, no audit
    // ------------------------------------------------------------------
    @Test
    void updateProfile_noopBody_returnsExistingWithoutSavingOrAudit() throws Exception {
        User existing = baseUser("u1", "Same Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        AccountMeResponse resp = svc.updateProfile("u1",
            new UpdateProfileRequest("Same Name", null));

        assertThat(resp.getDisplayName()).isEqualTo("Same Name");
        verify(userRepository, never()).save(any());
        verify(auditLogService, never()).logProfileEdit(any(), any());
    }

    // ------------------------------------------------------------------
    // Validation: URL in displayName → ProfileValidationException
    // ------------------------------------------------------------------
    @Test
    void updateDisplayName_withURL_throwsValidation() throws Exception {
        User existing = baseUser("u1", "Old", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> svc.updateProfile("u1",
            new UpdateProfileRequest("Click https://spam.example", null)))
            .isInstanceOf(ProfileValidationException.class);
    }

    // ------------------------------------------------------------------
    // Missing user → UserNotFoundException
    // ------------------------------------------------------------------
    @Test
    void updateProfile_userMissing_throwsUserNotFound() throws Exception {
        when(userRepository.findByUid("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.updateProfile("ghost",
            new UpdateProfileRequest("Anyone", null)))
            .isInstanceOf(UserNotFoundException.class);
    }
}
