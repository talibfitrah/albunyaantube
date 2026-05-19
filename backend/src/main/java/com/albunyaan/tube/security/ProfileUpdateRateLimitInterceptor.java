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
 * Profile-update rate-limit gate — 10 PUT /api/account/profile per uid per hour.
 * Not {@code @Component}: registered explicitly so {@code @WebMvcTest} slices
 * don't auto-pick it up. Mirrors {@link SubmissionRateLimitInterceptor}.
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
        // PUT-only gate — same path also serves POST (completeProfile) and
        // OPTIONS preflights which would otherwise drain the bucket.
        if (!"PUT".equals(req.getMethod())) {
            return true;
        }
        var auth = SecurityContextHolder.getContext().getAuthentication();
        if (auth == null
                || !auth.isAuthenticated()
                || auth instanceof AnonymousAuthenticationToken) {
            return true;
        }
        Object principal = auth.getPrincipal();
        if (!(principal instanceof FirebaseUserDetails fud)) return true;

        var outcome = limiter.acquire(fud.getUid());
        if (outcome instanceof com.albunyaan.tube.service.ProfileUpdateRateLimiter.AcquireOutcome.Acquired acq) {
            // Stash the exact slot Instant so afterCompletion can refund
            // it precisely (not the deque tail, which may be another
            // concurrent acquire from the same uid).
            req.setAttribute(ATTR_RATE_LIMITED_UID, fud.getUid());
            req.setAttribute(ATTR_RATE_LIMITED_SLOT, acq.slot());
            return true;
        }

        long retryAfter = ((com.albunyaan.tube.service.ProfileUpdateRateLimiter.AcquireOutcome.Limited) outcome)
                .retryAfterSec();
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
     * Refund on 4xx client errors so a streak of validation failures
     * doesn't lock out a legitimate user. 2xx/5xx still consume the slot.
     */
    @Override
    public void afterCompletion(HttpServletRequest req, HttpServletResponse res,
                                Object handler, Exception ex) {
        Object uidAttr = req.getAttribute(ATTR_RATE_LIMITED_UID);
        Object slotAttr = req.getAttribute(ATTR_RATE_LIMITED_SLOT);
        if (!(uidAttr instanceof String uid)) return;
        if (!(slotAttr instanceof java.time.Instant slot)) return;
        int status = res.getStatus();
        if (status >= 400 && status < 500) {
            limiter.release(uid, slot);
        }
    }

    private static final String ATTR_RATE_LIMITED_UID =
            ProfileUpdateRateLimitInterceptor.class.getName() + ".uid";
    private static final String ATTR_RATE_LIMITED_SLOT =
            ProfileUpdateRateLimitInterceptor.class.getName() + ".slot";
}
