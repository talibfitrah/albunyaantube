package com.albunyaan.tube.integration;

import com.albunyaan.tube.exception.LastAdminException;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.when;

/**
 * BACKEND-AUTH-01: Integration tests for AuthService.updateUserRoleAsActor last-admin guard.
 *
 * Uses real Firestore emulator for transactional correctness.
 * Mocks FirebaseAuth (setCustomUserClaims) — no Auth emulator required.
 */
class AuthServiceLastAdminIntegrationTest extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void demoteLastAdmin_throws() throws Exception {
        String soloAdmin = seedUser("solo@t", "admin");
        String otherAdmin = seedUser("a2@t", "admin"); // 2 admins total

        // Stub Firebase Auth mutations needed by blockUser (called in setup)
        when(firebaseAuth.updateUser(any())).thenReturn(null);
        doNothing().when(firebaseAuth).revokeRefreshTokens(anyString());

        // Block otherAdmin first so soloAdmin is the only ACTIVE admin
        authService.blockUser(otherAdmin, soloAdmin, "test-setup");

        // Now soloAdmin is the only active admin — demoting must throw
        assertThrows(LastAdminException.class,
                () -> authService.updateUserRoleAsActor(soloAdmin, "moderator", soloAdmin));
    }

    @Test
    @SuppressWarnings("unchecked")
    void demoteNonLastAdmin_succeeds() throws Exception {
        String adminA = seedUser("aA@t", "admin");
        String adminB = seedUser("aB@t", "admin");

        // F7: AuthService.setUserRoleClaim reads existing claims before merging.
        com.google.firebase.auth.UserRecord rec = org.mockito.Mockito.mock(com.google.firebase.auth.UserRecord.class);
        org.mockito.Mockito.when(rec.getCustomClaims()).thenReturn(null);
        org.mockito.Mockito.when(firebaseAuth.getUser(adminA)).thenReturn(rec);

        // Stub setCustomUserClaims for the successful demotion
        doNothing().when(firebaseAuth).setCustomUserClaims(anyString(), anyMap());

        authService.updateUserRoleAsActor(adminA, "moderator", adminB);

        assertEquals("moderator", userRepository.findByUid(adminA).orElseThrow().getRole());
    }

    @Test
    void selfDemoteAdmin_throws_evenWithMultipleAdmins() throws Exception {
        String adminA = seedUser("a1@t", "admin");
        seedUser("a2@t", "admin");
        seedUser("a3@t", "admin");
        seedUser("a4@t", "admin");
        seedUser("a5@t", "admin");  // 5 admins total

        // Self-demotion is blocked regardless of how many other admins exist
        assertThrows(LastAdminException.class,
                () -> authService.updateUserRoleAsActor(adminA, "moderator", adminA));
    }

    /**
     * Seed an ACTIVE user directly into the Firestore emulator and return their UID.
     */
    private String seedUser(String email, String role) throws Exception {
        String uid = "test-" + email.replace("@", "-at-").replace(".", "-");
        User u = new User();
        u.setUid(uid);
        u.setEmail(email);
        u.setRole(role);
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
        return uid;
    }
}
