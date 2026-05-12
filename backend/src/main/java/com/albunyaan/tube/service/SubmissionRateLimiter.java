package com.albunyaan.tube.service;

import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Plan E — per-uid sliding-window rate limiter for moderator submissions.
 * 50 submissions per 24h. In-memory only; will not survive process restart
 * (acceptable for ≤20-user pre-release scale; migrate to Redis if needed).
 */
@Component
public class SubmissionRateLimiter {
    public static final int LIMIT = 50;
    public static final Duration WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final Map<String, Deque<Instant>> hits = new ConcurrentHashMap<>();

    public SubmissionRateLimiter(Clock clock) { this.clock = clock; }

    /** Returns null if allowed; otherwise seconds until the oldest hit ages out. */
    public Long tryAcquire(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> dq = hits.computeIfAbsent(uid, k -> new ArrayDeque<>());
        synchronized (dq) {
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() >= LIMIT) {
                Instant oldest = dq.peekFirst();
                return oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            }
            dq.addLast(now);
            return null;
        }
    }
}
