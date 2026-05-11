package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

/**
 * BACKEND-AUTH-01 review-pipeline finding F9.
 *
 * Pre-fix: UserRepository.countModerators used whereEqualTo("role", "ADMIN")
 * and "MODERATOR" — uppercase literals that match zero documents post-D6
 * (all roles stored canonical lowercase). The admin dashboard showed
 * "0 moderators" regardless of reality.
 *
 * Also pins F9 path-param normalization on UserController.getUsersByRole:
 * a request to /role/ADMIN must return the same result as /role/admin
 * (D6 canonical-lowercase role storage).
 */
class UserRepositoryCountIntegrationTest extends BaseIntegrationTest {

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void countModerators_returnsCorrectCountWithLowercaseRoles() throws Exception {
        // Seed 1 admin + 2 moderators + 1 user = 3 should be counted as
        // admin+moderator. Pre-F9 returned 0 because countModerators looked
        // for uppercase "ADMIN" / "MODERATOR" literals.
        seedUser("admin-c1", "admin");
        seedUser("mod-c1", "moderator");
        seedUser("mod-c2", "moderator");
        seedUser("user-c1", "user"); // must NOT count

        long count = userRepository.countModerators();

        assertEquals(3L, count,
            "countModerators should sum admin + moderator counts with lowercase role values");
    }

    @Test
    void countModerators_returnsZeroWhenNone() throws Exception {
        seedUser("user-c2", "user");

        assertEquals(0L, userRepository.countModerators());
    }

    @Test
    void findByRole_returnsLowercaseMatches() throws Exception {
        // Verify the underlying findByRole works with lowercase, so the F9
        // controller normalization (path param "ADMIN" → "admin") returns hits.
        seedUser("admin-find-1", "admin");
        seedUser("admin-find-2", "admin");
        seedUser("mod-find-1", "moderator");

        List<User> admins = userRepository.findByRole("admin");
        assertEquals(2, admins.size(),
            "findByRole(\"admin\") must return all admin users");
    }

    @Test
    void getUsersByRole_acceptsBothCases_returnsLowercaseMatches() throws Exception {
        // E2E through the controller: /role/ADMIN and /role/admin both
        // resolve to the lowercase "admin" query.
        String adminUid = "admin-route-1";
        seedUser(adminUid, "admin");
        seedUser("admin-route-2", "admin");
        seedUser("mod-route-1", "moderator");

        stubToken(adminUid, "admin");

        mvc.perform(get("/api/admin/users/role/admin")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        mvc.perform(get("/api/admin/users/role/ADMIN")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(2));

        // Moderator route too — mixed-case
        mvc.perform(get("/api/admin/users/role/Moderator")
                .header("Authorization", "Bearer fake-token"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.length()").value(1));
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    private void seedUser(String uid, String role) throws Exception {
        User u = new User();
        u.setUid(uid);
        u.setEmail(uid + "@t.com");
        u.setRole(role);
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(Timestamp.now());
        userRepository.save(u);
    }

    private void stubToken(String uid, String role) throws Exception {
        FirebaseToken token = Mockito.mock(FirebaseToken.class);
        Mockito.when(token.getUid()).thenReturn(uid);
        Mockito.when(token.getEmail()).thenReturn(uid + "@test");
        Map<String, Object> claims = new HashMap<>();
        claims.put("role", role);
        Mockito.when(token.getClaims()).thenReturn(claims);
        Mockito.when(firebaseAuth.verifyIdToken(anyString())).thenReturn(token);
        Mockito.when(firebaseAuth.verifyIdToken(anyString(), anyBoolean())).thenReturn(token);
    }
}
