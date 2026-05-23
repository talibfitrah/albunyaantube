package com.albunyaan.tube.config;

import com.albunyaan.tube.security.ProfileUpdateRateLimitInterceptor;
import com.albunyaan.tube.security.SubmissionRateLimitInterceptor;
import com.albunyaan.tube.service.ProfileUpdateRateLimiter;
import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Plans E + G — wires rate-limit interceptors.
 *
 * <ul>
 *   <li>Plan E: {@link SubmissionRateLimitInterceptor} on admin registry POSTs
 *       (50 submissions / 24h per uid).
 *   <li>Plan G B5: {@link ProfileUpdateRateLimitInterceptor} on
 *       PUT /api/account/profile (10 updates / hour per uid).
 * </ul>
 *
 * Interceptors are constructed as {@code @Bean}s here rather than via
 * {@code @Component} scan so {@code @WebMvcTest} slices that don't load
 * regular {@code @Component} services don't fail with a missing limiter
 * dependency. {@link ObjectProvider} lets the config itself load even when
 * a limiter bean is absent. {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}
 * lets Spring omit the interceptor bean entirely when the limiter is absent
 * (e.g. in {@code @WebMvcTest} slices); {@code ObjectProvider.ifAvailable}
 * in {@link #addInterceptors} makes registration a no-op when the bean is
 * missing.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<SubmissionRateLimitInterceptor> submissionInterceptorProvider;
    private final ObjectProvider<ProfileUpdateRateLimitInterceptor> profileUpdateInterceptorProvider;

    public WebConfig(
            ObjectProvider<SubmissionRateLimitInterceptor> submissionInterceptorProvider,
            ObjectProvider<ProfileUpdateRateLimitInterceptor> profileUpdateInterceptorProvider) {
        this.submissionInterceptorProvider = submissionInterceptorProvider;
        this.profileUpdateInterceptorProvider = profileUpdateInterceptorProvider;
    }

    // ── Plan E ────────────────────────────────────────────────────────────────

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(SubmissionRateLimiter.class)
    public SubmissionRateLimitInterceptor submissionRateLimitInterceptor(
            SubmissionRateLimiter limiter,
            ObjectMapper json) {
        return new SubmissionRateLimitInterceptor(limiter, json);
    }

    // ── Plan G B5 ─────────────────────────────────────────────────────────────

    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(ProfileUpdateRateLimiter.class)
    public ProfileUpdateRateLimitInterceptor profileUpdateRateLimitInterceptor(
            ProfileUpdateRateLimiter limiter,
            ObjectMapper json) {
        return new ProfileUpdateRateLimitInterceptor(limiter, json);
    }

    // ── Registry ──────────────────────────────────────────────────────────────

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        submissionInterceptorProvider.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor)
                        .addPathPatterns(
                                "/api/admin/registry/channels",
                                "/api/admin/registry/playlists",
                                "/api/admin/registry/videos",
                                // Bulk preview/submit
                                // share the same 50/24h per-uid budget so a moderator
                                // can't fan out 50 bulk batches of 25 URLs each = 1250
                                // writes/day via the bulk path while the single-add
                                // path is correctly throttled.
                                "/api/admin/registry/bulk/preview",
                                "/api/admin/registry/bulk/submit"
                        )
        );
        profileUpdateInterceptorProvider.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor)
                        .addPathPatterns("/api/account/profile")
        );
    }
}
