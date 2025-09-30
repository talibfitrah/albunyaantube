package com.albunyaan.tube.common.web;

import com.albunyaan.tube.common.TraceContext;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import java.io.IOException;
import java.util.UUID;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.filter.OncePerRequestFilter;

@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final String HEADER_NAME = "traceId";
    private static final String LEGACY_HEADER_NAME = "X-Trace-Id";

    @Override
    protected void doFilterInternal(
        HttpServletRequest request,
        HttpServletResponse response,
        FilterChain filterChain
    ) throws ServletException, IOException {
        var incoming = request.getHeader(LEGACY_HEADER_NAME);
        if (!StringUtils.hasText(incoming)) {
            incoming = request.getHeader(HEADER_NAME);
        }
        var traceId = StringUtils.hasText(incoming) ? incoming : UUID.randomUUID().toString();

        TraceContext.set(traceId);
        try {
            response.setHeader(HEADER_NAME, traceId);
            filterChain.doFilter(request, response);
        } finally {
            TraceContext.clear();
        }
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        var path = request.getRequestURI();
        return path.startsWith("/actuator");
    }
}
