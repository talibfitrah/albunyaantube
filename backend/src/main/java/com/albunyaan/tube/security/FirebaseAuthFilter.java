package com.albunyaan.tube.security;

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
import java.util.List;

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

    private final FirebaseAuth firebaseAuth;

    public FirebaseAuthFilter(FirebaseAuth firebaseAuth) {
        this.firebaseAuth = firebaseAuth;
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
                FirebaseToken decodedToken = firebaseAuth.verifyIdToken(token);
                String uid = decodedToken.getUid();
                String email = decodedToken.getEmail();

                // Extract role from custom claims
                String role = (String) decodedToken.getClaims().get(ROLE_CLAIM);
                if (role == null) {
                    role = "user"; // Default role if not set
                }

                // Create Spring Security authentication with role as authority
                SimpleGrantedAuthority authority = new SimpleGrantedAuthority("ROLE_" + role.toUpperCase());
                FirebaseUserDetails userDetails = new FirebaseUserDetails(uid, email, role);

                UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
                        userDetails,
                        null,
                        List.of(authority)
                );

                authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
                SecurityContextHolder.getContext().setAuthentication(authentication);

                logger.debug("✓ Authenticated user UID: {} with role: {} (authority: ROLE_{})", uid, role, role.toUpperCase());

            } catch (FirebaseAuthException e) {
                logger.error("Firebase token verification failed: {}", e.getMessage());
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.getWriter().write("{\"error\": \"Invalid or expired token\"}");
                response.setContentType("application/json");
                return;
            }
        }

        filterChain.doFilter(request, response);
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

