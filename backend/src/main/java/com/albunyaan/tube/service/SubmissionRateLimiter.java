package com.albunyaan.tube.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.concurrent.TimeUnit;

/**
 * Plan E — per-uid sliding-window rate limiter for moderator submissions.
 * 50 submissions per 24h. In-memory only; will not survive process restart
 * (acceptable for ≤20-user pre-release scale; migrate to Redis if needed).
 *
 * <p>The per-uid deques live in a Caffeine cache rather than a bare
 * {@link java.util.concurrent.ConcurrentHashMap}. A {@code ConcurrentHashMap}
 * keyed by uid has no eviction — once a uid is added, the entry sticks
 * forever (the deque trim loop empties the deque but never removes the map
 * key). If the authenticated surface ever widens, the map grows
 * monotonically. The Caffeine cache evicts entries one window after their
 * last hit, so cold uids are reclaimed automatically.
 */
@Component
public class SubmissionRateLimiter {
    public static final int LIMIT = 50;
    public static final Duration WINDOW = Duration.ofHours(24);

    private final Clock clock;
    private final Cache<String, Deque<Instant>> hits;

    public SubmissionRateLimiter(Clock clock) {
        this.clock = clock;
        this.hits = Caffeine.newBuilder()
                .expireAfterAccess(WINDOW.toMinutes(), TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
    }

    /** Returns null if allowed; otherwise seconds until the oldest hit ages out. */
    public Long tryAcquire(String uid) {
        if (uid == null || uid.isEmpty()) return null;
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        Deque<Instant> dq = hits.get(uid, k -> new ArrayDeque<>());
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
