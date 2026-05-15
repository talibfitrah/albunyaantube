package com.albunyaan.tube.config;

import com.albunyaan.tube.security.SubmissionRateLimitInterceptor;
import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Plan E — wires the submission rate-limit interceptor onto registry POSTs.
 *
 * The interceptor bean is constructed here (rather than via @Component scan)
 * so @WebMvcTest slices that don't load regular @Component services don't
 * fail with a missing SubmissionRateLimiter dependency. ObjectProvider on
 * the limiter lets the config itself load even when the limiter is absent.
 */
@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final ObjectProvider<SubmissionRateLimitInterceptor> interceptorProvider;

    public WebConfig(ObjectProvider<SubmissionRateLimitInterceptor> interceptorProvider) {
        this.interceptorProvider = interceptorProvider;
    }

    /**
     * Conditional bean: only registers the interceptor when {@link SubmissionRateLimiter}
     * is on the context. The previous shape returned {@code null}, which registered a
     * null bean under the name — a future {@code @Autowired SubmissionRateLimitInterceptor}
     * (non-Optional) would NPE rather than fail wiring (cubic R5 P2). The
     * {@link org.springframework.boot.autoconfigure.condition.ConditionalOnBean}
     * annotation lets Spring omit the bean entirely when the limiter isn't loaded
     * (e.g. {@code @WebMvcTest} slices); {@link InterceptorRegistry#addInterceptors}
     * below uses {@code ObjectProvider.ifAvailable} so the registration is a no-op
     * when the bean is missing.
     */
    @Bean
    @org.springframework.boot.autoconfigure.condition.ConditionalOnBean(SubmissionRateLimiter.class)
    public SubmissionRateLimitInterceptor submissionRateLimitInterceptor(
            SubmissionRateLimiter limiter,
            ObjectMapper json) {
        return new SubmissionRateLimitInterceptor(limiter, json);
    }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        interceptorProvider.ifAvailable(interceptor ->
                registry.addInterceptor(interceptor)
                        .addPathPatterns(
                                "/api/admin/registry/channels",
                                "/api/admin/registry/playlists",
                                "/api/admin/registry/videos"
                        )
        );
    }
}
