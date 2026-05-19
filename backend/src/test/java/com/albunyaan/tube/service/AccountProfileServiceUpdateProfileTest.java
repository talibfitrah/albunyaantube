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

        AccountMeResponse resp = svc.updateProfile("u1",
            new UpdateProfileRequest("  New Name  ", null));

        // Plan G review-fix: field-level merge — verify updateFields was
        // called with only displayName (not the whole document overwrite).
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> fields =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(userRepository).updateFields(eq("u1"), fields.capture());
        verify(userRepository, never()).save(any());
        assertThat(fields.getValue()).containsEntry("displayName", "New Name");
        assertThat(fields.getValue()).doesNotContainKey("dateOfBirth");
        assertThat(resp.getDisplayName()).isEqualTo("New Name");
        verify(auditLogService).logProfileEdit(eq("u1"), any());
    }

    // ------------------------------------------------------------------
    // Plan G round-3 review-fix (reviewer Important #2): allowlist enforcement.
    // Verify a future caller cannot mass-assign role/status/deletedAt
    // through {@code UserRepository.updateFields}.
    // ------------------------------------------------------------------
    @Test
    void userRepository_updateFields_rejectsDisallowedKey() throws Exception {
        com.albunyaan.tube.config.FirestoreTimeoutProperties timeouts =
            new com.albunyaan.tube.config.FirestoreTimeoutProperties();
        com.albunyaan.tube.repository.UserRepository repo =
            new com.albunyaan.tube.repository.UserRepository(
                org.mockito.Mockito.mock(com.google.cloud.firestore.Firestore.class),
                timeouts);

        // role is sensitive (admin escalation surface) — must throw before
        // any Firestore call.
        assertThatThrownBy(() ->
            repo.updateFields("u1", java.util.Map.of("role", "ADMIN")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");

        // status is also sensitive (could revive a deleted user).
        assertThatThrownBy(() ->
            repo.updateFields("u1", java.util.Map.of("status", "ACTIVE")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status");

        // The allowlist itself should be immutable (Set.of returns
        // ImmutableCollections.Set12; verify defensively).
        assertThatThrownBy(() ->
            com.albunyaan.tube.repository.UserRepository.ALLOWED_UPDATE_FIELDS.add("hostile"))
            .isInstanceOf(UnsupportedOperationException.class);
    }

    // ------------------------------------------------------------------
    // Plan G review-fix (codex P1 lost-update): concurrent disjoint edits
    // ------------------------------------------------------------------
    @Test
    void updateProfile_writesOnlyChangedFields_notWholeDocument() throws Exception {
        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        // DOB-only update: must NOT include displayName in the merge,
        // otherwise a concurrent displayName edit on another device gets
        // overwritten by this thread's stale read.
        LocalDate twentyYearsAgo = LocalDate.of(2026, 5, 19).minusYears(20);
        svc.updateProfile("u1", new UpdateProfileRequest(null, twentyYearsAgo));

        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> fields =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(userRepository).updateFields(eq("u1"), fields.capture());
        verify(userRepository, never()).save(any());
        assertThat(fields.getValue()).containsKey("dateOfBirth");
        assertThat(fields.getValue()).doesNotContainKey("displayName");
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
        // Plan G review-fix (codex P1): Firebase Auth account is also
        // disabled so the user cannot re-authenticate, regain a fresh
        // token, and self-recover by submitting an adult DOB.
        // Plan G re-review-fix (reviewer Minor #4): assert disabled == true
        // specifically — without this, a future refactor that passes
        // setDisabled(false) would silently pass. UpdateRequest stores
        // mutations in a private "properties" map; reflect on that map to
        // assert the value because the SDK exposes no public accessor.
        ArgumentCaptor<com.google.firebase.auth.UserRecord.UpdateRequest> updateReq =
            ArgumentCaptor.forClass(com.google.firebase.auth.UserRecord.UpdateRequest.class);
        verify(firebaseAuth).updateUser(updateReq.capture());
        java.lang.reflect.Field propsField =
            com.google.firebase.auth.UserRecord.UpdateRequest.class.getDeclaredField("properties");
        propsField.setAccessible(true);
        @SuppressWarnings("unchecked")
        java.util.Map<String, Object> props =
            (java.util.Map<String, Object>) propsField.get(updateReq.getValue());
        // Firebase Admin SDK serializes setDisabled(true) as
        // properties["disableUser"]=true on the wire (not "disabled").
        assertThat(props).containsEntry("disableUser", Boolean.TRUE);
    }

    // ------------------------------------------------------------------
    // Plan G review-fix (codex P1 future-DOB): a future date previously
    // produced a negative Period and went through revoke + soft-delete on
    // a legitimate account. Now rejected with IllegalArgumentException.
    // ------------------------------------------------------------------
    @Test
    void updateDateOfBirth_future_throwsValidationNotAgeReject() throws Exception {
        LocalDate tomorrow = LocalDate.of(2026, 5, 19).plusDays(1);

        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        // Plan G cubic R2 P1: validateDateOfBirth now throws
        // ProfileValidationException (not IllegalArgumentException) so the
        // 400 envelope carries field metadata the Android client can
        // route on. Reason text still contains "future".
        assertThatThrownBy(() -> svc.updateProfile("u1",
            new UpdateProfileRequest(null, tomorrow)))
            .isInstanceOf(ProfileValidationException.class)
            .hasMessageContaining("future");

        // Critical: revoke/disable/soft-delete MUST NOT fire.
        verify(firebaseAuth, never()).revokeRefreshTokens(any());
        verify(firebaseAuth, never()).updateUser(any(com.google.firebase.auth.UserRecord.UpdateRequest.class));
        verify(userRepository, never()).updateFields(any(), any());
        verify(userRepository, never()).save(any());
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
        verify(userRepository, never()).updateFields(any(), any());
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
