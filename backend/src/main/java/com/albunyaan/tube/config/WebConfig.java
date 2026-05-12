package com.albunyaan.tube.config;

import com.albunyaan.tube.security.SubmissionRateLimitInterceptor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

@Configuration
public class WebConfig implements WebMvcConfigurer {

    private final SubmissionRateLimitInterceptor rateLimit;

    public WebConfig(SubmissionRateLimitInterceptor rateLimit) { this.rateLimit = rateLimit; }

    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(rateLimit)
                .addPathPatterns(
                    "/api/admin/registry/channels",
                    "/api/admin/registry/playlists",
                    "/api/admin/registry/videos"
                );
    }
}
