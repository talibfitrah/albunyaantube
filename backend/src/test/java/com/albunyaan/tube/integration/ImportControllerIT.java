package com.albunyaan.tube.integration;

import com.albunyaan.tube.dto.YouTubeContentType;
import com.albunyaan.tube.dto.importflow.ImportItem;
import com.albunyaan.tube.dto.importflow.ImportResolveRequest;
import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.albunyaan.tube.repository.ChannelRepository;
import com.albunyaan.tube.repository.PlaylistRepository;
import com.albunyaan.tube.repository.VideoRepository;
import com.albunyaan.tube.service.UserImportSubmissionService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.cloud.Timestamp;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.http.MediaType;

import com.albunyaan.tube.dto.importflow.ImportDisposition;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.hamcrest.Matchers.anyOf;
import static org.hamcrest.Matchers.equalTo;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * BACKEND-IMPORT-05: Security integration test for POST /api/account/import/resolve.
 *
 * <p>Verifies the authorization rule with filters ON (full {@code @SpringBootTest} context,
 * inherited from {@link BaseIntegrationTest}). The {@code ImportControllerTest} uses
 * {@code @AutoConfigureMockMvc(addFilters=false)} and therefore cannot exercise the
 * security layer — this test fills that gap.
 *
 * <p>Effective rule (SecurityConfig, anyRequest fallback, line ~114):
 * {@code .anyRequest().authenticated()} — no role restriction. Any authenticated
 * user (USER, MODERATOR, ADMIN) must be allowed; anonymous requests must be rejected.
 *
 * <p>Two cases are covered:
 * <ol>
 *   <li>Anonymous (no Authorization header) → FirebaseAuthFilter passes the request
 *       through without setting an authentication; Spring Security's
 *       {@code anyRequest().authenticated()} rule rejects it with 401 or 403.</li>
 *   <li>Authenticated plain-USER principal → passes the security layer (no 401/403);
 *       the controller returns 200 (all-UNKNOWN items trigger mocked submit→PENDING).</li>
 * </ol>
 */
class ImportControllerIT extends BaseIntegrationTest {

    @Autowired
    private ObjectMapper json;

    @MockBean
    private FirebaseAuth firebaseAuth;

    /** Mock controller dependencies to avoid real Firestore calls in the controller layer. */
    @MockBean
    private ChannelRepository channelRepository;

    @MockBean
    private PlaylistRepository playlistRepository;

    @MockBean
    private VideoRepository videoRepository;

    @MockBean
    private UserImportSubmissionService submissions;

    // ─── Tests ────────────────────────────────────────────────────────────────

    /**
     * No Authorization header → the filter does not set an authentication object →
     * Spring Security rejects the request at the anyRequest().authenticated() rule.
     * The project's filter/entry-point combination returns 401 or 403 (see AccountControllerIT).
     */
    @Test
    void resolve_anonymous_isRejected() throws Exception {
        mvc.perform(post("/api/account/import/resolve")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalRequest()))
                .andExpect(status().is(anyOf(equalTo(401), equalTo(403))));
    }

    /**
     * Authenticated plain-USER → passes the security layer (not rejected with 401/403).
     *
     * <p>The controller is allowed to execute; with the channel unknown and submit()
     * returning PENDING, the response is 200. This proves no role gate blocks USER-role
     * callers on this path.
     */
    @Test
    void resolve_authenticatedUser_isAllowed() throws Exception {
        String uid = seedActiveUser("import-sec-user@test");
        stubAuthAs(uid, "user");

        // Controller will look up the channel (unknown → Optional.empty) then call submit().
        when(channelRepository.findByYoutubeId(anyString())).thenReturn(Optional.empty());
        when(submissions.submit(
                Mockito.any(ImportItem.class),
                Mockito.eq(uid)))
                .thenReturn(ImportDisposition.PENDING);

        mvc.perform(post("/api/account/import/resolve")
                        .header("Authorization", "Bearer fake-token")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(minimalRequest()))
                .andExpect(status().isOk());
    }

    // ─── Helpers ──────────────────────────────────────────────────────────────

    /**
     * Minimal valid request body — one CHANNEL item so @NotEmpty passes.
     */
    private String minimalRequest() throws Exception {
        ImportResolveRequest req = new ImportResolveRequest(List.of(
                new ImportItem(YouTubeContentType.CHANNEL, "ch-sec-test", "Security Test Channel", null, null)
        ));
        return json.writeValueAsString(req);
    }

    /**
     * Seed an ACTIVE user directly into the Firestore emulator.
     * The FirebaseAuthFilter calls userRepository.findByUidUncached() against Firestore;
     * seeding the doc ensures the filter's status check succeeds and the request reaches
     * the security-layer decision.
     */
    private String seedActiveUser(String email) throws Exception {
        String uid = "uid-" + email.replace("@", "-").replace(".", "-");
        User u = new User(uid, email, "Security Test User", "user");
        u.setStatusEnum(UserStatus.ACTIVE);
        u.setCreatedAt(Timestamp.now());
        u.setUpdatedAt(u.getCreatedAt());
        userRepository.save(u);
        return uid;
    }

    /**
     * Stub FirebaseAuth.verifyIdToken (both overloads) to return a fake token carrying
     * the given uid and role claim. Mirrors the pattern used across the IT suite
     * (AccountControllerIT, UpdateProfileIT, BulkSubmissionIT).
     */
    private void stubAuthAs(String uid, String role) throws Exception {
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
