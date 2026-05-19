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
     * Refund the specific slot identified by {@code acquiredAt}. Removing
     * by exact {@code Instant} (not by deque-tail) keeps the slot accounting
     * correct when two concurrent acquires for the same uid interleave —
     * popping the tail would refund the other caller's slot.
     *
     * <p>Safe under concurrent {@link #acquire(String)} calls because the
     * deque mutation runs inside the same per-key {@code compute} lock.
     */
    public void release(String uid, Instant acquiredAt) {
        if (uid == null || uid.isBlank() || acquiredAt == null) return;
        hits.asMap().computeIfPresent(uid, (key, dq) -> {
            dq.removeFirstOccurrence(acquiredAt);
            return dq;
        });
    }

    /**
     * Acquire a slot and return its timestamp so the caller can
     * {@link #release(String, Instant)} it later if the request fails.
     * Returns {@link AcquireOutcome.Limited} with {@code retryAfterSec} when
     * the uid is over the hourly budget.
     */
    public AcquireOutcome acquire(String uid) {
        if (uid == null || uid.isBlank()) {
            throw new IllegalArgumentException("acquire requires a non-blank uid");
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
        return retryAfter[0] == null
                ? AcquireOutcome.acquired(now)
                : AcquireOutcome.limited(retryAfter[0]);
    }

    /** Two-shape outcome for {@link #acquire(String)}. */
    public sealed interface AcquireOutcome {
        record Acquired(Instant slot) implements AcquireOutcome {}
        record Limited(long retryAfterSec) implements AcquireOutcome {}
        static Acquired acquired(Instant slot) { return new Acquired(slot); }
        static Limited limited(long retryAfterSec) { return new Limited(retryAfterSec); }
    }
}
