package com.albunyaan.tube.security;

import com.albunyaan.tube.model.User;
import com.albunyaan.tube.repository.UserRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.firebase.auth.AuthErrorCode;
import com.google.firebase.auth.FirebaseAuth;
import com.google.firebase.auth.FirebaseAuthException;
import com.google.firebase.auth.FirebaseToken;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * FIREBASE-MIGRATE-02: Firebase Authentication Filter
 *
 * Intercepts requests and validates Firebase ID tokens from the Authorization header.
 * Extracts user information and custom claims (role) from the token and sets
 * Spring Security authentication context.
 *
 * Expected header format: Authorization: Bearer <firebase-id-token>
 */
@Component
public class FirebaseAuthFilter extends OncePerRequestFilter {

    private static final Logger logger = LoggerFactory.getLogger(FirebaseAuthFilter.class);
    private static final String AUTHORIZATION_HEADER = "Authorization";
    private static final String BEARER_PREFIX = "Bearer ";
    private static final String ROLE_CLAIM = "role";
    /** RFC 6750 §3 — the challenge both mobile clients gate their token-refresh retry on. */
    private static final String WWW_AUTHENTICATE_HEADER = "WWW-Authenticate";
    private static final String BEARER_CHALLENGE = "Bearer";
    /**
     * Only these role values are accepted from Firebase custom claims. Any other
     * value falls back to "user".
     *
     * <p>Cubic R7 P2 — drop "user" from the allow-list. The token side never
     * emits {@code role: "user"} (default-role users have no role claim at
     * all); accepting it on the read side widened the surface for free —
     * a stale or hand-crafted token with {@code role: "user"} would still
     * be honoured. Restricting to the two elevated roles is conservative;
     * the fallback path below already covers default users by returning
     * "user" anyway.
     */
    private static final Set<String> VALID_ROLES = Set.of("admin", "moderator");

    private final FirebaseAuth firebaseAuth;
    private final UserRepository userRepository;
    private final ObjectMapper objectMapper;

    public FirebaseAuthFilter(FirebaseAuth firebaseAuth, UserRepository userRepository, ObjectMapper objectMapper) {
        this.firebaseAuth = firebaseAuth;
        this.userRepository = userRepository;
        this.objectMapper = objectMapper;
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request,
            HttpServletResponse response,
            FilterChain filterChain
    ) throws ServletException, IOException {
        String requestURI = request.getRequestURI();
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        logger.debug("→ Request: {} {}", request.getMethod(), requestURI);
        logger.debug("  Auth header present: {}", authHeader != null);

        if (authHeader != null && authHeader.startsWith(BEARER_PREFIX)) {
            String token = authHeader.substring(BEARER_PREFIX.length());
            logger.debug("  Token length: {}", token.length());

            try {
                // Single verifyIdToken call gated on the revocation-check scope:
                // previously the filter ran verifyIdToken(token) unconditionally and
                // then verifyIdToken(token, true) again on admin paths, paying two
                // network/CPU calls to Firebase per admin request. The decodedToken
                // from the first (cheaper) call was discarded.
                //
                // Cubic R-final5 P0 — /api/account/* must also enable checkRevoked.
                // AccountProfileService.rejectUnderAge revokes refresh tokens and
                // soft-deletes the user, but the under-13 user's *current* ID token
                // stays valid until natural expiry (~1h). Without checkRevoked on
                // /api/account/*, that token passes verification, the filter's
                // "no Firestore doc yet — allow" lazy-create branch fires, and
                // AccountController re-lazy-creates a fresh PENDING_PROFILE row.
                // The user retries completeProfile with a different DOB and the
                // COPPA gate is bypassed. Enabling checkRevoked here makes
                // verifyIdToken consult validSince (updated by revokeRefreshTokens)
                // so the post-rejection token fails immediately.
                //
                // /api/v1/* reads keep the cheaper path — under-13 collateral read
                // access for ≤1h after rejection is not a COPPA write violation,
                // and /api/v1/* is high-traffic. If full revocation across all
                // namespaces is needed, route this through validSince-bypass tracking
                // in a dedicated plan (the AuthFilter cost grows linearly with RPS).
                boolean checkRevoked = requestURI.startsWith("/api/admin/")
                        || requestURI.startsWith("/api/account/");
                FirebaseToken decodedToken = firebaseAuth.verifyIdToken(token, checkRevoked);
                String uid = decodedToken.getUid();
                String email = decodedToken.getEmail();

                // Server-authoritative status check against Firestore.
                //
                // Cubic R7 P1 — switched from cached findByUid() to
                // findByUidUncached(). The cached path stores results in a
                // per-JVM Caffeine @Cacheable("userStatus", TTL=60s); on a
                // multi-node deployment a user blocked on node A continues to
                // authenticate on node B for up to 60s. Bulk-action targeting
                // already uses findByUidUncached for the same reason. The
                // canonical Plan-D multi-instance cache (Redis + pub/sub
                // eviction) remains deferred to a dedicated plan; until then,
                // uncached is the only safe enforcement read.
                try {
                    // Cubic R-final7 P2 — findByUidUncached uses
                    // FirestoreTimeoutProperties.getRead() (2s default,
                    // configurable). TimeoutException catch below maps to
                    // 503 SERVICE_UNAVAILABLE; the auth path cannot hang.
                    Optional<User> userOpt = userRepository.findByUidUncached(uid);
                    if (userOpt.isPresent()) {
                        User u = userOpt.get();
                        if (u.isDeleted()) {
                            // Emit 403 + ACCOUNT_DELETED so the Android
                            // AccountStatusInterceptor (Plan B T3) triggers
                            // signOut + terminal-dialog. The earlier 401 +
                            // ACCOUNT_NOT_FOUND shape was never consumed by
                            // the client — soft-deleted users would see
                            // opaque 401s for up to the access-token TTL
                            // (~1h) and lose the terminal-dialog UX.
                            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                "ACCOUNT_DELETED", "Your account has been deleted.");
                            return;
                        }
                        if (u.isBlocked()) {
                            // F15: do NOT echo u.getBlockReason() in the response. Moderators
                            // commonly write internal notes there ("known troll, banned per
                            // ticket #1234, contact legal") — exfiltrating them to the
                            // blocked user is a privacy hazard. The block reason stays on
                            // the User doc + audit log for internal observability; the
                            // public response is just the coarse-grained code.
                            writeError(response, HttpServletResponse.SC_FORBIDDEN,
                                "ACCOUNT_BLOCKED", "Your account is blocked.");
                            return;
                        }
                    }
                    // No Firestore doc yet (first-time user) — allow; Plan C will create it.
                } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException e) {
                    logger.error("Status check failed for uid {}: {}", uid, e.getMessage());
                    writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE", "Account status check failed; try again.");
                    return;
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    logger.error("Status check interrupted for uid {}", uid);
                    writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                        "SERVICE_UNAVAILABLE", "Account status check failed; try again.");
                    return;
                } catch (RuntimeException e) {
                    // Cubic R7 P1 — fail-open instead of 503-blanket on
                    // non-status-sensitive routes. Cubic R-final-verify P1
                    // — fail-CLOSED on /api/admin/ + /api/v1/me + lifecycle
                    // routes where a blocked/deleted user must NOT slip
                    // through a mapping quirk.
                    //
                    // The fail-open trade-off is correct for read-only
                    // public surfaces (/api/v1/categories, /api/v1/channels,
                    // public catalog) — a Caffeine load failure or mapper
                    // NPE shouldn't DoS the entire authenticated read path.
                    // But admin endpoints write or expose privileged data;
                    // there a blocked admin slipping through is far worse
                    // than 503ing them until the mapper is fixed.
                    // Cubic R-final2 P1 — extend fail-closed to ALL
                    // authenticated writes (POST/PUT/PATCH/DELETE). The
                    // explicit URI list missed /api/share-metadata/** and
                    // any future authenticated write surface; a blocked user
                    // could still poison server state during a mapper hiccup.
                    // Method-based gating is robust to new routes.
                    String method = request.getMethod();
                    boolean isWrite = "POST".equals(method) || "PUT".equals(method)
                            || "PATCH".equals(method) || "DELETE".equals(method);
                    boolean statusSensitive = isWrite
                            || requestURI.startsWith("/api/admin/")
                            || requestURI.startsWith("/api/account/")
                            || requestURI.equals("/api/v1/me")
                            || requestURI.startsWith("/api/v1/me/");
                    if (statusSensitive) {
                        logger.error("Unexpected error during status check for uid {} on status-sensitive route {} — failing CLOSED: {}",
                                uid, requestURI, e.getMessage(), e);
                        writeError(response, HttpServletResponse.SC_SERVICE_UNAVAILABLE,
                                "SERVICE_UNAVAILABLE", "Account status check failed; try again.");
                        return;
                    }
                    logger.error("Unexpected error during status check for uid {} on route {} — failing open: {}",
                            uid, requestURI, e.getMessage(), e);
                    // fall through — non-status-sensitive request continues
                    // unauthorized-by-status but with a valid identity attached below.
                }

                // Extract role from custom claims with allowlist validation
                Object roleClaim = decodedToken.getClaims().get(ROLE_CLAIM);
                String role;
                if (roleClaim instanceof String && VALID_ROLES.contains(((String) roleClaim).toLowerCase(Locale.ROOT))) {
                    role = ((String) roleClaim).toLowerCase(Locale.ROOT);
                } else {
                    role = "user"; // Default role if not set or not in allowlist
                }

                // Create Spring Security authentication with role as authority
                SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.toUpperCase(Locale.ROOT));
                FirebaseUserDetails userDetails = new FirebaseUserDetails(uid, email, role, decodedToken.isEmailVerified());

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        List.of(authority)
                );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                logger.debug("✓ Authenticated user UID: {} with role: {} (authority: ROLE_{})", uid, role, role.toUpperCase(Locale.ROOT));

            } catch (FirebaseAuthException e) {
                logger.error("Firebase token verification failed: {}", e.getMessage());
                // Task 19 Stage 5 §3 M1 — the lifecycle 403 arms above were
                // unreachable for exactly the users they exist for. Both
                // AuthService.softDeleteUser and blockUser do setDisabled(true)
                // + revokeRefreshTokens(uid) before anything else, so on the
                // checkRevoked namespaces verifyIdToken throws here and the
                // client only ever saw a bare 401 — no ACCOUNT_DELETED /
                // ACCOUNT_BLOCKED verdict, so no device wipe and no terminal
                // dialog on either mobile client.
                //
                // Only these two codes are recoverable: RevocationCheckDecorator
                // runs AFTER the base verifier, so USER_DISABLED and
                // REVOKED_ID_TOKEN are the only failures a decode without the
                // revocation check can still get a uid out of. EXPIRED_ID_TOKEN
                // and friends fail the unchecked decode too — gating on them
                // would buy a second Firebase verification on the most common
                // 401 there is (hourly token expiry) and change nothing.
                AuthErrorCode authErrorCode = e.getAuthErrorCode();
                if ((authErrorCode == AuthErrorCode.USER_DISABLED
                        || authErrorCode == AuthErrorCode.REVOKED_ID_TOKEN)
                        && writeLifecycleVerdict(response, token)) {
                    return;
                }
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                // RFC 6750 §3 — without this challenge both mobile clients'
                // one-time token-refresh retry is dead code: iOS
                // AuthorizedTransport and Android FirebaseAuthInterceptor each
                // gate the retry on a "WWW-Authenticate: Bearer" response
                // header no 401 in this backend was setting.
                response.setHeader(WWW_AUTHENTICATE_HEADER, BEARER_CHALLENGE);
                response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
                response.setContentType("application/json");
                return;
            }
        }

        filterChain.doFilter(request, response);
    }

    /**
     * M1 — recover the account-lifecycle verdict for a token the revocation
     * check just rejected: decode it WITHOUT that check (signature and expiry
     * are still verified) to get the uid, then write the SAME 403 envelope the
     * in-band {@code isDeleted()} / {@code isBlocked()} arms write.
     *
     * @return {@code true} when a verdict was written and the caller must not
     *         fall through to the 401; {@code false} for a healthy user's
     *         merely-revoked token, an undecodable token, or a lookup failure
     *         (401 is the safe verdict in all three — the token is invalid
     *         either way).
     */
    private boolean writeLifecycleVerdict(HttpServletResponse response, String token) throws IOException {
        String uid;
        try {
            uid = firebaseAuth.verifyIdToken(token, false).getUid();
        } catch (FirebaseAuthException e) {
            return false;
        }
        try {
            Optional<User> userOpt = userRepository.findByUidUncached(uid);
            if (userOpt.isPresent()) {
                User u = userOpt.get();
                if (u.isDeleted()) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "ACCOUNT_DELETED", "Your account has been deleted.");
                    return true;
                }
                if (u.isBlocked()) {
                    writeError(response, HttpServletResponse.SC_FORBIDDEN,
                        "ACCOUNT_BLOCKED", "Your account is blocked.");
                    return true;
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.error("Lifecycle lookup interrupted for uid {}", uid);
        } catch (java.util.concurrent.ExecutionException | java.util.concurrent.TimeoutException
                 | RuntimeException e) {
            logger.error("Lifecycle lookup after rejected token failed for uid {}: {}", uid, e.getMessage());
        }
        return false;
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message) throws IOException {
        writeError(response, status, code, message, Map.of());
    }

    private void writeError(HttpServletResponse response, int status,
                            String code, String message,
                            Map<String, Object> extra) throws IOException {
        response.setStatus(status);
        response.setContentType("application/json");
        Map<String, Object> body = new HashMap<>(extra);
        body.put("code", code);
        body.put("message", message);
        objectMapper.writeValue(response.getWriter(), body);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        // Skip filter for public endpoints only
        // Actuator endpoints are now protected and require ADMIN role (see SecurityConfig)
        String path = request.getRequestURI();
        return path.startsWith("/api/public/") ||
               path.startsWith("/api/v1/") ||  // Mobile app public APIs
               path.equals("/api/auth/login");
    }
}

