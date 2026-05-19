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
 * Plan G B5 — per-uid sliding-window rate limiter for profile updates.
 * 10 updates per hour. In-memory only; will not survive process restart
 * (acceptable for pre-release scale; migrate to Redis if multi-instance).
 *
 * <p>Mirrors {@link SubmissionRateLimiter} exactly: Caffeine cache for
 * automatic eviction of cold uids, atomic {@code compute} to close the
 * eviction-race window documented in Plan E.
 */
@Component
public class ProfileUpdateRateLimiter {

    public static final int LIMIT = 10;
    public static final Duration WINDOW = Duration.ofHours(1);

    private final Clock clock;
    private final Cache<String, Deque<Instant>> hits;

    public ProfileUpdateRateLimiter(Clock clock) {
        this.clock = clock;
        this.hits = Caffeine.newBuilder()
                .expireAfterAccess(WINDOW.toMinutes(), TimeUnit.MINUTES)
                .maximumSize(100_000)
                .build();
    }

    /**
     * Returns {@code null} if the request is allowed; otherwise returns
     * the number of seconds until the oldest hit in the current window ages
     * out (i.e. the earliest the caller may retry).
     *
     * @throws IllegalArgumentException if {@code uid} is null or blank —
     *     same guard as {@link SubmissionRateLimiter#tryAcquire} (cubic R7 P2).
     */
    public Long tryAcquire(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("tryAcquire requires a non-blank uid");
        }
        Instant now = clock.instant();
        Instant cutoff = now.minus(WINDOW);

        Long[] retryAfter = new Long[]{null};
        hits.asMap().compute(uid, (key, existing) -> {
            Deque<Instant> dq = existing != null ? existing : new ArrayDeque<>();
            while (!dq.isEmpty() && dq.peekFirst().isBefore(cutoff)) dq.pollFirst();
            if (dq.size() >= LIMIT) {
                Instant oldest = dq.peekFirst();
                retryAfter[0] = oldest.plus(WINDOW).getEpochSecond() - now.getEpochSecond();
            } else {
                dq.addLast(now);
            }
            return dq;
        });
        return retryAfter[0];
    }

    /**
     * Plan G cubic R5 P1 — refund the most-recent slot for {@code uid}.
     *
     * <p>Called from {@code ProfileUpdateRateLimitInterceptor#afterCompletion}
     * when a request that consumed a slot returned a non-2xx status. A user
     * who fat-fingers a display name and hits 400 ten times in a row would
     * otherwise burn their hourly budget without ever landing an edit;
     * refund-on-failure preserves the abuse gate (success / 5xx still
     * count) while keeping the UX usable.
     *
     * <p>Safe under concurrent {@link #tryAcquire(String)} calls because the
     * deque mutation runs inside the same per-key {@code compute} lock.
     */
    public void releaseLast(String uid) {
        if (uid == null || uid.isBlank()) return;
        hits.asMap().computeIfPresent(uid, (key, dq) -> {
            dq.pollLast();
            return dq;
        });
    }
}
