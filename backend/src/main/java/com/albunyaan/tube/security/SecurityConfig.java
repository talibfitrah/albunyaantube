package com.albunyaan.tube.security;

import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.annotation.web.configurers.AbstractHttpConfigurer;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

import java.util.List;

/**
 * FIREBASE-MIGRATE-02: Spring Security Configuration
 *
 * Configures security for Firebase-based authentication:
 * - Stateless session (JWT-based)
 * - Firebase token verification filter
 * - CORS configuration
 * - Public/protected endpoint rules
 */
@Configuration
@EnableWebSecurity
@EnableMethodSecurity(prePostEnabled = true)
public class SecurityConfig {

    private static final Logger logger = LoggerFactory.getLogger(SecurityConfig.class);
    private final FirebaseAuthFilter firebaseAuthFilter;

    public SecurityConfig(FirebaseAuthFilter firebaseAuthFilter) {
        this.firebaseAuthFilter = firebaseAuthFilter;
    }

    @Bean
    public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
        http
                .csrf(AbstractHttpConfigurer::disable)
                .cors(cors -> cors.configurationSource(corsConfigurationSource()))
                .sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                .authorizeHttpRequests(auth -> auth
                        // Public endpoints - ORDER MATTERS! Most specific first
                        .requestMatchers("/api/public/**").permitAll()
                        .requestMatchers("/api/v1/**").permitAll() // Public mobile app APIs (includes /api/v1/search)
                        .requestMatchers(HttpMethod.POST, "/api/auth/login").permitAll()

                        // Actuator endpoints - ADMIN only for production security
                        .requestMatchers("/actuator/**").hasRole("ADMIN")

                        // Admin-only endpoints
                        .requestMatchers("/api/admin/users/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.POST, "/api/admin/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.PUT, "/api/admin/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/categories/**").hasRole("ADMIN")
                        .requestMatchers(HttpMethod.DELETE, "/api/admin/**").hasRole("ADMIN")

                        // Moderator and Admin endpoints
                        .requestMatchers("/api/admin/**").hasAnyRole("ADMIN", "MODERATOR")

                        // All other requests require authentication
                        .anyRequest().authenticated()
                )
                .exceptionHandling(ex -> ex
                        .accessDeniedHandler((request, response, accessDeniedException) -> {
                            logger.warn("Access denied to {} - User: {} - Exception: {}",
                                    request.getRequestURI(),
                                    request.getUserPrincipal(),
                                    accessDeniedException.getMessage());
                            response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                            response.getWriter().write("{\"error\": \"Access denied\"}");
                            response.setContentType("application/json");
                        })
                )
                .addFilterBefore(firebaseAuthFilter, UsernamePasswordAuthenticationFilter.class);

        return http.build();
    }

    @Bean
    public CorsConfigurationSource corsConfigurationSource() {
        CorsConfiguration configuration = new CorsConfiguration();
        // Allow web frontend (localhost during development)
        configuration.setAllowedOrigins(List.of(
                "http://localhost:5173",
                "http://127.0.0.1:5173"
        ));
        // NOTE: Mobile apps do NOT need CORS configuration
        // CORS is a browser security feature and does not apply to native mobile apps.
        // Mobile apps make direct HTTP requests without Origin headers.
        configuration.setAllowedMethods(List.of("GET", "POST", "PUT", "DELETE", "PATCH", "OPTIONS"));
        configuration.setAllowedHeaders(List.of("*"));
        configuration.setAllowCredentials(false);
        configuration.setMaxAge(3600L);

        UrlBasedCorsConfigurationSource source = new UrlBasedCorsConfigurationSource();
        source.registerCorsConfiguration("/api/**", configuration);
        return source;
    }
}

