package com.albunyaan.tube.security;

import com.albunyaan.tube.service.SubmissionRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plan E — submission rate-limit gate.
 *
 * Not annotated {@code @Component}: registered as a bean via
 * {@link com.albunyaan.tube.config.WebConfig} so {@code @WebMvcTest} slices
 * (which auto-load every {@link HandlerInterceptor} bean and would fail on
 * the {@link SubmissionRateLimiter} dependency) don't pick it up by accident.
 */
public class SubmissionRateLimitInterceptor implements HandlerInterceptor {

    private final SubmissionRateLimiter limiter;
    private final ObjectMapper json;

    public SubmissionRateLimitInterceptor(SubmissionRateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler) throws Exception {
        if (!"POST".equals(req.getMethod())) return true;
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null || !auth.isAuthenticated()) return true;
        Object principal = auth.getPrincipal();
        if (!(principal instanceof FirebaseUserDetails fud)) return true;
        Long retryAfter = limiter.tryAcquire(fud.getUid());
        if (retryAfter == null) return true;
        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RATE_LIMIT");
        body.put("retryAfterSeconds", retryAfter);
        body.put("message", "Daily submission limit reached. Try again later.");
        json.writeValue(res.getWriter(), body);
        return false;
    }
}
