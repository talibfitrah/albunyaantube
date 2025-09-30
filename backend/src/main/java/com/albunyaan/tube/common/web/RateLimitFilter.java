package com.albunyaan.tube.common.web;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import org.springframework.context.MessageSource;
import org.springframework.context.i18n.LocaleContextHolder;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.server.ResponseStatusException;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class RateLimitFilter extends OncePerRequestFilter {

    private static final Duration WINDOW = Duration.ofSeconds(60);
    private static final int LIMIT = 120;
    private static final String API_PREFIX = "/api/";

    private final Map<String, RequestWindow> buckets = new ConcurrentHashMap<>();
    private final MessageSource messageSource;

    public RateLimitFilter(MessageSource messageSource) {
        this.messageSource = messageSource;
    }

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var key = resolveClientKey(request);
        var now = Instant.now();

        var bucket = buckets.compute(key, (ignored, existing) -> {
            if (existing == null || now.isAfter(existing.windowStart().plus(WINDOW))) {
                return new RequestWindow(now, new AtomicInteger(0));
            }
            return existing;
        });

        var attempts = bucket.counter().incrementAndGet();
        if (attempts > LIMIT) {
            var locale = LocaleContextHolder.getLocale();
            var message = messageSource.getMessage("error.rate-limit", null, "Too many requests", locale);
            throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS, message);
        }

        filterChain.doFilter(request, response);
    }

    private String resolveClientKey(HttpServletRequest request) {
        var remoteAddr = request.getRemoteAddr();
        var authHeader = request.getHeader("Authorization");
        if (authHeader != null && !authHeader.isBlank()) {
            return remoteAddr + '|' + authHeader.hashCode();
        }
        return remoteAddr;
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return !path.startsWith(API_PREFIX);
    }

    private record RequestWindow(Instant windowStart, AtomicInteger counter) {}
}
