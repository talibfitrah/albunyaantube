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
            new UpdateProfileRequest("  New Name  ", null, null));

        // Field-level merge — only displayName, not a whole-doc overwrite.
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

    // updateFields allowlist — sensitive field keys (role, status,
    // deletedAt) must throw before any Firestore call.
    @Test
    void userRepository_updateFields_rejectsDisallowedKey() throws Exception {
        com.albunyaan.tube.config.FirestoreTimeoutProperties timeouts =
            new com.albunyaan.tube.config.FirestoreTimeoutProperties();
        com.albunyaan.tube.repository.UserRepository repo =
            new com.albunyaan.tube.repository.UserRepository(
                org.mockito.Mockito.mock(com.google.cloud.firestore.Firestore.class),
                timeouts);

        assertThatThrownBy(() ->
            repo.updateFields("u1", java.util.Map.of("role", "ADMIN")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("role");

        assertThatThrownBy(() ->
            repo.updateFields("u1", java.util.Map.of("status", "ACTIVE")))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessageContaining("status");
    }

    // ------------------------------------------------------------------
    // Concurrent disjoint edits should both persist via field-level merge.
    // ------------------------------------------------------------------
    @Test
    void updateProfile_writesOnlyChangedFields_notWholeDocument() throws Exception {
        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        // DOB-only update: must NOT include displayName in the merge,
        // otherwise a concurrent displayName edit on another device gets
        // overwritten by this thread's stale read.
        LocalDate twentyYearsAgo = LocalDate.of(2026, 5, 19).minusYears(20);
        svc.updateProfile("u1", new UpdateProfileRequest(null, twentyYearsAgo, null));

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
            new UpdateProfileRequest(null, twelveYearsAgo, null)))
            .isInstanceOf(AgeIneligibleException.class);

        verify(firebaseAuth).revokeRefreshTokens("u1");
        // Firebase Auth account must be disabled (not just revoked) so the
        // user can't re-authenticate and self-recover. Assert
        // disabled == true specifically — a future refactor passing
        // setDisabled(false) would otherwise slip through. UpdateRequest stores
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
    // Future DOB must be rejected before the age-gate — a negative
    // Period would otherwise trigger revoke + soft-delete.
    // ------------------------------------------------------------------
    @Test
    void updateDateOfBirth_future_throwsValidationNotAgeReject() throws Exception {
        LocalDate tomorrow = LocalDate.of(2026, 5, 19).plusDays(1);

        User existing = baseUser("u1", "Old Name", null);
        when(userRepository.findByUid("u1")).thenReturn(Optional.of(existing));

        // validateDateOfBirth throws ProfileValidationException (typed
        // with field metadata for client routing); reason contains "future".
        assertThatThrownBy(() -> svc.updateProfile("u1",
            new UpdateProfileRequest(null, tomorrow, null)))
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
            new UpdateProfileRequest("Same Name", null, null));

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
            new UpdateProfileRequest("Click https://spam.example", null, null)))
            .isInstanceOf(ProfileValidationException.class);
    }

    // ------------------------------------------------------------------
    // Missing user → UserNotFoundException
    // ------------------------------------------------------------------
    @Test
    void updateProfile_userMissing_throwsUserNotFound() throws Exception {
        when(userRepository.findByUid("ghost")).thenReturn(Optional.empty());

        assertThatThrownBy(() -> svc.updateProfile("ghost",
            new UpdateProfileRequest("Anyone", null, null)))
            .isInstanceOf(UserNotFoundException.class);
    }

    // ------------------------------------------------------------------
    // Phone: happy path — new phone persists and audit diff emitted
    // ------------------------------------------------------------------
    @Test
    void updateProfilePhoneOnlyPersistsAndAudits() throws Exception {
        User existing = baseUser("uid-1", "Alice", null);
        existing.setPhoneNumber("+31612345678");
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        AccountMeResponse resp = svc.updateProfile("uid-1",
            new UpdateProfileRequest(null, null, "+447412345678"));

        assertThat(resp.getPhoneNumber()).isEqualTo("+447412345678");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> updatesCaptor =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(userRepository).updateFields(eq("uid-1"), updatesCaptor.capture());
        assertThat(updatesCaptor.getValue()).containsEntry("phoneNumber", "+447412345678");
        @SuppressWarnings("unchecked")
        ArgumentCaptor<java.util.Map<String, Object>> diffCaptor =
            ArgumentCaptor.forClass(java.util.Map.class);
        verify(auditLogService).logProfileEdit(eq("uid-1"), diffCaptor.capture());
        assertThat(diffCaptor.getValue()).containsEntry("phoneNumber", "changed");
    }

    // ------------------------------------------------------------------
    // Phone: same value → no-op (no save, no audit)
    // ------------------------------------------------------------------
    @Test
    void updateProfilePhoneSameAsExistingIsNoOp() throws Exception {
        User existing = baseUser("uid-1", "Alice", null);
        existing.setPhoneNumber("+31612345678");
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        svc.updateProfile("uid-1", new UpdateProfileRequest(null, null, "+31612345678"));

        verify(userRepository, never()).updateFields(any(), any());
        verify(auditLogService, never()).logProfileEdit(any(), any());
    }

    // ------------------------------------------------------------------
    // Phone: malformed value → ProfileValidationException, no persist
    // ------------------------------------------------------------------
    @Test
    void updateProfileRejectsMalformedPhone() throws Exception {
        User existing = baseUser("uid-1", "Alice", null);
        existing.setPhoneNumber("+31612345678");
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(existing));

        assertThatThrownBy(() -> svc.updateProfile("uid-1",
            new UpdateProfileRequest(null, null, "not-a-phone")))
            .isInstanceOf(ProfileValidationException.class);
        verify(userRepository, never()).updateFields(any(), any());
    }

    // ------------------------------------------------------------------
    // Status gate: PENDING_PROFILE user cannot use the partial-update path
    // ------------------------------------------------------------------
    @Test
    void updateProfile_pendingProfileUser_throwsProfileValidationException() throws Exception {
        User pending = new User("uid-1", "uid-1@example.com", "Alice", "user");
        pending.setStatusEnum(UserStatus.PENDING_PROFILE);
        when(userRepository.findByUid("uid-1")).thenReturn(Optional.of(pending));

        assertThatThrownBy(() -> svc.updateProfile("uid-1",
            new UpdateProfileRequest(null, null, "+31612345678")))
            .isInstanceOf(ProfileValidationException.class)
            .hasMessageContaining("status");
        verify(userRepository, never()).updateFields(any(), any());
    }
}
