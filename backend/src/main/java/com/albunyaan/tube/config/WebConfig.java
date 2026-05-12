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

    @Bean
    public SubmissionRateLimitInterceptor submissionRateLimitInterceptor(
            ObjectProvider<SubmissionRateLimiter> limiter,
            ObjectMapper json) {
        SubmissionRateLimiter resolved = limiter.getIfAvailable();
        if (resolved == null) return null;   // interceptor will be absent in slices that don't load the limiter
        return new SubmissionRateLimitInterceptor(resolved, json);
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
