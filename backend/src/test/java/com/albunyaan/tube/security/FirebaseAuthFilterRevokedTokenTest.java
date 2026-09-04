package com.albunyaan.tube.security;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.model.UserStatus;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.ErrorCode;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.core.context.SecurityContextHolder;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * Task 19 Stage 5 §3 M1/M2 — the account-lifecycle 403 envelope was unreachable
 * for exactly the users it exists for.
 *
 * <p>{@code AuthService.softDeleteUser} / {@code blockUser} call
 * {@code setDisabled(true)} + {@code revokeRefreshTokens(uid)} before anything
 * else, and the filter enables {@code checkRevoked} on {@code /api/admin/} and
 * {@code /api/account/}. So {@code verifyIdToken(token, true)} throws for a
 * blocked or soft-deleted user and the {@code FirebaseAuthException} arm
 * returned a bare 401 — the {@code isDeleted()} / {@code isBlocked()} 403 arms
 * never ran, and the mobile clients never learned the verdict.
 *
 * <p>M2: no 401 in the whole backend set {@code WWW-Authenticate}, which both
 * mobile clients gate their one-time token-refresh retry on (RFC 6750 §3).
 *
 * <p>Pure unit test: {@link FirebaseAuth} and the repository are Mockito mocks,
 * so no Firebase and no emulator. Not tagged {@code integration} — it runs
 * under a plain {@code ./gradlew test}.
 */
class FirebaseAuthFilterRevokedTokenTest {

    private static final String UID = "uid-under-review";
    private static final String TOKEN = "revoked-id-token";

    private FirebaseAuth firebaseAuth;
    private com.albunyaan.tube.repository.UserRepository userRepository;
    private FirebaseAuthFilter filter;

    @BeforeEach
    void setUp() {
        firebaseAuth = mock(FirebaseAuth.class);
        userRepository = mock(com.albunyaan.tube.repository.UserRepository.class);
        filter = new FirebaseAuthFilter(firebaseAuth, userRepository, new ObjectMapper());
        SecurityContextHolder.clearContext();
    }

    @AfterEach
    void tearDown() {
        SecurityContextHolder.clearContext();
    }

    // ── helpers ─────────────────────────────────────────────────────────────

    private static FirebaseAuthException authException(AuthErrorCode code) {
        return new FirebaseAuthException(
                ErrorCode.UNAUTHENTICATED, "token rejected: " + code, null, null, code);
    }

    /** The checked call throws (disabled/revoked); the unchecked one still decodes. */
    private void stubRevokedButDecodable(AuthErrorCode code) throws Exception {
        FirebaseToken decoded = mock(FirebaseToken.class);
        when(decoded.getUid()).thenReturn(UID);
        when(firebaseAuth.verifyIdToken(anyString(), eq(true))).thenThrow(authException(code));
        when(firebaseAuth.verifyIdToken(anyString(), eq(false))).thenReturn(decoded);
    }

    private void stubUser(UserStatus status) throws Exception {
        User user = new User(UID, "reviewed@test.com", "Reviewed", "user");
        user.setStatusEnum(status);
        when(userRepository.findByUidUncached(UID)).thenReturn(Optional.of(user));
    }

    private MockHttpServletResponse callAccountEndpoint() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/account/me");
        request.setRequestURI("/api/account/me");
        request.addHeader("Authorization", "Bearer " + TOKEN);
        MockHttpServletResponse response = new MockHttpServletResponse();
        filter.doFilter(request, response, new MockFilterChain());
        return response;
    }

    // ── M1: the lifecycle verdict must survive the revocation failure ───────

    @Test
    @DisplayName("M1: revoked token + soft-deleted user -> 403 ACCOUNT_DELETED, not a bare 401")
    void revokedTokenForDeletedUser_getsAccountDeleted403() throws Exception {
        stubRevokedButDecodable(AuthErrorCode.USER_DISABLED);
        stubUser(UserStatus.DELETED);

        MockHttpServletResponse response = callAccountEndpoint();

        assertEquals(403, response.getStatus());
        assertEquals("{\"code\":\"ACCOUNT_DELETED\",\"message\":\"Your account has been deleted.\"}",
                normalize(response.getContentAsString()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("M1: revoked token + blocked user -> 403 ACCOUNT_BLOCKED, not a bare 401")
    void revokedTokenForBlockedUser_getsAccountBlocked403() throws Exception {
        stubRevokedButDecodable(AuthErrorCode.REVOKED_ID_TOKEN);
        stubUser(UserStatus.BLOCKED);

        MockHttpServletResponse response = callAccountEndpoint();

        assertEquals(403, response.getStatus());
        assertEquals("{\"code\":\"ACCOUNT_BLOCKED\",\"message\":\"Your account is blocked.\"}",
                normalize(response.getContentAsString()));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    @Test
    @DisplayName("M1+M2: revoked token + healthy user -> 401 with the Bearer challenge")
    void revokedTokenForHealthyUser_falls_throughTo401WithChallenge() throws Exception {
        stubRevokedButDecodable(AuthErrorCode.REVOKED_ID_TOKEN);
        stubUser(UserStatus.ACTIVE);

        MockHttpServletResponse response = callAccountEndpoint();

        assertEquals(401, response.getStatus());
        assertNotNull(response.getHeader("WWW-Authenticate"), "no WWW-Authenticate challenge on the 401");
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertNull(SecurityContextHolder.getContext().getAuthentication());
    }

    // ── M2: every 401 the filter writes carries the challenge ───────────────

    @Test
    @DisplayName("M2: an ordinary invalid token -> 401 with WWW-Authenticate: Bearer")
    void invalidToken_gets401WithChallenge() throws Exception {
        when(firebaseAuth.verifyIdToken(anyString(), eq(true)))
                .thenThrow(authException(AuthErrorCode.INVALID_ID_TOKEN));

        MockHttpServletResponse response = callAccountEndpoint();

        assertEquals(401, response.getStatus());
        assertEquals("Bearer", response.getHeader("WWW-Authenticate"));
        assertEquals("{\"error\": \"Invalid or expired token\"}", response.getContentAsString());
    }

    /** Jackson map ordering is not guaranteed; compare on a canonical field order. */
    private String normalize(String json) throws Exception {
        var node = new ObjectMapper().readTree(json);
        return "{\"code\":\"" + node.get("code").asText()
                + "\",\"message\":\"" + node.get("message").asText() + "\"}";
    }
}
