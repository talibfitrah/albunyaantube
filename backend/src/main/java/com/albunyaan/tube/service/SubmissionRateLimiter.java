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

    /**
     * Returns null if allowed; otherwise seconds until the oldest hit ages out.
     *
     * <p>Cubic R7 P2 — blank uid now {@code throws IllegalArgumentException}
     * instead of returning "allowed".
     *
     * <p>Pre-fix the null/empty-uid path returned null (= allowed), which
     * silently bypassed the per-user limit when a caller forgot to thread
     * principal.uid through. The rate limit was supposed to be the user's
     * own write quota — empty-uid sneaking past defeated the cap.
     */
    public Long tryAcquire(String uid) {
        return tryAcquire(uid, 1);
    }

    /**
     * Atomically consume {@code count} slots from the per-uid budget. Returns
     * null when all {@code count} slots were granted; otherwise returns
     * seconds until the oldest hit ages out. Failure is all-or-nothing —
     * no slots are taken if {@code count} would exceed the per-uid {@link #LIMIT}.
     *
     * <p>Used by the bulk-submit path so a {@code POST /bulk/submit} with
     * {@code N} rows consumes {@code N} budget slots, not 1. Without this,
     * one bulk HTTP call consumed only one slot from the 50/24h budget while
     * fanning out 25 Firestore writes, giving the bulk path a 25x effective
     * throughput vs single-add (Stage 1 P1 / Stage 2 H3 in the post-Plan-G
     * 7-stage review).
     */
    public Long tryAcquire(String uid, int count) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("tryAcquire requires a non-blank uid");
        }
        if (count < 1) {
            throw new IllegalArgumentException("count must be >= 1, got " + count);
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);
        // Atomic compute — see single-arg overload for the eviction-race
        // rationale.
        Long[] retryAfter = new Long[]{null};
        hits.asMap().compute(uid, (key, existing) -> {
            Deque<Instant> dq = existing != null ? existing : new ArrayDeque<>();
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() + count > LIMIT) {
                // All-or-nothing: leave the deque untouched. retry-after is
                // computed from the oldest existing slot when the deque is
                // non-empty, else from `now` (degenerate path — count > LIMIT
                // is rejected by SubmitRow.@Size validation, but keep the
                // math defensive).
                Instant oldest = dq.isEmpty() ? now : dq.peekFirst();
                retryAfter[0] = oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            } else {
                for (int i = 0; i < count; i++) dq.addLast(now);
            }
            return dq;
        });
        return retryAfter[0];
    }
}
