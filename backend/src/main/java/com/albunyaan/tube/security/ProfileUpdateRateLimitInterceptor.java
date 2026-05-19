package com.albunyaan.tube.security;

import com.albunyaan.tube.service.ProfileUpdateRateLimiter;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.authentication.AnonymousAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plan G B5 — profile-update rate-limit gate.
 * Allows 10 PUT /api/account/profile calls per uid per hour.
 *
 * <p>Not annotated {@code @Component}: registered as a bean via
 * {@link com.albunyaan.tube.config.WebConfig} so {@code @WebMvcTest} slices
 * (which auto-load every {@link HandlerInterceptor} bean and would fail on
 * the {@link ProfileUpdateRateLimiter} dependency) don't pick it up by
 * accident. Mirrors the shape of {@link SubmissionRateLimitInterceptor}.
 */
public class ProfileUpdateRateLimitInterceptor implements HandlerInterceptor {

    private final ProfileUpdateRateLimiter limiter;
    private final ObjectMapper json;

    public ProfileUpdateRateLimitInterceptor(ProfileUpdateRateLimiter limiter, ObjectMapper json) {
        this.limiter = limiter;
        this.json = json;
    }

    @Override
    public boolean preHandle(HttpServletRequest req, HttpServletResponse res, Object handler)
            throws Exception {
        // Plan G review-fix (cso + codex MED): only PUT consumes the budget.
        // {@code /api/account/profile} also serves POST (completeProfile) and
        // an implicit OPTIONS preflight on cross-origin calls; without this
        // gate a user who completes their profile and then hits a preflight
        // burst would drain their hourly bucket without ever calling the
        // intended PUT endpoint. Mirrors SubmissionRateLimitInterceptor:35.
        if (!"PUT".equals(req.getMethod())) {
            return true;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        // Exclude null, unauthenticated, and anonymous tokens — same guard as
        // SubmissionRateLimitInterceptor (cubic R5 P2).
        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof FirebaseUserDetails fud)) return true;

        Long retryAfter = limiter.tryAcquire(fud.getUid());
        if (retryAfter == null) {
            // Plan G cubic R5 P1: remember the uid that consumed the slot so
            // afterCompletion can refund it if the request ends in non-2xx.
            req.setAttribute(ATTR_RATE_LIMITED_UID, fud.getUid());
            return true;
        }

        res.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());
        res.setHeader("Retry-After", String.valueOf(retryAfter));
        res.setContentType(MediaType.APPLICATION_JSON_VALUE);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("code", "RATE_LIMIT");
        body.put("retryAfterSeconds", retryAfter);
        body.put("message", "Hourly profile-update limit reached. Try again later.");
        json.writeValue(res.getWriter(), body);
        return false;
    }

    /**
     * Plan G cubic R5 P1 — refund the slot when the request ended in a
     * non-2xx status. Keeps the abuse gate intact for true bot-style
     * floods (which produce 2xx successes or 5xx server errors) while
     * preventing legitimate users from being locked out by a streak of
     * validation-error PUTs.
     */
    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        Object uidAttr = req.getAttribute(ATTR_RATE_LIMITED_UID);
        if (!(uidAttr instanceof String uid)) return;
        int status = res.getStatus();
        // Refund on client errors (4xx) and outright failures (no status
        // set means the response was never written, treat as failure).
        // Keep the slot consumed on 2xx success AND 5xx server faults
        // (5xx may indicate buggy retries we want to rate-limit).
        if (status >= 400 && status < 500) {
            limiter.releaseLast(uid);
        }
    }

    private static final String ATTR_RATE_LIMITED_UID =
            ProfileUpdateRateLimitInterceptor.class.getName() + ".uid";
}
