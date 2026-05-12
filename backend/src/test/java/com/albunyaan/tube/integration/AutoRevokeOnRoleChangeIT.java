package com.albunyaan.tube.integration;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.UserRepository;
import com.albunyaan.tube.service.AuthService;
import com.google.cloud.Timestamp;
import com.google.cloud.firestore.QueryDocumentSnapshot;
import com.google.cloud.firestore.QuerySnapshot;
import com.google.firebase.auth.FirebaseAuth;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.verify;

class AutoRevokeOnRoleChangeIT extends BaseIntegrationTest {

    @Autowired
    AuthService authService;

    @MockBean
    FirebaseAuth firebaseAuth;

    @Test
    void updateRole_firesAutoRevoke_andAudits() throws Exception {
        String adminUid = seedUser("admin-aroc@test.com", "admin");
        seedUser("admin-aroc-2@test.com", "admin"); // not last admin
        String targetUid = seedUser("target-aroc@test.com", "user");

        authService.updateUserRoleAsActor(targetUid, "moderator", adminUid);

        verify(firebaseAuth).revokeRefreshTokens(targetUid);

        QuerySnapshot snap = firestore.collection("audit_logs")
                .whereEqualTo("action", "USER_SESSIONS_REVOKED_AUTO")
                .whereEqualTo("entityId", targetUid)
                .get().get();

        List<QueryDocumentSnapshot> docs = snap.getDocuments();
        assertEquals(1, docs.size(),
                "expected exactly one USER_SESSIONS_REVOKED_AUTO entry for " + targetUid);

        @SuppressWarnings("unchecked")
        Map<String, Object> details = (Map<String, Object>) docs.get(0).get("details");
        assertNotNull(details, "audit details should be present");
        assertEquals("user", details.get("oldRole"));
        assertEquals("moderator", details.get("newRole"));
        assertEquals("role_change", details.get("trigger"));
    }

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
